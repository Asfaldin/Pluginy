package elo.mainplugins.tools.pickaxe;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.GuiUtils;
import elo.mainplugins.tools.pickaxe.PickaxeSkillTrees.Branch;
import elo.mainplugins.tools.pickaxe.PickaxeSkillTrees.SkillNode;
import elo.mainplugins.tools.pickaxe.RarePerks.RarePerk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drzewko umiejętności kilofa - jedyne narzędzie z rozbudowaną, ręcznie sterowaną
 * progresją (reszta duszozłączonych narzędzi zostaje przy prostym, automatycznym
 * systemie z {@link elo.mainplugins.tools.LevelableToolsManager}).
 *
 * Cały stan gracza trzymany jest w PersistentDataContainer samego przedmiotu
 * (klucze "pk_*"), więc kilof jest w pełni samowystarczalny - nie potrzeba osobnej
 * bazy/pliku. Efekty realne (Wydajność/Fortuna) to prawdziwe enchanty na
 * ItemMeta, efekty pasywne (bonus $, aura, itd.) są odczytywane z bitmasek
 * gałęzi i listy wybranych rzadkich perków w odpowiednich listenerach.
 *
 * Progresja ma twardy sufit na 100 poziomach (MAX_LEVEL) = 100 punktów umiejętności,
 * dokładnie tyle, ile węzłów ma pełne drzewko łącznie (34/33/33 na gałąź, patrz
 * PickaxeSkillTrees). Tier (materiał kilofa) rośnie co 20 poziomów (5 tierów x 20 = 100)
 * niezależnie od wykupionych węzłów, więc maksowany kilof jest zawsze już netherytowy -
 * "przelevelowane drewno" nie istnieje. Węzły NIE są tierowo zablokowane osobno - to jeden
 * ciągły łańcuch na gałąź, ale że punkty i tier rosną w tym samym tempie co poziom, gracz
 * i tak fizycznie nie zdąży kupić np. "diamentowych" węzłów będąc jeszcze na tierze drewna.
 */
public class PickaxeSkillManager implements Listener {

    // Kilof ma twardy sufit na 100 poziomach (100 punktów = dokładnie tyle, ile węzłów ma
    // pełne drzewko łącznie - patrz PickaxeSkillTrees). Tier zmienia się co 20 poziomów
    // (5 tierów x 20 poziomów = 100), więc materiał kilofa i poziom rosną razem -
    // maksowany kilof zawsze jest już netherytowy, nigdy "przelevelowanym drewnem".
    private static final int MAX_LEVEL = 100;
    private static final int LEVELS_PER_TIER = 20;
    private static final int MAX_TIER = 4;
    private static final int POINTS_PER_LEVEL = 1;
    // Exp wymagany na poziom zależy od AKTUALNEGO tieru (indeks = tier gracza w danej
    // chwili) - im słabszy kilof, tym mniej exp potrzeba, żeby zniwelować to, że słabszym
    // narzędziem kopie się wolniej. Przewaga maleje z każdym tierem (rosnące, malejące
    // przyrosty: +10, +8, +5, +2), więc pod koniec progresji różnica jest już kosmetyczna.
    private static final int[] EXP_PER_LEVEL_BY_TIER = {25, 35, 43, 48, 50};
    // Co ile poziomów wystawiana jest nowa runda Rzadkiego Wyboru (3 z puli 8 do wyboru
    // 1) - przy 100 poziomach i co 10 to 10 rund, więc pula 8 rzadkich perków wyczerpie
    // się przed samym końcem progresji zamiast już w połowie (jak przy dawnym "co 5").
    private static final int RARE_ROLL_INTERVAL = 10;

    // Hub (Kilof — Umiejętności): domyślnie 27 slotów (3 rzędy), symetrycznie. Górny rząd:
    // bonus z bruku w lewym rogu, kilof (tier + poziom + wolne punkty w jednej ikonie) na
    // samym środku; prawy róg pusty (tło). Środkowy rząd: 3 gałęzie, równo rozstawione pod
    // kilofem. Dolny rząd: Statystyki i Aktualne Ulepszenia symetrycznie po obu stronach
    // Wyjdź (który zostaje na środku); Rzadki Wybór (gdy czeka) w lewym rogu.
    //
    // Rozmiar okna i wszystkie te sloty są konfigurowalne na żywo w kilof-hub.yml (patrz
    // wczytajUkladHuba) - wartości poniżej to tylko fallback, gdyby wpisu brakowało w pliku.
    private static final int DEFAULT_HUB_SIZE = 27;
    private static final int DEFAULT_BRUK_INFO_SLOT = 0;
    private static final int DEFAULT_KILOF_ICON_SLOT = 4;
    private static final int DEFAULT_SLOT_WYDAJNOSC = 10;
    private static final int DEFAULT_SLOT_PRECYZJA = 13;
    private static final int DEFAULT_SLOT_MAGIA = 16;
    private static final int DEFAULT_RARE_BUTTON_SLOT = 18;
    private static final int DEFAULT_STATS_SLOT = 20;
    private static final int DEFAULT_CLOSE_SLOT = 22;
    private static final int DEFAULT_UPGRADES_SLOT = 24;

    private int hubSize;
    private int slotBrukInfo;
    private int slotKilofIcon;
    private int slotRareButton;
    private int slotStats;
    private int slotClose;
    private int slotUpgrades;
    private final Map<Branch, Integer> branchHubSlots = new EnumMap<>(Branch.class);

    // Widok gałęzi: każda gałąź ma teraz ~33-34 węzły (100 łącznie), ale widać naraz tylko 5 (BRANCH_NODE_SLOTS)
    // - strzałki lewo/prawo przełączają stronę (patrz openBranch(..., page)).
    private static final int NODES_PER_PAGE = 5;
    private static final int[] BRANCH_NODE_SLOTS = {11, 12, 13, 14, 15};
    private static final int PAGE_LEFT_SLOT = 9;
    private static final int PAGE_RIGHT_SLOT = 17;
    private static final int BRANCH_BACK_SLOT = 22;
    private static final int[] RARE_CHOICE_SLOTS = {11, 13, 15};
    private static final int AURA_DURATION_TICKS = 1_000_000;
    private static final BlockFace[] NEIGHBOR_FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final Plugin plugin;
    private final EconomyService economyService;
    private final BrukSurowceManager brukSurowceManager;

    private final NamespacedKey keyType;
    private final NamespacedKey keyOwner;
    private final NamespacedKey pkLevel;
    private final NamespacedKey pkExp;
    private final NamespacedKey pkTier;
    private final NamespacedKey pkPoints;
    private final NamespacedKey pkWyd;
    private final NamespacedKey pkPrec;
    private final NamespacedKey pkMag;
    private final NamespacedKey pkRare;
    private final NamespacedKey pkPendingRare;
    // Ile DODATKOWYCH rund Rzadkiego Wyboru czeka w kolejce ponad tę aktualnie wystawioną
    // w pkPendingRare - patrz rollRareChoice/handleRareClick.
    private final NamespacedKey pkPendingRareQueue;
    // Identyfikator naszego AttributeModifier na BLOCK_BREAK_SPEED - patrz syncSpeedAttribute.
    private final NamespacedKey pkSpeedModifierKey;
    // Drugi, niezależny modifier na tym samym atrybucie - pasywny bonus co poziom (patrz
    // syncSpeedAttribute) - osobny klucz, żeby oba mogły współistnieć jednocześnie.
    private final NamespacedKey pkSpeedPassiveModifierKey;
    // Włącznik/wyłącznik bonusu z bruku (BrukSurowceManager) - domyślnie włączony; ten sam
    // klucz odczytuje BrukSurowceManager.isEnabled na własnej instancji NamespacedKey.
    private final NamespacedKey pkBrukOn;

