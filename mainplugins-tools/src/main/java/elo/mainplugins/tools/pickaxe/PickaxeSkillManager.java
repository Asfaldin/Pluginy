package elo.mainplugins.tools.pickaxe;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.GuiUtils;
import elo.mainplugins.tools.pickaxe.BrukSurowce.SurowiecDrop;
import elo.mainplugins.tools.skilltree.RarePerk;
import elo.mainplugins.tools.skilltree.Rarity;
import elo.mainplugins.tools.skilltree.SkillBranch;
import elo.mainplugins.tools.skilltree.ToolSkillManager;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Kilof na silniku ToolSkillManager - w odróżnieniu od siekiery/motyki/miecza NIE jest
 * już jedną ewoluującą linią tierów (drewno→netheryt). Zamiast tego gracz zdobywa jeden
 * z {@link PickaxeType} (Material stały per typ, patrz materialOverride), z których
 * każdy ma WŁASNĄ, dedykowaną pulę kart (branchesFor) i własną automatyczną statystykę
 * rosnącą z poziomem (patrz applyMiningPerks/auraAmplifierFor) - "różne kilofy, różne
 * systemy", zamiast wspólnego drzewka.
 *
 * Bonus z Bruku (jedyne źródło "rudy" na tym Skyblocku - patrz BrukSurowceManager)
 * zostaje UNIWERSALNY dla każdego typu (inaczej gracze bez Kilofa Szczęścia nigdy nie
 * dostaliby diamentu/netherytu) - Szczęścia tylko podbija jego szansę, tak jak dawna
 * karta MAG_BRUK_SZCZESCIE.
 *
 * Gemy (patrz {@link GemType}) - uniwersalne modyfikatory, max {@link #MAX_GEMY} na
 * kilof, osadzane w kowadle (onPrepareAnvil, wzorem FishingManager#onPrepareAnvil w
 * mainplugins-fishing).
 */
public class PickaxeSkillManager extends ToolSkillManager {

    public static final int MAX_GEMY = 3;

    private static final List<SkillBranch> PLACEHOLDER_BRANCHES = List.of(
            new SkillBranch("PLACEHOLDER_A", "-", NamedTextColor.GRAY, Material.BARRIER, List.of()),
            new SkillBranch("PLACEHOLDER_B", "-", NamedTextColor.GRAY, Material.BARRIER, List.of()),
            new SkillBranch("PLACEHOLDER_C", "-", NamedTextColor.GRAY, Material.BARRIER, List.of())
    );

    private final BrukSurowceManager brukSurowceManager;
    private final NamespacedKey pkBrukOn;
    private final NamespacedKey pkBrukDisabled;
    private final NamespacedKey pkType;
    private final NamespacedKey pkGems;
    private final NamespacedKey pkGemId;
    private final NamespacedKey pkSpeedPassiveModifierKey;
    private final NamespacedKey pkMilestones;

    private int slotBrukInfo;

    private final Map<UUID, Long> streakLastBreak = new HashMap<>();
    private final Map<UUID, Integer> streakCount = new HashMap<>();
    private final Map<UUID, Integer> luckStreak = new HashMap<>();

    /**
     * Grupy zamrożonych sąsiadów Wiecznej Zimy (milestone 30) - lokalizacja -> WSPÓLNA
     * instancja Set całej grupy (wszyscy członkowie dzielą ten sam obiekt, więc usunięcie
     * jednego przy łańcuchowym wykopaniu, patrz onNiflFrozenBreak, widać u wszystkich).
     * Bloki w grupie to PRAWDZIWY Material.PACKED_ICE w świecie (nie pakiet/sendBlockChange) -
     * nie pękają same, tylko gdy gracz wykopie KTÓRYKOLWIEK z nich.
     */
    private final Map<Location, Set<Location>> niflFreezeGroups = new HashMap<>();
    /** Drop + oryginalny Material każdego zamrożonego bloku, zebrane W MOMENCIE zamrożenia (tym samym narzędziem) - patrz onNiflFrozenBreak. */
    private final Map<Location, Collection<ItemStack>> niflFreezeDrops = new HashMap<>();
    private final Map<Location, Material> niflFreezeOriginalType = new HashMap<>();

    public PickaxeSkillManager(Plugin plugin, EconomyService economyService, BrukSurowceManager brukSurowceManager) {
        super(plugin, economyService, "pickaxe", "Kilof", PLACEHOLDER_BRANCHES, List.of(), List.of(), "kilof-hub.yml");
        this.brukSurowceManager = brukSurowceManager;
        this.pkBrukOn = new NamespacedKey(plugin, "pk_bruk_on");
        this.pkBrukDisabled = new NamespacedKey(plugin, "pk_bruk_disabled");
        this.pkType = new NamespacedKey(plugin, "pk_type");
        this.pkGems = new NamespacedKey(plugin, "pk_gems");
        this.pkGemId = new NamespacedKey(plugin, "gem_id");
        this.pkSpeedPassiveModifierKey = new NamespacedKey(plugin, "pk_speed_passive");
        this.pkMilestones = new NamespacedKey(plugin, "pk_milestones");
        wczytajSloty();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickNiflheimAmbient, 20L, 10L);
    }

    /** Slot ikonki Bonusu z Bruku - jedyny klucz w kilof-hub.yml nieznany bazowemu ToolSkillManager (gemy pokazują się w ekranie Statystyki, patrz statsBlocks). */
    private void wczytajSloty() {
        File plik = new File(plugin.getDataFolder(), "kilof-hub.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);
        slotBrukInfo = clampSlot(cfg.getInt("sloty.bonus_bruku", 21));
    }

    // ==================================================== Typ kilofa ====

    /** Testowy dzieli mechanikę 1:1 z Wydajnościowym (patrz PickaxeType#TESTOWY) - jedyne miejsce, w którym trzeba to uwzględnić. */
    private boolean jestWydajnosciowy(PickaxeType type) {
        return type == PickaxeType.WYDAJNOSCIOWY || type == PickaxeType.TESTOWY;
    }

    /** Typy zbudowane na nowym silniku progresji (max 30 lvl, 3 stałe ulepszenia zamiast kart) - patrz DOS_MILESTONES/NIFL_MILESTONES. */
    private boolean maNowySilnik(PickaxeType type) {
        return type == PickaxeType.DOSWIADCZENIA || type == PickaxeType.NIFLHEIM;
    }

    private PickaxeType typeOf(PersistentDataContainer pdc) {
        String name = pdc.get(pkType, PersistentDataType.STRING);
        if (name == null) return PickaxeType.WYDAJNOSCIOWY;
        try {
            return PickaxeType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return PickaxeType.WYDAJNOSCIOWY;
        }
    }

    /** Tworzy w pełni zainicjalizowany kilof danego typu, poziom 1 - jedyne wejście do tworzenia kilofów (quest 1, /dajwszystko). */
    public ItemStack stworzKilof(Player player, PickaxeType type) {
        ItemStack item = new ItemStack(type.material());
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyType, PersistentDataType.STRING, "pickaxe");
        pdc.set(keyOwner, PersistentDataType.STRING, player.getUniqueId().toString());
        pdc.set(pkType, PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        ensureInitialized(item);
        return item;
    }

    /** Gem jako fizyczny item do dropu/wręczenia (np. /dajwszystko) - osadzany w kowadle, patrz onPrepareAnvil. */
    public ItemStack stworzGem(GemType gem) {
        ItemStack item = new ItemStack(gem.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(gem.displayName(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text(gem.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Połącz z kilofem w kowadle, aby osadzić.", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(pkGemId, PersistentDataType.STRING, gem.name());
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    /** Maksymalny poziom wśród WSZYSTKICH trzymanych kilofów gracza (dowolnego typu) - pod ToolsService#poziomKilofa. */
    public int poziomNajlepszegoKilofa(Player player) {
        int max = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getItemMeta() == null) continue;
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            if (!"pickaxe".equals(pdc.get(keyType, PersistentDataType.STRING))) continue;
            int lvl = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
            if (lvl > max) max = lvl;
        }
        return max;
    }

    // ==================================================== Silnik: hooki ====

    @Override
    protected Material materialOverride(PersistentDataContainer pdc) {
        return typeOf(pdc).material();
    }

    /** Każdy typ ma WŁASNĄ nazwę ("Kilof Wydajnościowy", nie samo "Kilof") - inaczej wszystkie 4 typy wyglądają identycznie. */
    @Override
    protected String displayNameFor(PersistentDataContainer pdc) {
        return "Kilof " + typeOf(pdc).displayName();
    }

    /** Nazwa każdego kilofa w kolorze WŁASNEGO typu (patrz PickaxeType#color) zamiast wspólnego AQUA dla wszystkich narzędzi. */
    @Override
    protected NamedTextColor nameColorFor(PersistentDataContainer pdc) {
        return typeOf(pdc).color();
    }

    /** Niflheim - jedyny kilof jawnie oznaczony jako Legendarny (patrz Rarity), na samej górze opisu. */
    @Override
    protected List<Component> extraLoreLinesFor(PersistentDataContainer pdc) {
        if (typeOf(pdc) != PickaxeType.NIFLHEIM) return List.of();
        return List.of(
                Component.text("✦ " + Rarity.LEGENDARY.label.toUpperCase() + " ✦", Rarity.LEGENDARY.color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                Component.empty()
        );
    }

    /** Typy na starym silniku zostają na pułapie 100 - patrz ToolSkillManager#maxLevel/maNowySilnik. */
    @Override
    protected int maxLevel(PersistentDataContainer pdc) {
        return maNowySilnik(typeOf(pdc)) ? 30 : MAX_LEVEL;
    }

    /** Nowy silnik nie losuje ofert kart wcale - ma zamiast tego 3 stałe ulepszenia, patrz onLeveledUp/milestonesFor. */
    @Override
    protected boolean usesCardOffers(PersistentDataContainer pdc) {
        return !maNowySilnik(typeOf(pdc));
    }

    /**
     * Domyślna formuła (level-1)/LEVELS_PER_TIER zakłada pułap 100 - przy 30-poziomowych
     * typach nowego silnika nigdy nie doszłaby do MAX_TIER, więc Bonus z Bruku (uniwersalny,
     * patrz komentarz klasowy) zawsze losowałby z najsłabszej puli. Zamiast tego skalujemy
     * proporcjonalnie do WŁASNEGO pułapu tego przedmiotu - pełny tier (netheryt) na
     * ostatnim poziomie, tak jak inne typy osiągają go na poziomie 100.
     */
    @Override
    protected int tierForLevel(int level, PersistentDataContainer pdc) {
        if (!maNowySilnik(typeOf(pdc))) return super.tierForLevel(level, pdc);
        int cap = maxLevel(pdc);
        int tier = (level - 1) * MAX_TIER / Math.max(1, cap - 1);
        return Math.min(tier, MAX_TIER);
    }

    // ============================================ Kilof Doświadczenia (nowy silnik) ====
    // Prototyp systemu "3 stałe ulepszenia zamiast puli kart" (patrz PickaxeType#DOSWIADCZENIA):
    // zamiast losowej oferty co kilka poziomów, ten typ odblokowuje automatycznie DOKŁADNIE
    // jedno ulepszenie na każdym z 3 kamieni milowych (10/20/30 = ostatni poziom). Dodatkowo
    // ma ciągłą auto-statę rosnącą co poziom (szansa na bonusowy orb XP, patrz
    // applyMiningPerks) - "różne typy, różne staty bazowe", tak jak reszta kilofów.

    private record Milestone(int poziom, String nazwa, Material icon, String opis) {}

    private static final List<Milestone> DOS_MILESTONES = List.of(
            new Milestone(10, "Głęboka Koncentracja", Material.NETHERITE_INGOT,
                    "Kilof staje się niezniszczalny - nigdy nie traci wytrzymałości."),
            new Milestone(20, "Aura Doświadczenia", Material.EXPERIENCE_BOTTLE,
                    "+15 punktów procentowych do szansy na bonusowy orb XP przy kopaniu."),
            new Milestone(30, "Mistrzostwo Górnika", Material.NETHER_STAR,
                    "Stała Szybkość Kopania II, niezależnie od innych bonusów.")
    );

    private static final List<Milestone> NIFL_MILESTONES = List.of(
            new Milestone(10, "Podwójny Urobek", Material.SNOWBALL,
                    "Niewielka szansa na zdublowanie dropu wykopanego bloku (x2)."),
            new Milestone(20, "Mroźny Pośpiech", Material.BLUE_ICE,
                    "Stała Szybkość Kopania II przy trzymaniu kilofa."),
            new Milestone(30, "Wieczna Zima", Material.PACKED_ICE,
                    "Kopanie zamienia do 5 połączonych sąsiednich bloków (także w generatorach) w lód - pęka dopiero gdy go wykopiesz, a wtedy rozbija resztę grupy naraz.")
    );

    private List<Milestone> milestonesFor(PickaxeType type) {
        return type == PickaxeType.NIFLHEIM ? NIFL_MILESTONES : DOS_MILESTONES;
    }

    private Milestone milestoneAt(PickaxeType type, int poziom) {
        for (Milestone m : milestonesFor(type)) if (m.poziom() == poziom) return m;
        return null;
    }

    private boolean hasMilestone(PersistentDataContainer pdc, int poziom) {
        return csvToSet(pdc.getOrDefault(pkMilestones, PersistentDataType.STRING, "")).contains(String.valueOf(poziom));
    }

    /** Patrz ToolsService#maWiecznaZime (mainplugins-core) - wołane cross-module przez GeneratorBrukuManager (mainplugins-quests, patrz CoreAPI). */
    public boolean maWiecznaZime(ItemStack tool) {
        if (tool == null || tool.getItemMeta() == null) return false;
        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
        if (!"pickaxe".equals(pdc.get(keyType, PersistentDataType.STRING))) return false;
        return typeOf(pdc) == PickaxeType.NIFLHEIM && hasMilestone(pdc, 30);
    }

    /** Auto-odblokowanie ulepszenia z milestonesFor(type), jeśli newLevel jest jednym z kamieni milowych - no-op dla pozostałych typów/poziomów. */
    @Override
    protected void onLeveledUp(Player player, ItemStack item, int newLevel) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        PickaxeType type = typeOf(pdc);
        if (!maNowySilnik(type)) return;

        Milestone milestone = milestoneAt(type, newLevel);
        if (milestone == null) return;

        Set<String> unlocked = csvToSet(pdc.getOrDefault(pkMilestones, PersistentDataType.STRING, ""));
        if (!unlocked.add(String.valueOf(newLevel))) return;
        pdc.set(pkMilestones, PersistentDataType.STRING, String.join(",", unlocked));
        item.setItemMeta(meta);

        player.showTitle(Title.title(
                Component.text("Nowe Ulepszenie!", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text(milestone.nazwa(), NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("★ Odblokowano: " + milestone.nazwa(), NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    // ==================================================== Custom GUI (hub) ====
    // Proof-of-concept custom teksturowanego huba - patrz assets/mainplugins/font/gui.json
    // w resourcepacku (kod czcionki "mainplugins:gui"): znak dystansowy (cofa tytuł o 8px
    // w lewo, żeby wyrównać się z lewą krawędzią GUI) + jeden znak-obrazek (CAŁE okno,
    // 176x166px, ascent=7/height=166 - pokrywa naszą sekcję (3 rzędy) ORAZ ekwipunek
    // gracza/hotbar pod spodem - patrz komentarz w PLACEHOLDER o ograniczeniu: realne itemy
    // gracza i tak wyrenderują się NAD tym obrazkiem, jeśli coś tam ma). Placeholder ma
    // siatkę (cyjan=nasze sloty, pomarańcz=ekw. gracza, limonka=hotbar) + znaczniki rogów
    // pod kalibrację wyrównania.

    private static final Key GUI_FONT = Key.key("mainplugins", "gui");
    private static final String GUI_SPACER_CHAR = "";
    private static final String GUI_HUB_BG_CHAR = "";

    @Override
    protected boolean hubUsesCustomBackground(PersistentDataContainer pdc) {
        return true;
    }

    @Override
    protected Component hubTitleFor(PersistentDataContainer pdc, String nazwa) {
        return Component.text(GUI_SPACER_CHAR + GUI_HUB_BG_CHAR).font(GUI_FONT);
    }

    /** Nigdy realnie użyte (materialOverride zawsze wygrywa dla kilofa) - musi istnieć, bo abstrakcyjne w bazie. */
    @Override
    protected Material materialForTier(int tier) {
        return PickaxeType.WYDAJNOSCIOWY.material();
    }

    @Override
    protected int offerCadence(PersistentDataContainer pdc) {
        return 5;
    }

    @Override
    protected List<SkillBranch> branchesFor(PersistentDataContainer pdc) {
        return List.of(typeOf(pdc).branch());
    }

    @Override
    protected List<RarePerk> rarePerksFor(PersistentDataContainer pdc) {
        return List.of();
    }

    @Override
    protected void initializeToolSpecific(PersistentDataContainer pdc) {
        pdc.set(pkBrukOn, PersistentDataType.BYTE, (byte) 1);
        pdc.set(pkGems, PersistentDataType.STRING, "");
        if (!pdc.has(pkType, PersistentDataType.STRING)) {
            pdc.set(pkType, PersistentDataType.STRING, PickaxeType.WYDAJNOSCIOWY.name());
        }
    }

    // ============================================================ Gemy ====

    private List<GemType> gemsOf(PersistentDataContainer pdc) {
        String csv = pdc.getOrDefault(pkGems, PersistentDataType.STRING, "");
        if (csv.isEmpty()) return List.of();
        List<GemType> list = new ArrayList<>();
        for (String s : csv.split(",")) {
            try {
                list.add(GemType.valueOf(s));
            } catch (IllegalArgumentException ignored) {
                // uszkodzony wpis - pomijamy
            }
        }
        return list;
    }

    private int gemCount(PersistentDataContainer pdc, GemType gem) {
        int count = 0;
        for (GemType g : gemsOf(pdc)) if (g == gem) count++;
        return count;
    }

    /** Mnożnik z Gemu Mocy (1.0 = brak, 1.10 = jeden gem, itd.) - stosowany do głównej auto-staty typu. */
    private double mocyMnoznik(PersistentDataContainer pdc) {
        return 1.0 + gemCount(pdc, GemType.MOCY) * GemType.MOCY.wartosc();
    }

    private GemType gemFromItem(ItemStack item) {
        if (item == null || item.getItemMeta() == null) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(pkGemId, PersistentDataType.STRING);
        if (id == null) return null;
        try {
            return GemType.valueOf(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Osadzanie gemu w kowadle - wzorowane 1:1 na FishingManager#onPrepareAnvil
     * (mainplugins-fishing): lewy slot = nasz przedmiot, prawy slot = "surowiec"
     * rozpoznawany po własnym tagu PDC, wynik = zmodyfikowany klon lewego przedmiotu.
     * Konsumpcja obu slotów jest w pełni wanilijska (zero dodatkowego kodu).
     */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack left = event.getInventory().getItem(0);
        ItemStack right = event.getInventory().getItem(1);
        if (left == null || right == null || left.getItemMeta() == null) return;

        PersistentDataContainer leftPdc = left.getItemMeta().getPersistentDataContainer();
        if (!"pickaxe".equals(leftPdc.get(keyType, PersistentDataType.STRING))) return;

        List<GemType> gems = gemsOf(leftPdc);
        if (gems.size() >= MAX_GEMY) return;

        GemType gem = gemFromItem(right);
        if (gem == null) return;

        ItemStack wynik = left.clone();
        ItemMeta wynikMeta = wynik.getItemMeta();
        PersistentDataContainer wynikPdc = wynikMeta.getPersistentDataContainer();
        List<GemType> nowe = new ArrayList<>(gems);
        nowe.add(gem);
        wynikPdc.set(pkGems, PersistentDataType.STRING, nowe.stream().map(Enum::name).collect(Collectors.joining(",")));
        wynik.setItemMeta(wynikMeta);
        refreshDisplay(wynik);

        event.getInventory().setRepairCost(0);
        event.setResult(wynik);
    }

    // ============================================================ Kopanie ====

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(item, player)) return;

        ensureInitialized(item);
        applyMiningPerks(player, event.getBlock(), item);
        addExp(player, item);

        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        double bonusExpChance = gemCount(pdc, GemType.DOSWIADCZENIA) * GemType.DOSWIADCZENIA.wartosc();
        if (bonusExpChance > 0 && ThreadLocalRandom.current().nextDouble() < bonusExpChance) {
            addExp(player, item);
        }
    }

    private void applyMiningPerks(Player player, Block block, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        PickaxeType type = typeOf(pdc);
        Material mat = block.getType();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double scale = procScale(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int tier = tierOf(pdc);
        double mocy = mocyMnoznik(pdc);

        // Bonus z Bruku - UNIWERSALNY (jedyne źródło "rudy" na tym Skyblocku, patrz komentarz klasowy).
        SurowiecDrop bonusSurowiec = rollBonusZBruku(player, block, mat, pdc);
        boolean cenny = bonusSurowiec != null && bonusSurowiec.cenny();

        if (type == PickaxeType.SZCZESCIA) {
            UUID id = player.getUniqueId();
            if (cenny) luckStreak.put(id, 0); else luckStreak.merge(id, 1, Integer::sum);

            if (cenny && cardCountOf(pdc, "SZCZ_KLUCZ") > 0
                    && rnd.nextDouble() < scale * stackedChance(cardCountOf(pdc, "SZCZ_KLUCZ"), 0.08)) {
                grantKluczGornika(player);
            }
            if (cenny && cardCountOf(pdc, "SZCZ_PODWOJNY") > 0 && rnd.nextDouble() < scale * 0.15) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                        new ItemStack(bonusSurowiec.item(), bonusSurowiec.ilosc()));
            }
        }

        if (type == PickaxeType.PIENIEZNY) {
            double autoChance = Math.min(level * 0.001, 0.15);
            double cardChance = stackedChance(cardCountOf(pdc, "PIE_SZANSA"), 0.03);
            double chance = scale * combineChances(autoChance, cardChance);
            if (rnd.nextDouble() < chance) {
                double amount = (2 + tier * 2) * (1 + cardCountOf(pdc, "PIE_MNOZNIK") * 0.15) * mocy;
                payBonus(player, amount);
            }
            if (rnd.nextDouble() < scale * stackedChance(cardCountOf(pdc, "PIE_JACKPOT"), 0.005)) {
                payBonus(player, (50 + tier * 20) * mocy);
            }
            if (cenny && cardCountOf(pdc, "PIE_BRUK_BONUS") > 0) {
                payBonus(player, (5 + tier * 3) * cardCountOf(pdc, "PIE_BRUK_BONUS") * 0.5 * mocy);
            }
        }

        if (jestWydajnosciowy(type)) {
            double dupChance = Math.min(level * 0.0005, 0.05) * mocy;
            if (rnd.nextDouble() < scale * dupChance) {
                dropCopy(block, item);
            }
            if (cardCountOf(pdc, "WYD_ZASIEG") > 0
                    && rnd.nextDouble() < scale * stackedChance(cardCountOf(pdc, "WYD_ZASIEG"), 0.08)) {
                breakRandomNeighbors(block, item, mat, 1);
            }
            if (cardCountOf(pdc, "WYD_MAGNES") > 0) {
                pullNearbyDrops(player, block.getLocation());
            }
            handleMomentum(player, pdc);
        }

        if (type == PickaxeType.OBSZAROWY) {
            int autoRadius = Math.min(1 + level / 20, 3);
            int extra = cardCountOf(pdc, "OBS_TUNEL") + (cardCountOf(pdc, "OBS_WIEKSZY_PROMIEN") > 0 ? 2 : 0);
            int total = Math.min(autoRadius + extra, 6);
            boolean autoZbiory = cardCountOf(pdc, "OBS_AUTOPOMIN") > 0;
            boolean echo = cardCountOf(pdc, "OBS_ECHO") > 0;
            breakAreaAroundBlock(player, block, item, total, autoZbiory, echo, pdc);
        }

        if (type == PickaxeType.DOSWIADCZENIA) {
            double chance = dosXpChancePercent(pdc) / 100.0;
            if (rnd.nextDouble() < chance) {
                spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 1 + tier);
            }
        }

        if (type == PickaxeType.NIFLHEIM) {
            if (rnd.nextDouble() < niflFreezeChancePercent(pdc) / 100.0) {
                zamrozBlok(player, block, item);
            }
            if (hasMilestone(pdc, 10) && rnd.nextDouble() < 0.10) {
                dropCopy(block, item);
            }
            if (hasMilestone(pdc, 30)) {
                int count = 1 + rnd.nextInt(5);
                zamrozSasiadow(player, block, item, count);
            }
        }
    }

    /** Auto-stata bazowa Kilofa Doświadczenia (rośnie z poziomem, wzmacniana przez Gem Mocy) + bonus z ulepszenia na 20 lvl. */
    private double dosXpChancePercent(PersistentDataContainer pdc) {
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        double baza = Math.min(level * 1.0, 30.0) * mocyMnoznik(pdc);
        return baza + (hasMilestone(pdc, 20) ? 15.0 : 0.0);
    }

    /** Auto-stata bazowa Kilofa Niflheim: szansa, że kopany blok "zamarznie" - patrz zamrozBlok. Rośnie z poziomem, wzmacniana przez Gem Mocy. */
    private double niflFreezeChancePercent(PersistentDataContainer pdc) {
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        return Math.min(level * 0.5, 15.0) * mocyMnoznik(pdc);
    }

    /**
     * "Zamrożenie" kopanego bloku - wizualnie cząsteczki śnieżynek + dźwięk pękającego
     * lodu, mechanicznie: bonusowa kopia dropu (dropCopy, jak dupChance Wydajnościowego)
     * + śnieg na pierwszym wolnym sąsiednim polu (czysto kosmetyczny, tylko na AIR -
     * nigdy nie nadpisuje istniejącego bloku).
     */
    private void zamrozBlok(Player player, Block block, ItemStack item) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawnParticle(Particle.SNOWFLAKE, center, 16, 0.35, 0.35, 0.35, 0.02);
        player.playSound(center, Sound.BLOCK_GLASS_BREAK, 1f, 1.3f);
        dropCopy(block, item);

        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = block.getRelative(face);
            if (neighbor.getType() == Material.AIR) {
                neighbor.setType(Material.SNOW);
                break;
            }
        }
    }

    /** Milestone 30 Niflheim: dodatkowe punkty procentowe do szansy na Bonus z Bruku (ponad normalną totalBrukSzansa) dla każdego zamrożonego sąsiada. */
    private static final double NIFL_FREEZE_BRUK_BONUS_PP = 15.0;

    /**
     * Wieczna Zima (milestone 30) - do maxCount sąsiadów TEGO SAMEGO typu co origin zamienia
     * się w PRAWDZIWY, trwały Material.PACKED_ICE (realny stan świata, nie pakiet/sendBlockChange).
     * Blok NIE pęka sam - leży jako lód, dopóki gracz nie wykopie KTÓREGOKOLWIEK bloku z tej
     * samej grupy (patrz niflFreezeGroups/onNiflFrozenBreak): wtedy cała reszta grupy (do 4
     * pozostałych) pęka razem z nim, z dropem zebranym na oryginalnym Materiale w momencie
     * zamrożenia (tym samym narzędziem) PLUS własny, podbity rzut na Bonus z Bruku dla każdego
     * członka (patrz rollBonusZBruku, bonusPP) - dotąd sąsiedzi przy okazji AoE w ogóle nie
     * brali w nim udziału. Generator Bruku (GeneratorBrukuManager, inny moduł) ma WŁASNĄ,
     * analogiczną wersję tego mechanizmu dla swoich bloków (patrz ToolsService#maWiecznaZime) -
     * ta metoda dotyczy wyłącznie zwykłych bloków świata, bo generator zmienia Material tuż
     * przed/po wykopaniu (MOSSY_COBBLESTONE w stanie gotowym) i nigdy nie pasuje do originType.
     */
    private void zamrozSasiadow(Player player, Block origin, ItemStack tool, int maxCount) {
        Material originType = origin.getType();
        List<Block> candidates = new ArrayList<>();
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = origin.getRelative(face);
            if (neighbor.getType() == originType && !niflFreezeGroups.containsKey(neighbor.getLocation())) {
                candidates.add(neighbor);
            }
        }
        Collections.shuffle(candidates);

        List<Block> wybrane = candidates.subList(0, Math.min(maxCount, candidates.size()));
        if (wybrane.isEmpty()) return;

        Set<Location> grupa = new HashSet<>();
        for (Block b : wybrane) grupa.add(b.getLocation());

        for (Block b : wybrane) {
            Location loc = b.getLocation();
            niflFreezeDrops.put(loc, b.getDrops(tool));
            niflFreezeOriginalType.put(loc, originType);
            niflFreezeGroups.put(loc, grupa);

            Location center = loc.clone().add(0.5, 0.5, 0.5);
            b.setType(Material.PACKED_ICE, false);
            b.getWorld().spawnParticle(Particle.SNOWFLAKE, center, 16, 0.35, 0.35, 0.35, 0.02);
            player.playSound(center, Sound.BLOCK_GLASS_BREAK, 1f, 1.3f);
        }
    }

    /**
     * Łańcuchowe wykopanie grupy Wiecznej Zimy - gracz wykopał JEDEN z zamrożonych bloków
     * (patrz zamrozSasiadow), więc cała reszta grupy pęka razem z nim: własny drop (zebrany
     * PRZY zamrożeniu, na oryginalnym Materiale) zamiast normalnego dropu lodu (który bez Silk
     * Toucha i tak byłby pusty) + rzut na Bonus z Bruku dla każdego członka. Priorytet LOWEST,
     * żeby ubiec ewentualne inne nasłuchy na to samo zdarzenie (wzorem GeneratorBrukuManager).
     *
     * Kliknięty blok (event.getBlock()) NIE jest tu ręcznie usuwany na AIR - zostaje
     * Material.PACKED_ICE aż do naturalnego usunięcia PO evencie (nie anulujemy go, tylko
     * dropItems=false), inaczej ten sam onBlockBreak niżej (priorytet NORMAL) widziałby już
     * pusty blok i np. zamrozSasiadow próbowałoby "zamrozić" sąsiadujące AIR.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onNiflFrozenBreak(BlockBreakEvent event) {
        Location klikniety = event.getBlock().getLocation();
        Set<Location> grupa = niflFreezeGroups.get(klikniety);
        if (grupa == null) return;

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.getType().name().endsWith("_PICKAXE")) {
            // Lód (bez Silk Toucha) i tak nic by nie dropił - bez tego gracz mógłby "wykopać"
            // gołą ręką zamrożony blok o wysokim wymogu narzędzia (np. rudę diamentu) za darmo.
            event.setCancelled(true);
            return;
        }
        PersistentDataContainer toolPdc = tool.getItemMeta() != null ? tool.getItemMeta().getPersistentDataContainer() : null;

        event.setDropItems(false);
        event.setExpToDrop(0);

        for (Location member : new ArrayList<>(grupa)) {
            niflFreezeGroups.remove(member);
            Collection<ItemStack> drops = niflFreezeDrops.remove(member);
            Material originalType = niflFreezeOriginalType.remove(member);
            Block b = member.getBlock();
            Location center = member.clone().add(0.5, 0.5, 0.5);

            b.getWorld().spawnParticle(Particle.SNOWFLAKE, center, 16, 0.35, 0.35, 0.35, 0.02);
            player.playSound(center, Sound.BLOCK_GLASS_BREAK, 1f, 1.3f);
            if (!member.equals(klikniety)) {
                b.setType(Material.AIR, false);
            }
            if (drops != null) {
                for (ItemStack drop : drops) b.getWorld().dropItemNaturally(center, drop);
            }
            if (toolPdc != null && originalType != null) {
                rollBonusZBruku(player, b, originalType, toolPdc, NIFL_FREEZE_BRUK_BONUS_PP);
            }
        }
    }

    /** Ambientowe cząsteczki śnieżynek wokół każdego gracza trzymającego Kilof Niflheim - patrz konstruktor (runTaskTimer). */
    private void tickNiflheimAmbient() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!isOwnedTool(held, player)) continue;
            PersistentDataContainer pdc = held.getItemMeta().getPersistentDataContainer();
            if (typeOf(pdc) != PickaxeType.NIFLHEIM) continue;
            player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0, 1, 0), 3, 0.5, 0.5, 0.5, 0.01);
        }
    }

    /** Wydajność Niflheim: startuje między Wydajnością II a III (poziom < 10), rośnie do IV na ostatnich poziomach. */
    private int niflEfficiencyLevel(int level) {
        if (level >= 25) return 4;
        if (level >= 10) return 3;
        return 2;
    }

    /** Szczęście/Fortuna Niflheim: startuje od Fortuny I, rośnie do III na maksymalnym poziomie. */
    private int niflFortuneLevel(int level) {
        if (level >= 30) return 3;
        if (level >= 15) return 2;
        return 1;
    }

    /**
     * Realna szansa (%) na trigger Bonusu z Bruku - baza z bruk-szanse.yml + auto-bonus
     * Kilofa Szczęścia (rośnie z poziomem) + karta SZCZ_SZCZESCIE + Passa (SZCZ_PASSA,
     * liczona TYLKO gdy player != null - patrz brukInfoIcon, gdzie nie mamy gracza pod
     * ręką) + Gem Szczęścia (uniwersalny, dowolny typ).
     */
    private double totalBrukSzansa(Player player, PersistentDataContainer pdc) {
        PickaxeType type = typeOf(pdc);
        int tier = tierOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        double autoLuckUnits = type == PickaxeType.SZCZESCIA ? level / 10.0 : 0;
        double passaBonusPP = 0;
        if (player != null && type == PickaxeType.SZCZESCIA && cardCountOf(pdc, "SZCZ_PASSA") > 0) {
            passaBonusPP = Math.min(luckStreak.getOrDefault(player.getUniqueId(), 0) * 0.02, 4.0);
        }
        double gemLuckPP = gemCount(pdc, GemType.SZCZESCIA) * GemType.SZCZESCIA.wartosc();
        int szczescieUnits = (int) Math.round(cardCountOf(pdc, "SZCZ_SZCZESCIE") + autoLuckUnits);
        return brukSurowceManager.calkowitaSzansa(tier, szczescieUnits) + gemLuckPP + passaBonusPP;
    }

    /**
     * Bonus z Bruku - patrz komentarz klasowy BrukSurowceManager. Liczy trigger + losuje
     * konkretny surowiec (respektując przełącznik pk_bruk_on i ręcznie wyłączone surowce),
     * OD RAZU wypłaca go graczowi (drop + dźwięk/actionbar) i zwraca wynik.
     */
    private SurowiecDrop rollBonusZBruku(Player player, Block block, Material mat, PersistentDataContainer pdc) {
        return rollBonusZBruku(player, block, mat, pdc, 0.0);
    }

    /** Wariant z dodatkowym bonusem procentowym ponad normalną totalBrukSzansa - patrz zamrozSasiadow (NIFL_FREEZE_BRUK_BONUS_PP). */
    private SurowiecDrop rollBonusZBruku(Player player, Block block, Material mat, PersistentDataContainer pdc, double bonusPP) {
        if (mat != Material.COBBLESTONE) return null;
        boolean enabled = pdc.getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
        if (!enabled) return null;

        double szansa = totalBrukSzansa(player, pdc) + bonusPP;
        if (szansa <= 0 || ThreadLocalRandom.current().nextDouble(100.0) >= szansa) return null;

        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tierOf(pdc));
        if (pula.isEmpty()) return null;
        SurowiecDrop wylosowany = brukSurowceManager.losujWazony(pula, 0, brukSurowceManager.wylaczoneSurowce(pdc));
        if (wylosowany == null) return null;

        Location loc = block.getLocation();
        block.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), new ItemStack(wylosowany.item(), wylosowany.ilosc()));
        player.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
        player.sendActionBar(Component.text("W bruku znalazłeś " + wylosowany.nazwa() + "!", NamedTextColor.AQUA));
        return wylosowany;
    }

    private void handleMomentum(Player player, PersistentDataContainer pdc) {
        int streakCards = cardCountOf(pdc, "WYD_STREAK");
        if (streakCards <= 0) return;

        UUID id = player.getUniqueId();
        long windowMs = streakCards >= 3 ? 5000 : 3000;
        long now = System.currentTimeMillis();
        long last = streakLastBreak.getOrDefault(id, 0L);
        int combo = (now - last <= windowMs) ? streakCount.getOrDefault(id, 0) + 1 : 1;
        streakLastBreak.put(id, now);

        int threshold = streakCards >= 2 ? 2 : 3;
        if (combo >= threshold) {
            combo = 0;
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 80, streakCards >= 3 ? 1 : 0, true, false, true));
        }
        streakCount.put(id, combo);
    }

    /** Skruszenie do maxCount sąsiadów TEGO SAMEGO Materiału co origin - AoE Kilofa Obszarowego. */
    private void breakAreaAroundBlock(Player player, Block origin, ItemStack tool, int maxCount, boolean autoZbiory, boolean echo, PersistentDataContainer pdc) {
        Material originType = origin.getType();
        List<Block> candidates = new ArrayList<>();
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = origin.getRelative(face);
            if (neighbor.getType() == originType) candidates.add(neighbor);
        }
        Collections.shuffle(candidates);

        int broken = 0;
        for (Block b : candidates) {
            if (broken >= maxCount) break;
            Material neighborType = b.getType();
            if (autoZbiory) {
                for (ItemStack drop : b.getDrops(tool)) {
                    var leftover = player.getInventory().addItem(drop);
                    leftover.values().forEach(i -> b.getWorld().dropItemNaturally(b.getLocation(), i));
                }
                b.setType(Material.AIR);
            } else {
                b.breakNaturally(tool);
            }
            if (echo && neighborType == Material.COBBLESTONE) {
                rollBonusZBruku(player, b, neighborType, pdc);
            }
            broken++;
        }
    }

    /** Skruszenie do maxCount LOSOWYCH sąsiadów zadanego Materiału - Rozszerzony Zasięg (WYD_ZASIEG). */
    private void breakRandomNeighbors(Block origin, ItemStack tool, Material requiredType, int maxCount) {
        List<Block> candidates = new ArrayList<>();
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = origin.getRelative(face);
            if (neighbor.getType() == requiredType) candidates.add(neighbor);
        }
        Collections.shuffle(candidates);
        int broken = 0;
        for (Block b : candidates) {
            if (broken >= maxCount) break;
            b.breakNaturally(tool);
            broken++;
        }
    }

    /**
     * Klucz Górnika - opcjonalny bonus, cichy no-op jeśli mainplugins-crates nie jest
     * wgrany/włączony (patrz CoreAPI#getCrateService).
     */
    private void grantKluczGornika(Player player) {
        CrateService crateService = CoreAPI.getCrateService();
        if (crateService == null) return;

        ItemStack klucz = crateService.stworzKlucz();
        var nieZmieszczone = player.getInventory().addItem(klucz);
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        player.sendMessage(Component.text("★★★ Podczas kopania znalazłeś Klucz do Skrzynki! ★★★", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    // ===================================================== Pasywna aura ====

    @Override
    protected PotionEffectType auraEffectType() {
        return PotionEffectType.HASTE;
    }

    /**
     * Wydajnościowy (i Testowy) ma stałą, rosnącą Aurę Pośpiechu. Doświadczenia dostaje
     * stałą Szybkość Kopania II dopiero jako CAPSTONE (ulepszenie na 30 lvl, patrz
     * DOS_MILESTONES) - przed tym poziomem brak jakiejkolwiek aury.
     */
    @Override
    protected Integer auraAmplifierFor(PersistentDataContainer pdc) {
        PickaxeType type = typeOf(pdc);
        if (jestWydajnosciowy(type)) {
            int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
            return (int) Math.min(4, Math.round((level / 20.0) * mocyMnoznik(pdc)));
        }
        if (type == PickaxeType.DOSWIADCZENIA && hasMilestone(pdc, 30)) {
            return 1;
        }
        if (type == PickaxeType.NIFLHEIM && hasMilestone(pdc, 20)) {
            return 1;
        }
        return null;
    }

    // ==================================================== Statystyki/wygląd ====

    @Override
    protected void syncToolSpecificStats(ItemMeta meta, PersistentDataContainer pdc) {
        PickaxeType type = typeOf(pdc);
        meta.setCustomModelData(type.customModelData());
        meta.removeEnchant(Enchantment.FORTUNE);
        meta.removeEnchant(Enchantment.EFFICIENCY);
        meta.removeAttributeModifier(Attribute.BLOCK_BREAK_SPEED);

        if (jestWydajnosciowy(type)) {
            if (cardCountOf(pdc, "WYD_AUTOFORTUNE") > 0) meta.addEnchant(Enchantment.FORTUNE, 1, true);
            int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
            meta.addAttributeModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(
                    pkSpeedPassiveModifierKey, level * 0.003 * mocyMnoznik(pdc), AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }

        if (type == PickaxeType.DOSWIADCZENIA) {
            meta.setUnbreakable(hasMilestone(pdc, 10));
        }

        if (type == PickaxeType.NIFLHEIM) {
            int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
            meta.addEnchant(Enchantment.EFFICIENCY, niflEfficiencyLevel(level), true);
            meta.addEnchant(Enchantment.FORTUNE, niflFortuneLevel(level), true);
        }
    }

    @Override
    protected List<Component> statsLore(PersistentDataContainer pdc) {
        PickaxeType type = typeOf(pdc);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Typ: " + type.displayName(), type.color(), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.addAll(autoStatLines(pdc, type));
        return lore;
    }

    private List<Component> autoStatLines(PersistentDataContainer pdc, PickaxeType type) {
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        List<Component> lines = new ArrayList<>();
        switch (type) {
            case WYDAJNOSCIOWY, TESTOWY -> {
                Integer amp = auraAmplifierFor(pdc);
                lines.add(Component.text("Aura Pośpiechu: " + rzymskie((amp == null ? 0 : amp) + 1), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                lines.add(Component.text("Szansa na dodatkowy drop: " + formatPercent(Math.min(level * 0.05, 5)) + "%", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            case PIENIEZNY -> lines.add(Component.text("Szansa na bonus $: " + formatPercent(Math.min(level * 0.1, 15)) + "%", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            case SZCZESCIA -> lines.add(Component.text("Bonus do szansy z Bruku: +" + formatPercent(level / 10.0) + " pp", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            case OBSZAROWY -> lines.add(Component.text("Promień obszarowy: " + Math.min(1 + level / 20, 3), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            case DOSWIADCZENIA -> lines.add(Component.text("Szansa na bonusowy orb XP: " + formatPercent(dosXpChancePercent(pdc)) + "%", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            case NIFLHEIM -> {
                lines.add(Component.text("Wydajność " + rzymskie(niflEfficiencyLevel(level)) + " / Fortuna " + rzymskie(niflFortuneLevel(level)), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                lines.add(Component.text("Szansa na zamrożenie bloku: " + formatPercent(niflFreezeChancePercent(pdc)) + "%", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
        }
        lines.add(Component.empty());
        List<GemType> gems = gemsOf(pdc);
        lines.add(Component.text("Gemy (" + gems.size() + "/" + MAX_GEMY + "):", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        if (gems.isEmpty()) {
            lines.add(Component.text("Brak - połącz kilof z gemem w kowadle.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            for (GemType g : gems) lines.add(Component.text("• " + g.displayName(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        return lines;
    }

    @Override
    protected List<ItemStack> statsBlocks(PersistentDataContainer pdc) {
        PickaxeType type = typeOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        List<ItemStack> blocks = new ArrayList<>();
        switch (type) {
            case WYDAJNOSCIOWY, TESTOWY -> {
                Integer amp = auraAmplifierFor(pdc);
                int ampVal = (amp == null ? 0 : amp) + 1;
                blocks.add(statBlock(Material.AMETHYST_SHARD, "Aura Pośpiechu " + rzymskie(ampVal), "Rośnie z poziomem.", ampVal));
                blocks.add(statBlock(Material.FEATHER, "Dodatkowy Drop +" + formatPercent(Math.min(level * 0.05, 5)) + "%", "Rośnie z poziomem.", 1));
            }
            case PIENIEZNY -> blocks.add(statBlock(Material.GOLD_NUGGET, "Bonus $ " + formatPercent(Math.min(level * 0.1, 15)) + "%", "Rośnie z poziomem.", 1));
            case SZCZESCIA -> blocks.add(statBlock(Material.RABBIT_FOOT, "Bonus Bruku +" + formatPercent(level / 10.0) + " pp", "Rośnie z poziomem.", 1));
            case OBSZAROWY -> {
                int radius = Math.min(1 + level / 20, 3);
                blocks.add(statBlock(Material.TNT, "Promień " + radius, "Rośnie z poziomem.", radius));
            }
            case DOSWIADCZENIA -> blocks.add(statBlock(Material.EXPERIENCE_BOTTLE,
                    "Bonusowy Orb XP " + formatPercent(dosXpChancePercent(pdc)) + "%", "Rośnie z poziomem.", 1));
            case NIFLHEIM -> {
                blocks.add(statBlock(Material.DIAMOND_PICKAXE, "Wydajność " + rzymskie(niflEfficiencyLevel(level)), "Rośnie z poziomem.", niflEfficiencyLevel(level)));
                blocks.add(statBlock(Material.EMERALD, "Fortuna " + rzymskie(niflFortuneLevel(level)), "Rośnie z poziomem.", niflFortuneLevel(level)));
                blocks.add(statBlock(Material.ICE, "Zamrożenie " + formatPercent(niflFreezeChancePercent(pdc)) + "%", "Rośnie z poziomem.", 1));
            }
        }
        // Zawsze DOKŁADNIE MAX_GEMY okienek (puste albo wypełnione) - nie tylko posiadane,
        // żeby gracz widział ile gniazd ma w ogóle do dyspozycji (patrz onPrepareAnvil).
        List<GemType> gems = gemsOf(pdc);
        for (int i = 0; i < MAX_GEMY; i++) {
            if (i < gems.size()) {
                GemType g = gems.get(i);
                blocks.add(statBlock(g.icon(), g.displayName(), g.opis(), 1));
            } else {
                ItemStack puste = GuiUtils.namedItem(Material.GRAY_STAINED_GLASS_PANE,
                        Component.text("Puste gniazdo", NamedTextColor.DARK_GRAY, TextDecoration.BOLD),
                        Component.text("Połącz ten kilof z gemem w kowadle,", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.text("aby go tu osadzić.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                blocks.add(puste);
            }
        }
        return blocks;
    }

    private ItemStack statBlock(Material material, String name, String opis, int amount) {
        ItemStack icon = GuiUtils.namedItem(material,
                Component.text(name, NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                Component.text(opis, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        icon.setAmount(Math.max(1, Math.min(64, amount)));
        return icon;
    }

    // ============================================== Dodatkowe ikonki huba ====

    @Override
    protected void populateExtraHubIcons(Inventory gui, PersistentDataContainer pdc) {
        boolean enabled = pdc.getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
        gui.setItem(slotBrukInfo, brukInfoIcon(pdc, enabled));
    }

    @Override
    protected boolean handleExtraHubClick(Player player, ItemStack tool, int slot) {
        if (slot == slotBrukInfo) {
            openBrukConfig(player, tool);
        }
        return false;
    }

    // ================================================ Nowy hub: silnik bez ofert ====
    // Wspólny dla wszystkich typów maNowySilnik (Doświadczenia, Niflheim) - zamiast
    // standardowego huba (oferty/Informacje/Zamknij) jeden skonsolidowany, czysto
    // informacyjny ekran: ikonka kilofa na górze-środku, 3 ulepszenia (patrz
    // milestonesFor) symetrycznie w środkowym rzędzie (1 slot odstępu między nimi),
    // osadzone gemy tak samo symetrycznie na dole. Nic tu nie jest klikalne -
    // odblokowania są automatyczne (onLeveledUp), gemy osadza się w kowadle jak
    // dotychczas - to tylko podgląd stanu.

    private static final int NOWY_HUB_SIZE = 45;
    private static final int NOWY_HUB_ICON_SLOT = 4;
    private static final int[] NOWY_HUB_UPGRADE_SLOTS = {20, 22, 24};
    private static final int[] NOWY_HUB_GEM_SLOTS = {38, 40, 42};

    /** Typy na nowym silniku dostają własny, uproszczony ekran zamiast standardowego huba - pozostałe typy bez zmian. */
    @Override
    protected void openHub(Player player, ItemStack tool) {
        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
        if (!maNowySilnik(typeOf(pdc))) {
            super.openHub(player, tool);
            return;
        }
        openNowySilnikHub(player, tool, pdc);
    }

    private void openNowySilnikHub(Player player, ItemStack tool, PersistentDataContainer pdc) {
        PickaxeType type = typeOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        String nazwa = "Kilof " + type.displayName();
        String podsumowanie = type == PickaxeType.NIFLHEIM
                ? "Szansa na zamrożenie bloku: " + formatPercent(niflFreezeChancePercent(pdc)) + "%"
                : "Szansa na bonusowy orb XP: " + formatPercent(dosXpChancePercent(pdc)) + "%";

        NowyHubHolder holder = new NowyHubHolder(this);
        Inventory gui = Bukkit.createInventory(holder, NOWY_HUB_SIZE,
                Component.text(nazwa + " [Poziom " + level + "]", type.color(), TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.BLACK_STAINED_GLASS_PANE);

        gui.setItem(NOWY_HUB_ICON_SLOT, GuiUtils.namedItem(type.material(),
                Component.text(nazwa + " [Poziom " + level + "]", type.color(), TextDecoration.BOLD),
                Component.text("Tier: " + tierName(tierOf(pdc)), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(podsumowanie, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));

        List<Milestone> milestones = milestonesFor(type);
        for (int i = 0; i < NOWY_HUB_UPGRADE_SLOTS.length; i++) {
            gui.setItem(NOWY_HUB_UPGRADE_SLOTS[i], milestoneIcon(pdc, milestones.get(i)));
        }

        List<GemType> gems = gemsOf(pdc);
        for (int i = 0; i < NOWY_HUB_GEM_SLOTS.length; i++) {
            if (i < gems.size()) {
                GemType g = gems.get(i);
                gui.setItem(NOWY_HUB_GEM_SLOTS[i], GuiUtils.namedItem(g.icon(),
                        Component.text(g.displayName(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                        Component.text(g.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            } else {
                gui.setItem(NOWY_HUB_GEM_SLOTS[i], GuiUtils.namedItem(Material.GRAY_STAINED_GLASS_PANE,
                        Component.text("Puste gniazdo", NamedTextColor.DARK_GRAY, TextDecoration.BOLD),
                        Component.text("Połącz ten kilof z gemem w kowadle,", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.text("aby go tu osadzić.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            }
        }

        player.openInventory(gui);
    }

    private ItemStack milestoneIcon(PersistentDataContainer pdc, Milestone milestone) {
        boolean unlocked = hasMilestone(pdc, milestone.poziom());
        ItemStack icon = GuiUtils.namedItem(unlocked ? milestone.icon() : Material.GRAY_DYE,
                Component.text(milestone.nazwa(), unlocked ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                Component.text(milestone.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text(unlocked ? "✔ Odblokowane" : "○ Odblokuje się na " + milestone.poziom() + " lvl",
                        unlocked ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        if (unlocked) {
            ItemMeta iconMeta = icon.getItemMeta();
            iconMeta.setEnchantmentGlintOverride(true);
            icon.setItemMeta(iconMeta);
        }
        return icon;
    }

    /** Ekran czysto informacyjny - jedyna obsługa kliknięcia to anulowanie (bez tego gracz mógłby wyciągnąć itemy z GUI). */
    @EventHandler
    public void onNowyHubClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof NowyHubHolder holder) || holder.owner != this) return;
        event.setCancelled(true);
    }

    private static final class NowyHubHolder implements InventoryHolder {
        private final PickaxeSkillManager owner;
        private Inventory inventory;
        private NowyHubHolder(PickaxeSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private ItemStack brukInfoIcon(PersistentDataContainer pdc, boolean enabled) {
        int tier = tierOf(pdc);
        double szansa = totalBrukSzansa(null, pdc);
        var wylaczone = brukSurowceManager.wylaczoneSurowce(pdc);
        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tier);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Status: " + (enabled ? "WŁĄCZONY" : "WYŁĄCZONY"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Kopiąc bruk (cobblestone) masz szansę", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("na dodatkowy surowiec w dropie:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(formatPercent(szansa) + "% łącznie", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Losowo jeden z (tier " + tierName(tier) + "):", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        for (SurowiecDrop drop : pula) {
            boolean dropEnabled = !wylaczone.contains(drop.id());
            double udzial = brukSurowceManager.udzialSurowca(tier, drop, 0, wylaczone);
            String linia = "• " + drop.nazwa() + " — " + (dropEnabled ? formatPercent(szansa * udzial) + "%" : "wyłączony");
            lore.add(Component.text(linia, dropEnabled ? (drop.cenny() ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.YELLOW) : NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Kliknij, aby skonfigurować (włącz/wyłącz cały bonus lub pojedyncze surowce)", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));

        return GuiUtils.namedItem(Material.COBBLESTONE,
                Component.text("Bonus z Bruku", NamedTextColor.GOLD, TextDecoration.BOLD),
                lore.toArray(Component[]::new));
    }

    // ============================================== Konfiguracja bonusu z bruku ====

    private static final int BRUK_CONFIG_SIZE = 36;
    private static final int BRUK_CONFIG_MASTER_SLOT = 4;
    private static final int BRUK_CONFIG_CONTENT_START = 9;
    private static final int BRUK_CONFIG_BACK_SLOT = 31;

    private void openBrukConfig(Player player, ItemStack tool) {
        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
        int tier = tierOf(pdc);
        boolean enabled = pdc.getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
        double szansa = totalBrukSzansa(player, pdc);
        var wylaczone = brukSurowceManager.wylaczoneSurowce(pdc);
        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tier);

        BrukConfigHolder holder = new BrukConfigHolder(this);
        Inventory gui = Bukkit.createInventory(holder, BRUK_CONFIG_SIZE, Component.text("Bonus z Bruku — Surowce", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.BLACK_STAINED_GLASS_PANE);

        gui.setItem(BRUK_CONFIG_MASTER_SLOT, brukMasterToggleIcon(enabled));
        for (int i = 0; i < pula.size() && BRUK_CONFIG_CONTENT_START + i < BRUK_CONFIG_BACK_SLOT; i++) {
            SurowiecDrop drop = pula.get(i);
            boolean dropEnabled = !wylaczone.contains(drop.id());
            double udzial = brukSurowceManager.udzialSurowca(tier, drop, 0, wylaczone);
            gui.setItem(BRUK_CONFIG_CONTENT_START + i, surowiecToggleIcon(drop, dropEnabled, szansa, udzial));
        }

        gui.setItem(BRUK_CONFIG_BACK_SLOT, GuiUtils.namedItem(Material.ARROW, Component.text("« Wróć", NamedTextColor.GOLD, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private ItemStack brukMasterToggleIcon(boolean enabled) {
        return GuiUtils.namedItem(Material.COBBLESTONE,
                Component.text("Bonus z Bruku: " + (enabled ? "WŁĄCZONY" : "WYŁĄCZONY"),
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Wyłącza/włącza CAŁY bonus naraz.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Pojedyncze surowce wyłączysz osobno poniżej.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Kliknij, aby " + (enabled ? "wyłączyć" : "włączyć"), NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
    }

    private ItemStack surowiecToggleIcon(SurowiecDrop drop, boolean enabled, double szansaCalkowita, double udzial) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Status: " + (enabled ? "WŁĄCZONY" : "WYŁĄCZONY"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Realna szansa: " + (enabled ? formatPercent(szansaCalkowita * udzial) + "%" : "0% (wyłączony)"),
                NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Kliknij, aby " + (enabled ? "wyłączyć" : "włączyć"), NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));

        ItemStack icon = GuiUtils.namedItem(drop.item(),
                Component.text(drop.nazwa(), drop.cenny() ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.YELLOW, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                lore.toArray(Component[]::new));
        if (enabled) {
            ItemMeta iconMeta = icon.getItemMeta();
            iconMeta.setEnchantmentGlintOverride(true);
            icon.setItemMeta(iconMeta);
        }
        return icon;
    }

    @EventHandler
    public void onBrukConfigClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof BrukConfigHolder holder) || holder.owner != this) return;
        event.setCancelled(true);

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(tool, player)) {
            player.closeInventory();
            return;
        }

        int slot = event.getSlot();
        if (slot == BRUK_CONFIG_BACK_SLOT) {
            openHub(player, tool);
            return;
        }

        ItemMeta meta = tool.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (slot == BRUK_CONFIG_MASTER_SLOT) {
            boolean enabled = pdc.getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
            pdc.set(pkBrukOn, PersistentDataType.BYTE, (byte) (enabled ? 0 : 1));
            tool.setItemMeta(meta);
            player.playSound(player.getLocation(), enabled ? Sound.ENTITY_VILLAGER_NO : Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            openBrukConfig(player, tool);
            return;
        }

        int idx = slot - BRUK_CONFIG_CONTENT_START;
        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tierOf(pdc));
        if (idx < 0 || idx >= pula.size()) return;

        SurowiecDrop drop = pula.get(idx);
        var wylaczone = csvToSet(pdc.getOrDefault(pkBrukDisabled, PersistentDataType.STRING, ""));
        boolean wasEnabled = !wylaczone.contains(drop.id());
        if (wasEnabled) wylaczone.add(drop.id()); else wylaczone.remove(drop.id());
        pdc.set(pkBrukDisabled, PersistentDataType.STRING, String.join(",", wylaczone));
        tool.setItemMeta(meta);

        player.playSound(player.getLocation(), wasEnabled ? Sound.ENTITY_VILLAGER_NO : Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, wasEnabled ? 0.8f : 1.2f);
        openBrukConfig(player, tool);
    }

    private static final class BrukConfigHolder implements InventoryHolder {
        private final PickaxeSkillManager owner;
        private Inventory inventory;
        private BrukConfigHolder(PickaxeSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