    // Krótkotrwały licznik "kopnięć z rzędu" pod Impet Górnika - celowo w pamięci,
    // nie w PDC: to tylko chwilowy combo-mechanizm, nie trzeba go trzymać po restarcie.
    private final Map<UUID, Long> streakLastBreak = new HashMap<>();
    private final Map<UUID, Integer> streakCount = new HashMap<>();

    public PickaxeSkillManager(Plugin plugin, EconomyService economyService, BrukSurowceManager brukSurowceManager) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.brukSurowceManager = brukSurowceManager;
        this.keyType = new NamespacedKey(plugin, "tool_type");
        this.keyOwner = new NamespacedKey(plugin, "tool_owner");
        this.pkLevel = new NamespacedKey(plugin, "pk_level");
        this.pkExp = new NamespacedKey(plugin, "pk_exp");
        this.pkTier = new NamespacedKey(plugin, "pk_tier");
        this.pkPoints = new NamespacedKey(plugin, "pk_points");
        this.pkWyd = new NamespacedKey(plugin, "pk_wyd");
        this.pkPrec = new NamespacedKey(plugin, "pk_prec");
        this.pkMag = new NamespacedKey(plugin, "pk_mag");
        this.pkRare = new NamespacedKey(plugin, "pk_rare");
        this.pkPendingRare = new NamespacedKey(plugin, "pk_pending_rare");
        this.pkPendingRareQueue = new NamespacedKey(plugin, "pk_pending_rare_queue");
        this.pkSpeedModifierKey = new NamespacedKey(plugin, "pk_speed_bonus");
        this.pkSpeedPassiveModifierKey = new NamespacedKey(plugin, "pk_speed_passive");
        this.pkBrukOn = new NamespacedKey(plugin, "pk_bruk_on");
        wczytajUkladHuba();
    }

    /**
     * Wczytuje rozmiar okna i pozycje slotów huba kilofa z kilof-hub.yml (kopiowany z
     * zasobów pluginu przy pierwszym uruchomieniu, jak bruk-szanse.yml) - admin może
     * powiększyć okno albo przestawić ikonki bez przebudowy pluginu, wystarczy zmienić
     * plik i zrestartować serwer. Rozmiar jest zaokrąglany w górę do wielokrotności 9 i
     * ograniczany do zakresu Bukkita (9-54); każdy slot jest przycinany do [0, rozmiar-1],
     * żeby błędny wpis w pliku nie wyrzucił hubowi ArrayIndexOutOfBoundsException.
     */
    private void wczytajUkladHuba() {
        File plik = new File(plugin.getDataFolder(), "kilof-hub.yml");
        if (!plik.exists()) {
            plugin.saveResource("kilof-hub.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);

        hubSize = clampSize(cfg.getInt("rozmiar", DEFAULT_HUB_SIZE));
        slotBrukInfo = clampSlot(cfg.getInt("sloty.bonus_bruku", DEFAULT_BRUK_INFO_SLOT));
        slotKilofIcon = clampSlot(cfg.getInt("sloty.kilof", DEFAULT_KILOF_ICON_SLOT));
        slotRareButton = clampSlot(cfg.getInt("sloty.rzadki_wybor", DEFAULT_RARE_BUTTON_SLOT));
        slotStats = clampSlot(cfg.getInt("sloty.statystyki", DEFAULT_STATS_SLOT));
        slotClose = clampSlot(cfg.getInt("sloty.wyjdz", DEFAULT_CLOSE_SLOT));
        slotUpgrades = clampSlot(cfg.getInt("sloty.ulepszenia", DEFAULT_UPGRADES_SLOT));
        branchHubSlots.put(Branch.WYDAJNOSC, clampSlot(cfg.getInt("sloty.wydajnosc", DEFAULT_SLOT_WYDAJNOSC)));
        branchHubSlots.put(Branch.PRECYZJA, clampSlot(cfg.getInt("sloty.precyzja", DEFAULT_SLOT_PRECYZJA)));
        branchHubSlots.put(Branch.MAGIA, clampSlot(cfg.getInt("sloty.magia", DEFAULT_SLOT_MAGIA)));
    }

    private int clampSize(int rozmiar) {
        int ograniczony = Math.max(9, Math.min(54, rozmiar));
        return Math.min(54, ((ograniczony + 8) / 9) * 9);
    }

    private int clampSlot(int slot) {
        return Math.max(0, Math.min(slot, hubSize - 1));
    }

    // ============================================================ Kopanie ====

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedPickaxe(item, player)) return;

        ensureInitialized(item);
        applyMiningPerks(player, event.getBlock(), item);
        addExp(player, item);
    }

    private void applyMiningPerks(Player player, Block block, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Material mat = block.getType();
        boolean ore = isOre(mat);
        boolean stoneLike = mat == Material.STONE || mat == Material.COBBLESTONE
                || mat == Material.DEEPSLATE || mat == Material.COBBLED_DEEPSLATE;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // Kamień Filozoficzny (I-V) - podmienia plon kamienia/brukowca, szansa rośnie z każdym węzłem
        double philosophersStoneChance = hasNode(pdc, Branch.MAGIA, "MAG_PHILOSOPHERS_STONE5") ? 0.24
                : hasNode(pdc, Branch.MAGIA, "MAG_PHILOSOPHERS_STONE4") ? 0.20
                : hasNode(pdc, Branch.MAGIA, "MAG_PHILOSOPHERS_STONE3") ? 0.16
                : hasNode(pdc, Branch.MAGIA, "MAG_PHILOSOPHERS_STONE2") ? 0.12 : 0.06;
        if (stoneLike && hasNode(pdc, Branch.MAGIA, "MAG_PHILOSOPHERS_STONE") && rnd.nextDouble() < philosophersStoneChance) {
            if (rnd.nextBoolean()) {
                ItemStack reward = rnd.nextBoolean() ? new ItemStack(Material.IRON_INGOT) : new ItemStack(Material.GOLD_NUGGET, 3);
                block.getWorld().dropItemNaturally(block.getLocation(), reward);
            } else {
                payBonus(player, 3 + tierOf(pdc));
            }
        }

        // Żelazna Pięść (I-IX) / Precyzyjne Uderzenie (I-IX) / Wrota Głębi (I-VII) /
        // Perfekcja Górnika / Mistrzostwo (Wydajności/Precyzji/Magii) / Druga Szansa -
        // dodatkowa kopia plonu (wszystko sumuje się w jedną szansę)
        double bonusDropChance = 0;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP2")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP3")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP4")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP5")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP6")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP7")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP8")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP9")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_MASTERY")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT2")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT3")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT4")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT5")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT6")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT7")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT8")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT9")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_PERFECTION")) bonusDropChance += 0.20;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_MASTERY")) bonusDropChance += 0.20;
        if (hasNode(pdc, Branch.MAGIA, "MAG_DEPTHS_GATE")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.MAGIA, "MAG_DEPTHS_GATE2")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.MAGIA, "MAG_DEPTHS_GATE3")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.MAGIA, "MAG_DEPTHS_GATE4")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.MAGIA, "MAG_DEPTHS_GATE5")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.MAGIA, "MAG_DEPTHS_GATE6")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.MAGIA, "MAG_DEPTHS_GATE7")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.MAGIA, "MAG_MASTERY")) bonusDropChance += 0.10;
        if (hasRare(pdc, "RARE_SECOND_CHANCE")) bonusDropChance += 0.05;
        if (bonusDropChance > 0 && rnd.nextDouble() < bonusDropChance) {
            dropCopy(block, item);
        }

        // Oko Sokoła (I-VIII) - dodatkowy plon z rzadkich rud (osobno sumowane)
        double rareOreBonusChance = 0;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_HAWK_EYE")) rareOreBonusChance += 0.10;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_HAWK_EYE2")) rareOreBonusChance += 0.10;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_HAWK_EYE3")) rareOreBonusChance += 0.10;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_HAWK_EYE4")) rareOreBonusChance += 0.10;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_HAWK_EYE5")) rareOreBonusChance += 0.10;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_HAWK_EYE6")) rareOreBonusChance += 0.10;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_HAWK_EYE7")) rareOreBonusChance += 0.10;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_HAWK_EYE8")) rareOreBonusChance += 0.10;
        if (isRareOre(mat) && rareOreBonusChance > 0 && rnd.nextDouble() < rareOreBonusChance) {
            dropCopy(block, item);
        }

        // Szczęśliwa Ruda (rzadkie rudy) - spory, niezależny wybuch orbów exp
        if (hasNode(pdc, Branch.MAGIA, "MAG_LUCKY_ORE") && isRareOre(mat) && rnd.nextDouble() < 0.15) {
            spawnXp(block, 5 + rnd.nextInt(6));
        }

        // Rezonans Rudy (I-V) - sąsiednie bloki tej samej rudy pękają razem z kopanym;
        // każdy kolejny węzeł podnosi zarówno szansę bazowego triggera, jak i liczbę
        // sąsiadów - patrz resonanceLevelOf.
        int resonanceLevel = resonanceLevelOf(pdc);
        if (ore && resonanceLevel > 0 && rnd.nextDouble() < 0.15 + resonanceLevel * 0.05) {
            breakRandomNeighbors(block, item, mat, resonanceLevel);
        }

        // Rdzeń Chaosu (rzadka) - do 3 sąsiednich bloków kamienia/rudy naraz
        if (hasRare(pdc, "RARE_CHAOS_CORE") && rnd.nextDouble() < 0.05) {
            breakRandomNeighbors(block, item, null, 3);
        }

        // Bystre Oko (I-VII) / Ręka Jubilera (I-IV) / Dotyk Midasa - bonusowa wypłata
        if (ore) {
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_KEEN_EYE") && rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_KEEN_EYE2") && rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_KEEN_EYE3") && rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_KEEN_EYE4") && rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_KEEN_EYE5") && rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_KEEN_EYE6") && rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_KEEN_EYE7") && rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_JEWELER") && rnd.nextDouble() < 0.08) payBonus(player, 6 + tierOf(pdc) * 4);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_JEWELER2") && rnd.nextDouble() < 0.08) payBonus(player, 6 + tierOf(pdc) * 4);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_JEWELER3") && rnd.nextDouble() < 0.08) payBonus(player, 6 + tierOf(pdc) * 4);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_JEWELER4") && rnd.nextDouble() < 0.08) payBonus(player, 6 + tierOf(pdc) * 4);

            // Pasywne Szczęście - rośnie automatycznie z każdym poziomem (niezależnie od
            // wykupionych węzłów), ale wyraźnie słabiej: +0.06%/lvl = max +6% na 100 lvl,
            // wobec do +35% z samych ręcznych węzłów Bystrego Oka (7 węzłów) powyżej.
            int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
            if (rnd.nextDouble() < level * 0.0006) payBonus(player, 2 + tierOf(pdc));
        }
        if (hasRare(pdc, "RARE_MIDAS") && rnd.nextDouble() < 0.03) payBonus(player, 2 + tierOf(pdc));

        // Iskra Many (I-IX) / Nieugięty Duch - bonusowe orby xp
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK2") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK3") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK4") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK5") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK6") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK7") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK8") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK9") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
        if (hasRare(pdc, "RARE_UNYIELDING_SPIRIT") && rnd.nextDouble() < 0.08) spawnXp(block, 10 + rnd.nextInt(11));

        // Magnes Górnika (rzadka)
        if (hasRare(pdc, "RARE_MAGNET")) pullNearbyDrops(player, block.getLocation());

        // Błogosławieństwo Głębi (rzadka)
        if (hasRare(pdc, "RARE_DEPTHS_BLESSING")) {
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + 0.5f));
            if (rnd.nextDouble() < 0.10) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false));
            }
        }

        // Wieczna Sytość / Nieskończona Sytość - słabszy, powtarzalny odpowiednik
        // Błogosławieństwa Głębi z drzewka (drugi węzeł podwaja regenerację)
        if (hasNode(pdc, Branch.MAGIA, "MAG_INSATIABLE2")) {
            float amount = hasNode(pdc, Branch.MAGIA, "MAG_INSATIABLE3") ? 0.2f : 0.1f;
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + amount));
        }

        // Impet Górnika (I-III) / Adrenalina Górnika - kopnięcia z rzędu dają chwilowy
        // Pośpiech (próg, okno czasowe i siła efektu rosną z kolejnymi węzłami w łańcuchu)
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_MOMENTUM")) {
            handleMomentum(player, pdc);
        }
    }

    /** Poziom Rezonansu Rudy (0-5, patrz WYD_RESONANCE.. WYD_RESONANCE5) - im wyżej, tym większa szansa i więcej sąsiadów. */
    private int resonanceLevelOf(PersistentDataContainer pdc) {
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_RESONANCE5")) return 5;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_RESONANCE4")) return 4;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_RESONANCE3")) return 3;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_RESONANCE2")) return 2;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_RESONANCE")) return 1;
        return 0;
    }

    private void handleMomentum(Player player, PersistentDataContainer pdc) {
        UUID id = player.getUniqueId();
        long windowMs = hasNode(pdc, Branch.WYDAJNOSC, "WYD_MOMENTUM3") ? 5000 : 3000;
        long now = System.currentTimeMillis();
        long last = streakLastBreak.getOrDefault(id, 0L);
        int streak = (now - last <= windowMs) ? streakCount.getOrDefault(id, 0) + 1 : 1;
        streakLastBreak.put(id, now);

        int threshold = hasNode(pdc, Branch.WYDAJNOSC, "WYD_MOMENTUM2") ? 2 : 3;
        if (streak >= threshold) {
            streak = 0;
            int amplifier = hasNode(pdc, Branch.WYDAJNOSC, "WYD_ADRENALINE") ? 1 : 0;
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 80, amplifier, true, false, true));
        }
        streakCount.put(id, streak);
    }

    private void dropCopy(Block block, ItemStack tool) {
        for (ItemStack drop : block.getDrops(tool)) {
            block.getWorld().dropItemNaturally(block.getLocation(), drop);
        }
    }

    private void breakRandomNeighbors(Block origin, ItemStack tool, Material requiredType, int maxCount) {
        List<Block> candidates = new ArrayList<>();
        for (BlockFace face : NEIGHBOR_FACES) {
            Block neighbor = origin.getRelative(face);
            Material type = neighbor.getType();
            boolean matches = requiredType != null
                    ? type == requiredType
                    : (isOre(type) || type == Material.STONE || type == Material.COBBLESTONE
                        || type == Material.DEEPSLATE || type == Material.COBBLED_DEEPSLATE);
            if (matches) candidates.add(neighbor);
        }
        Collections.shuffle(candidates);
        int broken = 0;
        for (Block b : candidates) {
            if (broken >= maxCount) break;
            b.breakNaturally(tool);
            broken++;
        }
    }

    private void payBonus(Player player, double amount) {
        economyService.dodajKase(player.getUniqueId(), amount);
        player.sendActionBar(Component.text("+" + formatMoney(amount) + " $", NamedTextColor.GREEN));
    }

    private void spawnXp(Block block, int amount) {
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(amount));
    }

    private void pullNearbyDrops(Player player, Location center) {
        for (Entity e : center.getWorld().getNearbyEntities(center, 4, 4, 4)) {
            if (e instanceof Item dropped) {
                Vector dir = player.getLocation().add(0, 1, 0).toVector().subtract(dropped.getLocation().toVector());
                if (dir.lengthSquared() > 0.01) {
                    dropped.setVelocity(dropped.getVelocity().add(dir.normalize().multiply(0.25)));
                }
            }
        }
    }

    private String formatMoney(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.1f", amount);
    }

    private boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    private boolean isRareOre(Material m) {
        return switch (m) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE, DEEPSLATE_EMERALD_ORE, ANCIENT_DEBRIS -> true;
            default -> false;
        };
    }

    // ======================================================== Progresja ====

    /** [DEBUG] Dodaje `levels` poziomów trzymanemu kilofowi - pod /addlvl. */
    public void debugAddLevels(Player player, ItemStack item, int levels) {
        ensureInitialized(item);
        int startLevel = item.getItemMeta().getPersistentDataContainer().getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int target = Math.min(MAX_LEVEL, startLevel + levels);
        while (item.getItemMeta().getPersistentDataContainer().getOrDefault(pkLevel, PersistentDataType.INTEGER, 1) < target) {
            addExp(player, item);
        }
    }

    private void addExp(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        if (level >= MAX_LEVEL) return;

        int tierBefore = tierOf(pdc);
        int exp = pdc.getOrDefault(pkExp, PersistentDataType.INTEGER, 0) + 1;
        int points = pdc.getOrDefault(pkPoints, PersistentDataType.INTEGER, 0);

        boolean leveledUp = false;
        boolean rareRolled = false;
        boolean tierUp = false;

        if (exp >= expPerLevel(tierBefore)) {
            exp = 0;
            level++;
            points += POINTS_PER_LEVEL;
            leveledUp = true;

            if (level % RARE_ROLL_INTERVAL == 0) {
                rareRolled = rollRareChoice(pdc);
            }
            int tierAfter = tierForLevel(level);
            if (tierAfter != tierBefore) {
                pdc.set(pkTier, PersistentDataType.INTEGER, tierAfter);
                tierUp = true;
            }
        }

        pdc.set(pkLevel, PersistentDataType.INTEGER, level);
        pdc.set(pkExp, PersistentDataType.INTEGER, exp);
        pdc.set(pkPoints, PersistentDataType.INTEGER, points);
        item.setItemMeta(meta);

        refreshDisplay(item);

        if (leveledUp) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            player.sendActionBar(Component.text("Kilof: poziom " + level + " (+" + POINTS_PER_LEVEL + " punkty umiejętności)", NamedTextColor.AQUA));

            if (tierUp) {
                int tier = pdc.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
                player.showTitle(Title.title(
                        Component.text("Kilof Ewoluował!", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("Nowy tier: " + tierName(tier), NamedTextColor.YELLOW)
                ));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            if (rareRolled) {
                player.sendMessage(Component.text("★ Nowy Rzadki Wybór dostępny w drzewku umiejętności kilofa! (PPM)",
                        NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
            }
            if (level >= MAX_LEVEL) {
                player.sendMessage(Component.text("★ Kilof osiągnął maksymalny poziom (" + MAX_LEVEL + ")!",
                        NamedTextColor.GOLD, TextDecoration.BOLD));
            }
        }
    }

    /**
     * @return true, jeśli gracz dostał nową rundę Rzadkiego Wyboru (od razu albo w kolejce) -
     * false tylko wtedy, gdy pula rzadkich perków jest już cała wykupiona.
     *
     * Poprzednio: jeśli gracz nie zdążył wybrać poprzedniej trójki, kolejny próg
     * level % RARE_ROLL_INTERVAL == 0 po prostu NIC nie robił (rzucona runda przepadała) -
     * stąd "mogę wybrać tylko 1", mimo osiągnięcia kilku progów. Teraz: gdy trójka już
     * czeka, dokładamy rundę do kolejki (pkPendingRareQueue) zamiast ją tracić - patrz
     * handleRareClick, gdzie po wyborze kolejka jest odpalana automatycznie.
     */
    private boolean rollRareChoice(PersistentDataContainer pdc) {
        Set<String> owned = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        if (owned.size() >= RarePerks.WSZYSTKIE.size()) return false;

        String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
        if (!pending.isEmpty()) {
            int queued = pdc.getOrDefault(pkPendingRareQueue, PersistentDataType.INTEGER, 0);
            pdc.set(pkPendingRareQueue, PersistentDataType.INTEGER, queued + 1);
            return true;
        }

        return offerNewRareChoice(pdc, owned);
    }

    /** Losuje nową trójkę rzadkich perków (spośród jeszcze niewykupionych) i zapisuje ją jako oczekującą. */
    private boolean offerNewRareChoice(PersistentDataContainer pdc, Set<String> owned) {
        List<RarePerk> remaining = new ArrayList<>();
        for (RarePerk perk : RarePerks.WSZYSTKIE) {
            if (!owned.contains(perk.id())) remaining.add(perk);
        }
        if (remaining.isEmpty()) return false;

        Collections.shuffle(remaining);
        List<String> picks = remaining.stream().limit(3).map(RarePerk::id).toList();
        pdc.set(pkPendingRare, PersistentDataType.STRING, String.join(",", picks));
        return true;
    }

    private void ensureInitialized(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(pkLevel, PersistentDataType.INTEGER)) return;

        pdc.set(pkLevel, PersistentDataType.INTEGER, 1);
        pdc.set(pkExp, PersistentDataType.INTEGER, 0);
        pdc.set(pkTier, PersistentDataType.INTEGER, 0);
        pdc.set(pkPoints, PersistentDataType.INTEGER, 0);
        pdc.set(pkWyd, PersistentDataType.INTEGER, 0);
        pdc.set(pkPrec, PersistentDataType.INTEGER, 0);
        pdc.set(pkMag, PersistentDataType.INTEGER, 0);
        pdc.set(pkRare, PersistentDataType.STRING, "");
        pdc.set(pkPendingRare, PersistentDataType.STRING, "");
        pdc.set(pkPendingRareQueue, PersistentDataType.INTEGER, 0);
        pdc.set(pkBrukOn, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);

        refreshDisplay(item);
    }

    private void refreshDisplay(ItemStack item) {
        ItemMeta metaBefore = item.getItemMeta();
        PersistentDataContainer pdcBefore = metaBefore.getPersistentDataContainer();
        int tier = pdcBefore.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
        Material correctMaterial = materialForTier(tier);
        if (item.getType() != correctMaterial) {
            item.setType(correctMaterial);
        }

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int exp = pdc.getOrDefault(pkExp, PersistentDataType.INTEGER, 0);
        int points = pdc.getOrDefault(pkPoints, PersistentDataType.INTEGER, 0);
        int wyd = Integer.bitCount(pdc.getOrDefault(pkWyd, PersistentDataType.INTEGER, 0));
        int prec = Integer.bitCount(pdc.getOrDefault(pkPrec, PersistentDataType.INTEGER, 0));
        int mag = Integer.bitCount(pdc.getOrDefault(pkMag, PersistentDataType.INTEGER, 0));
        String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
        int queuedRare = pdc.getOrDefault(pkPendingRareQueue, PersistentDataType.INTEGER, 0);

        meta.displayName(Component.text("Kilof Umiejętności ", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text("[Poziom " + level + "]", NamedTextColor.YELLOW, TextDecoration.BOLD)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Tier: " + tierName(tier), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (level >= MAX_LEVEL) {
            lore.add(Component.text("Postęp: MAKSYMALNY POZIOM", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        } else {
            int expNeeded = expPerLevel(tier);
            lore.add(Component.text("Postęp: " + expBar(exp, expNeeded) + " " + exp + "/" + expNeeded, NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Punkty umiejętności: " + points, points > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Wydajność " + wyd + "/" + Branch.WYDAJNOSC.nodes.size()
                + "  •  Precyzja " + prec + "/" + Branch.PRECYZJA.nodes.size()
                + "  •  Magia " + mag + "/" + Branch.MAGIA.nodes.size(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (!pending.isEmpty()) {
            lore.add(Component.empty());
            lore.add(Component.text("★ Dostępny Rzadki Wybór!" + (queuedRare > 0 ? " (+" + queuedRare + " w kolejce)" : ""),
                    NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Prywatny kilof", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Shift + PPM - otwórz drzewko umiejętności", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        if (meta instanceof Damageable damageable) {
            short maxDurability = item.getType().getMaxDurability();
            double progress = level >= MAX_LEVEL ? 1.0 : (double) exp / expPerLevel(tier);
            int damage = (int) Math.round(maxDurability - maxDurability * progress);
            damageable.setDamage(Math.max(0, Math.min(damage, maxDurability - 1)));
        }

        syncEnchants(meta, pdc);
        item.setItemMeta(meta);
    }

    private String expBar(int exp, int expNeeded) {
        int filled = (int) Math.round(10.0 * exp / expNeeded);
        return "▮".repeat(Math.max(0, filled)) + "▯".repeat(Math.max(0, 10 - filled));
    }

    /** Ile expa potrzeba na kolejny poziom, licząc od AKTUALNEGO tieru gracza - patrz EXP_PER_LEVEL_BY_TIER. */
    private int expPerLevel(int tier) {
        return EXP_PER_LEVEL_BY_TIER[Math.max(0, Math.min(tier, EXP_PER_LEVEL_BY_TIER.length - 1))];
    }

    /** Tier wynikający z poziomu - zmienia się co LEVELS_PER_TIER poziomów, patrz stałe u góry klasy. */
    private int tierForLevel(int level) {
        return Math.min((level - 1) / LEVELS_PER_TIER, MAX_TIER);
    }

    private void syncEnchants(ItemMeta meta, PersistentDataContainer pdc) {
        int wydMask = pdc.getOrDefault(pkWyd, PersistentDataType.INTEGER, 0);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int effLevel = effLevelOf(pdc);
        int fortLevel = fortLevelOf(pdc);

        meta.removeEnchant(Enchantment.EFFICIENCY);
        if (effLevel > 0) meta.addEnchant(Enchantment.EFFICIENCY, effLevel, true);
        meta.removeEnchant(Enchantment.FORTUNE);
        if (fortLevel > 0) meta.addEnchant(Enchantment.FORTUNE, fortLevel, true);

        syncSpeedAttribute(meta, wydMask, level);
    }

    /**
     * Węzły "Przyspieszenie I-XV" (drzewko Wydajności) nie dają już enchantu, tylko po
     * +3% do realnego atrybutu BLOCK_BREAK_SPEED (mnożnik multiplikatywny bazy, sumowany
     * addytywnie po węzłach - stąd Operation.ADD_SCALAR) - drobny, liniowy bonus zamiast
     * skokowych poziomów Wydajności. Do tego dochodzi DRUGI, niezależny modifier: pasywny
     * +0.08%/poziom, naliczany automatycznie niezależnie od wykupionych węzłów - wyraźnie
     * słabszy niż pełna inwestycja w drzewko (max +0.08%*100=8% pasywnie vs +45% z 15
     * węzłów Przyspieszenia - ok. 18%, w docelowym zakresie 15-20%), żeby ręczne punkty
     * dalej miały sens. Oba modifiery są przeliczane od zera przy każdym odświeżeniu,
     * żeby nie zdublować się przy wielokrotnym refreshu.
     */
    private void syncSpeedAttribute(ItemMeta meta, int wydMask, int level) {
        int speedNodes = speedNodesOf(wydMask);

        meta.removeAttributeModifier(Attribute.BLOCK_BREAK_SPEED);
        if (speedNodes > 0) {
            meta.addAttributeModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(
                    pkSpeedModifierKey, speedNodes * 0.03, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }
        if (level > 0) {
            meta.addAttributeModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(
                    pkSpeedPassiveModifierKey, level * 0.0008, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }
    }

    // ==================================================== Statystyki - odczyt ====
    // Wspólne dla syncEnchants/syncSpeedAttribute (co realnie ląduje na przedmiocie)
    // i statsIcon w hubie (co widzi gracz) - jedno źródło prawdy, zero duplikacji.

    /** Prawdziwa Wydajność (enchant) - wyłącznie z rzadkiego perku RARE_EFFICIENCY. */
    private int effLevelOf(PersistentDataContainer pdc) {
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        return rare.contains("RARE_EFFICIENCY") ? 1 : 0;
    }

    /** Prawdziwa Fortuna (enchant) - z drzewka Precyzji + ewentualny bonus rzadkiego RARE_FORT4. */
    private int fortLevelOf(PersistentDataContainer pdc) {
        int precMask = pdc.getOrDefault(pkPrec, PersistentDataType.INTEGER, 0);
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));

        int fortLevel;
        if (bitSet(precMask, indexOf(Branch.PRECYZJA, "PREC_FORT3"))) fortLevel = 3;
        else if (bitSet(precMask, indexOf(Branch.PRECYZJA, "PREC_FORT2"))) fortLevel = 2;
        else if (bitSet(precMask, indexOf(Branch.PRECYZJA, "PREC_FORT1"))) fortLevel = 1;
        else fortLevel = 0;
        if (rare.contains("RARE_FORT4") && fortLevel > 0) fortLevel += 1;
        return fortLevel;
    }

    /** Liczba wykupionych węzłów "Przyspieszenie I-IV" - każdy to +3% BLOCK_BREAK_SPEED. */
    private int speedNodesOf(int wydMask) {
        int count = 0;
        for (SkillNode node : Branch.WYDAJNOSC.nodes) {
            if (node.id().startsWith("WYD_SPEED") && bitSet(wydMask, indexOf(Branch.WYDAJNOSC, node.id()))) {
                count++;
            }
        }
        return count;
    }

    /** Poziom aury Pośpiechu z drzewka Magii (0 = brak) - patrz syncHasteAura. */
    private int hasteLevelOf(PersistentDataContainer pdc) {
        int magMask = pdc.getOrDefault(pkMag, PersistentDataType.INTEGER, 0);
        if (bitSet(magMask, indexOf(Branch.MAGIA, "MAG_HASTE5"))) return 5;
        if (bitSet(magMask, indexOf(Branch.MAGIA, "MAG_HASTE4"))) return 4;
        if (bitSet(magMask, indexOf(Branch.MAGIA, "MAG_HASTE3"))) return 3;
        if (bitSet(magMask, indexOf(Branch.MAGIA, "MAG_HASTE2"))) return 2;
        if (bitSet(magMask, indexOf(Branch.MAGIA, "MAG_HASTE1"))) return 1;
        return 0;
    }

    private String rzymskie(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    private boolean bitSet(int mask, int index) {
        return (mask & (1 << index)) != 0;
    }

    private Material materialForTier(int tier) {
        return switch (tier) {
            case 0 -> Material.WOODEN_PICKAXE;
            case 1 -> Material.STONE_PICKAXE;
            case 2 -> Material.IRON_PICKAXE;
            case 3 -> Material.DIAMOND_PICKAXE;
            default -> Material.NETHERITE_PICKAXE;
        };
    }

    private String tierName(int tier) {
        return switch (tier) {
            case 0 -> "Drewno";
            case 1 -> "Kamień";
            case 2 -> "Żelazo";
            case 3 -> "Diament";
            default -> "Netherite";
        };
    }

    private int tierOf(PersistentDataContainer pdc) {
        return pdc.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
    }

    // ================================================= Drzewko - odczyt ====

    private int indexOf(Branch branch, String nodeId) {
        List<SkillNode> nodes = branch.nodes;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id().equals(nodeId)) return i;
        }
        throw new IllegalArgumentException("Nieznany węzeł: " + nodeId);
    }

    private NamespacedKey maskKeyFor(Branch branch) {
        return switch (branch) {
            case WYDAJNOSC -> pkWyd;
            case PRECYZJA -> pkPrec;
            case MAGIA -> pkMag;
        };
    }

    private boolean hasNode(PersistentDataContainer pdc, Branch branch, String nodeId) {
        int mask = pdc.getOrDefault(maskKeyFor(branch), PersistentDataType.INTEGER, 0);
        return bitSet(mask, indexOf(branch, nodeId));
    }

    private boolean hasRare(PersistentDataContainer pdc, String rareId) {
        String csv = pdc.getOrDefault(pkRare, PersistentDataType.STRING, "");
        if (csv.isEmpty()) return false;
        for (String id : csv.split(",")) {
            if (id.equals(rareId)) return true;
        }
        return false;
    }

    private Set<String> csvToSet(String csv) {
        if (csv == null || csv.isEmpty()) return new LinkedHashSet<>();
        return new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    private boolean isOwnedPickaxe(ItemStack item, Player player) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!"pickaxe".equals(pdc.get(keyType, PersistentDataType.STRING))) return false;
        String owner = pdc.get(keyOwner, PersistentDataType.STRING);
        return owner != null && owner.equals(player.getUniqueId().toString());
    }

    // ===================================================== Pasywne aury ====

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        syncHasteAura(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand());
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        ItemStack incoming = event.getPlayer().getInventory().getItem(event.getNewSlot());
        syncHasteAura(event.getPlayer(), incoming);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        syncHasteAura(event.getPlayer(), event.getMainHandItem());
    }

    private void syncHasteAura(Player player, ItemStack candidateMainHand) {
        Integer amplifier = null;
        if (isOwnedPickaxe(candidateMainHand, player)) {
            ItemMeta meta = candidateMainHand.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            if (hasNode(pdc, Branch.MAGIA, "MAG_HASTE5")) amplifier = 4;
            else if (hasNode(pdc, Branch.MAGIA, "MAG_HASTE4")) amplifier = 3;
            else if (hasNode(pdc, Branch.MAGIA, "MAG_HASTE3")) amplifier = 2;
            else if (hasNode(pdc, Branch.MAGIA, "MAG_HASTE2")) amplifier = 1;
            else if (hasNode(pdc, Branch.MAGIA, "MAG_HASTE1")) amplifier = 0;
        }

        PotionEffect current = player.getPotionEffect(PotionEffectType.HASTE);
        boolean oursActive = current != null && current.getDuration() > 50_000;

        if (amplifier != null) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, AURA_DURATION_TICKS, amplifier, true, false, false));
        } else if (oursActive) {
            player.removePotionEffect(PotionEffectType.HASTE);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean fallingBlock = cause == EntityDamageEvent.DamageCause.FALLING_BLOCK;
        boolean fall = cause == EntityDamageEvent.DamageCause.FALL;
        if (!fallingBlock && !fall) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedPickaxe(item, player)) return;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        // Grawitacyjna Odporność chroni tylko przed spadającymi blokami; Wzmocniona Odporność
        // (dalej w łańcuchu, wymaga podstawowej) rozszerza to na zwykły upadek gracza.
        if (fallingBlock && hasNode(pdc, Branch.MAGIA, "MAG_GRAVITY_WARD")) {
            event.setCancelled(true);
        } else if (fall && hasNode(pdc, Branch.MAGIA, "MAG_GRAVITY_WARD2")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFoodLevel() >= player.getFoodLevel()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedPickaxe(item, player)) return;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (hasNode(pdc, Branch.MAGIA, "MAG_INSATIABLE")) {
            event.setCancelled(true);
        }
    }

    // ============================================================= GUI ====

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getPlayer().isSneaking()) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedPickaxe(item, player)) return;

        event.setCancelled(true);
        ensureInitialized(item);
        openHub(player, item);
    }

    private void openHub(Player player, ItemStack pickaxe) {
        ItemMeta meta = pickaxe.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int tier = pdc.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
        int points = pdc.getOrDefault(pkPoints, PersistentDataType.INTEGER, 0);
        String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
        int queuedRare = pdc.getOrDefault(pkPendingRareQueue, PersistentDataType.INTEGER, 0);

        HubHolder holder = new HubHolder();
        Inventory gui = Bukkit.createInventory(holder, hubSize, Component.text("Kilof — Umiejętności", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.PURPLE_STAINED_GLASS_PANE);

        gui.setItem(slotKilofIcon, GuiUtils.namedItem(materialForTier(tier),
                Component.text("Kilof [Poziom " + level + "]", NamedTextColor.AQUA, TextDecoration.BOLD),
                Component.text("Tier: " + tierName(tier), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Wolne punkty: " + points, points > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));

        for (Branch branch : Branch.values()) {
            int mask = pdc.getOrDefault(maskKeyFor(branch), PersistentDataType.INTEGER, 0);
            int owned = Integer.bitCount(mask);
            ItemStack icon = GuiUtils.namedItem(branch.icon,
                    Component.text(branch.displayName, branch.color, TextDecoration.BOLD),
                    Component.text("Wykupione: " + owned + "/" + branch.nodes.size(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Kliknij, aby otworzyć", branch.color).decoration(TextDecoration.ITALIC, false));
            gui.setItem(hubSlotFor(branch), icon);
        }

        boolean brukEnabled = pdc.getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
        gui.setItem(slotBrukInfo, brukInfoIcon(tier, brukEnabled));
        gui.setItem(slotStats, statsIcon(pdc, "Statystyki Kilofa"));
        gui.setItem(slotUpgrades, statsIcon(pdc, "Aktualne Ulepszenia"));

        if (!pending.isEmpty()) {
            gui.setItem(slotRareButton, GuiUtils.namedItem(Material.NETHER_STAR,
                    Component.text("★ Rzadki Wybór dostępny!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                    Component.text("Kliknij, aby wybrać jedną z 3 rzadkich", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("umiejętności dla swojego kilofa.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text(queuedRare > 0 ? "+" + queuedRare + " kolejnych w kolejce" : " ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        }

        gui.setItem(slotClose, GuiUtils.namedItem(Material.BARRIER, Component.text("Zamknij", NamedTextColor.RED, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    /**
     * Ikonka w hubie kilofa ze skrótem AKTUALNYCH statystyk (to, co realnie jest w tej
     * chwili na przedmiocie) - żeby nie trzeba było ręcznie sumować węzłów z 3 gałęzi +
     * rzadkich perków, żeby wiedzieć "ile mam". Czyta te same helpery (effLevelOf/
     * fortLevelOf/speedNodesOf/hasteLevelOf), których używa syncEnchants - więc zawsze
     * zgadza się z tym, co faktycznie działa. Używana dwa razy w hubie (Statystyki +
     * Aktualne Ulepszenia, po obu stronach Wyjdź) z tą samą treścią, różnym tytułem.
     */
    private ItemStack statsIcon(PersistentDataContainer pdc, String title) {
        int wydMask = pdc.getOrDefault(pkWyd, PersistentDataType.INTEGER, 0);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int effLevel = effLevelOf(pdc);
        int fortLevel = fortLevelOf(pdc);
        int speedNodes = speedNodesOf(wydMask);
        int haste = hasteLevelOf(pdc);
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Wydajność (enchant): " + (effLevel > 0 ? rzymskie(effLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Prędkość kopania (drzewko): +" + (speedNodes * 3) + "%", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Fortuna (enchant): " + (fortLevel > 0 ? rzymskie(fortLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Aura Pośpiechu: " + (haste > 0 ? rzymskie(haste) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Pasywne (co poziom, niezależnie od drzewka):", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("+" + formatPercent(level * 0.08) + "% prędkości  •  +" + formatPercent(level * 0.06) + "% szczęścia",
                NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Rzadkie perki (" + rare.size() + "):", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        if (rare.isEmpty()) {
            lore.add(Component.text("Brak", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            for (String id : rare) {
                RarePerk perk = findRarePerk(id);
                if (perk != null) {
                    lore.add(Component.text("• " + perk.displayName(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                }
            }
        }

        return GuiUtils.namedItem(Material.KNOWLEDGE_BOOK,
                Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD),
                lore.toArray(Component[]::new));
    }

    /**
     * Ikonka w hubie kilofa pokazująca realną szansę % (z bruk-szanse.yml) na to, że
     * skopanie bruku (COBBLESTONE) doda do dropu bonusowy surowiec - patrz
     * BrukSurowceManager. Pula jest losowana równomiernie, więc % na KONKRETNY surowiec =
     * szansa tieru / rozmiar puli. Kliknięcie w tę ikonkę przełącza bonus wł/wył (pkBrukOn) -
     * patrz handleHubClick.
     */
    private ItemStack brukInfoIcon(int tier, boolean enabled) {
        double szansa = brukSurowceManager.szansaDlaTieru(tier);
        List<BrukSurowce.SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tier);
        double naSurowiec = pula.isEmpty() ? 0 : szansa / pula.size();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Status: " + (enabled ? "WŁĄCZONY" : "WYŁĄCZONY"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Kopiąc bruk (cobblestone) masz szansę", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("na dodatkowy surowiec w dropie:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(formatPercent(szansa) + "% łącznie", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Losowo jeden z (tier " + tierName(tier) + "):", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        for (BrukSurowce.SurowiecDrop drop : pula) {
            lore.add(Component.text("• " + drop.nazwa() + " — " + formatPercent(naSurowiec) + "%", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Kliknij, aby " + (enabled ? "wyłączyć" : "włączyć"), NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));

        return GuiUtils.namedItem(Material.COBBLESTONE,
                Component.text("Bonus z Bruku", NamedTextColor.GOLD, TextDecoration.BOLD),
                lore.toArray(Component[]::new));
    }

    private String formatPercent(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.format("%.2f", value);
    }

    private int maxPageFor(Branch branch) {
        return (branch.nodes.size() - 1) / NODES_PER_PAGE;
    }

    private void openBranch(Player player, ItemStack pickaxe, Branch branch, int page) {
        ItemMeta meta = pickaxe.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int mask = pdc.getOrDefault(maskKeyFor(branch), PersistentDataType.INTEGER, 0);
        int points = pdc.getOrDefault(pkPoints, PersistentDataType.INTEGER, 0);
        int maxPage = maxPageFor(branch);
        page = Math.max(0, Math.min(page, maxPage));

        BranchHolder holder = new BranchHolder();
        holder.branch = branch;
        holder.page = page;
        Inventory gui = Bukkit.createInventory(holder, 27, Component.text(
                branch.displayName + " — Drzewko (" + (page + 1) + "/" + (maxPage + 1) + ")", branch.color, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui);

        List<SkillNode> nodes = branch.nodes;
        int start = page * NODES_PER_PAGE;
        int end = Math.min(start + NODES_PER_PAGE, nodes.size());
        for (int i = start; i < end; i++) {
            SkillNode node = nodes.get(i);
            boolean purchased = bitSet(mask, i);
            boolean available = i == 0 || bitSet(mask, i - 1);

            NamedTextColor nameColor;
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(node.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            if (purchased) {
                nameColor = NamedTextColor.GREEN;
                lore.add(Component.text("✔ Wykupione", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            } else if (available) {
                nameColor = NamedTextColor.YELLOW;
                lore.add(Component.text("Koszt: 1 punkt (masz " + points + ")", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Kliknij, aby wykupić", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            } else {
                nameColor = NamedTextColor.DARK_GRAY;
                lore.add(Component.text("Zablokowane - wykup poprzedni węzeł", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            }

            ItemStack icon = GuiUtils.namedItem(node.icon(), Component.text(node.displayName(), nameColor, TextDecoration.BOLD), lore.toArray(Component[]::new));
            if (purchased) {
                ItemMeta iconMeta = icon.getItemMeta();
                iconMeta.setEnchantmentGlintOverride(true);
                icon.setItemMeta(iconMeta);
            }
            gui.setItem(BRANCH_NODE_SLOTS[i - start], icon);
        }

        if (page > 0) {
            gui.setItem(PAGE_LEFT_SLOT, GuiUtils.namedItem(Material.ARROW, Component.text("« Poprzednia strona", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        }
        if (end < nodes.size()) {
            gui.setItem(PAGE_RIGHT_SLOT, GuiUtils.namedItem(Material.ARROW, Component.text("Następna strona »", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        }

        gui.setItem(BRANCH_BACK_SLOT, GuiUtils.namedItem(Material.BARRIER, Component.text("« Wróć", NamedTextColor.GOLD, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private void openRareChoice(Player player, String pendingCsv) {
        List<String> ids = Arrays.asList(pendingCsv.split(","));
        RareHolder holder = new RareHolder();
        Inventory gui = Bukkit.createInventory(holder, 27, Component.text("Rzadki Wybór", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.PURPLE_STAINED_GLASS_PANE);

        for (int i = 0; i < ids.size() && i < RARE_CHOICE_SLOTS.length; i++) {
            RarePerk perk = findRarePerk(ids.get(i));
            if (perk == null) continue;

            ItemStack icon = GuiUtils.namedItem(perk.icon(),
                    Component.text(perk.displayName(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                    Component.text(perk.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Kliknij, aby wybrać - NIEODWRACALNE", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            ItemMeta iconMeta = icon.getItemMeta();
            iconMeta.setEnchantmentGlintOverride(true);
            icon.setItemMeta(iconMeta);
            gui.setItem(RARE_CHOICE_SLOTS[i], icon);
        }

        gui.setItem(22, GuiUtils.namedItem(Material.BARRIER, Component.text("Zdecyduj później", NamedTextColor.GRAY, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private RarePerk findRarePerk(String id) {
        for (RarePerk perk : RarePerks.WSZYSTKIE) {
            if (perk.id().equals(id)) return perk;
        }
        return null;
    }

    private int hubSlotFor(Branch branch) {
        return branchHubSlots.get(branch);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof HubHolder) && !(holder instanceof BranchHolder) && !(holder instanceof RareHolder)) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        ItemStack pickaxe = player.getInventory().getItemInMainHand();
        if (!isOwnedPickaxe(pickaxe, player)) {
            player.closeInventory();
            return;
        }
        ensureInitialized(pickaxe);

        if (holder instanceof HubHolder) {
            handleHubClick(player, pickaxe, clicked, event.getSlot());
        } else if (holder instanceof BranchHolder branchHolder) {
            handleBranchClick(player, pickaxe, branchHolder, event.getSlot());
        } else {
            handleRareClick(player, pickaxe, event.getSlot());
        }
    }

    private void handleHubClick(Player player, ItemStack pickaxe, ItemStack clicked, int slot) {
        if (clicked.getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }
        if (slot == slotRareButton) {
            PersistentDataContainer pdc = pickaxe.getItemMeta().getPersistentDataContainer();
            String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
            if (!pending.isEmpty()) openRareChoice(player, pending);
            return;
        }
        if (slot == slotBrukInfo) {
            ItemMeta meta = pickaxe.getItemMeta();
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            boolean enabled = pdc.getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
            pdc.set(pkBrukOn, PersistentDataType.BYTE, (byte) (enabled ? 0 : 1));
            pickaxe.setItemMeta(meta);
            player.playSound(player.getLocation(), enabled ? Sound.ENTITY_VILLAGER_NO : Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            player.sendActionBar(Component.text("Bonus z Bruku: " + (enabled ? "wyłączony" : "włączony"),
                    enabled ? NamedTextColor.RED : NamedTextColor.GREEN));
            openHub(player, pickaxe);
            return;
        }
        for (Branch branch : Branch.values()) {
            if (slot == hubSlotFor(branch)) {
                openBranch(player, pickaxe, branch, 0);
                return;
            }
        }
    }

    private void handleBranchClick(Player player, ItemStack pickaxe, BranchHolder branchHolder, int slot) {
        Branch branch = branchHolder.branch;
        int page = branchHolder.page;

        if (slot == BRANCH_BACK_SLOT) {
            openHub(player, pickaxe);
            return;
        }
        if (slot == PAGE_LEFT_SLOT && page > 0) {
            openBranch(player, pickaxe, branch, page - 1);
            return;
        }
        if (slot == PAGE_RIGHT_SLOT && page < maxPageFor(branch)) {
            openBranch(player, pickaxe, branch, page + 1);
            return;
        }

        int posOnPage = -1;
        for (int i = 0; i < BRANCH_NODE_SLOTS.length; i++) {
            if (BRANCH_NODE_SLOTS[i] == slot) {
                posOnPage = i;
                break;
            }
        }
        if (posOnPage < 0) return;
        int idx = page * NODES_PER_PAGE + posOnPage;
        if (idx >= branch.nodes.size()) return;

        ItemMeta meta = pickaxe.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        NamespacedKey maskKey = maskKeyFor(branch);
        int mask = pdc.getOrDefault(maskKey, PersistentDataType.INTEGER, 0);
        boolean purchased = bitSet(mask, idx);
        boolean available = idx == 0 || bitSet(mask, idx - 1);
        int points = pdc.getOrDefault(pkPoints, PersistentDataType.INTEGER, 0);

        if (purchased) {
            player.sendActionBar(Component.text("Już posiadasz to ulepszenie.", NamedTextColor.GRAY));
        } else if (!available) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            player.sendActionBar(Component.text("Najpierw wykup poprzedni węzeł!", NamedTextColor.RED));
        } else if (points <= 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            player.sendActionBar(Component.text("Brak punktów umiejętności!", NamedTextColor.RED));
        } else {
            pdc.set(maskKey, PersistentDataType.INTEGER, mask | (1 << idx));
            pdc.set(pkPoints, PersistentDataType.INTEGER, points - 1);
            pickaxe.setItemMeta(meta);
            refreshDisplay(pickaxe);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            player.sendActionBar(Component.text("Wykupiono: " + branch.nodes.get(idx).displayName(), NamedTextColor.GREEN));
        }

        openBranch(player, pickaxe, branch, page);
    }

    private void handleRareClick(Player player, ItemStack pickaxe, int slot) {
        ItemMeta meta = pickaxe.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");

        if (slot == 22 || pending.isEmpty()) {
            player.closeInventory();
            return;
        }

        List<String> ids = Arrays.asList(pending.split(","));
        int idx = -1;
        for (int i = 0; i < RARE_CHOICE_SLOTS.length; i++) {
            if (RARE_CHOICE_SLOTS[i] == slot) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx >= ids.size()) return;

        String chosenId = ids.get(idx);
        RarePerk chosen = findRarePerk(chosenId);
        if (chosen == null) return;

        Set<String> owned = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        owned.add(chosenId);
        pdc.set(pkRare, PersistentDataType.STRING, String.join(",", owned));
        pdc.set(pkPendingRare, PersistentDataType.STRING, "");

        // Jeśli w kolejce czekała jeszcze jedna runda (patrz rollRareChoice), odpalamy ją
        // od razu, żeby gracz nie stracił żadnego z progów level % RARE_ROLL_INTERVAL == 0,
        // które trafiły podczas gdy poprzedni wybór wciąż czekał.
        int queued = pdc.getOrDefault(pkPendingRareQueue, PersistentDataType.INTEGER, 0);
        if (queued > 0) {
            pdc.set(pkPendingRareQueue, PersistentDataType.INTEGER, queued - 1);
            offerNewRareChoice(pdc, owned);
        }

        pickaxe.setItemMeta(meta);
        refreshDisplay(pickaxe);

        player.sendMessage(Component.text("★ Wybrano rzadką umiejętność: " + chosen.displayName(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        player.showTitle(Title.title(
                Component.text("Rzadka Umiejętność!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                Component.text(chosen.displayName(), NamedTextColor.WHITE)
        ));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);

        openHub(player, pickaxe);
    }

    // ========================================================= Holdery ====

    private static final class HubHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class BranchHolder implements InventoryHolder {
        private Inventory inventory;
        private Branch branch;
        private int page;
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class RareHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}