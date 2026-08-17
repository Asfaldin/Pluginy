package elo.mainplugins.tools.skilltree;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.GuiUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generyczny silnik progresji narzędzia - model "kart" (roguelike level-up), nie stare
 * drzewko kup-po-kolei. Co poziom gracz dostaje ofertę 4 kart wylosowanych z połączonej
 * puli wszystkich gałęzi, ważonej rzadkością (patrz Rarity) - wybiera 1. Karty
 * COMMON/RARE/EPIC są stackowalne (do card.maxStacks()), LEGENDARY to dawna pula
 * RarePerks - unikalne, max 1 sztuka na całe życie przedmiotu (ta sama pula/mechanizm
 * co wcześniej, tylko teraz zintegrowana w jedno losowanie zamiast osobnego mechanizmu
 * "co N poziomów").
 *
 * Świadomie: suma maxStacks wszystkich kart w drzewku danego narzędzia jest WIĘKSZA niż
 * MAX_LEVEL (100) - to jest zamierzone (margines niedoboru), żeby żaden gracz nie mógł
 * dobić do "wszystko wykupione" i wybór faktycznie coś kosztował.
 *
 * Wszystkie 4 duszozłączone narzędzia (kilof/siekiera/motyka/miecz) są na tym silniku -
 * kilof jako jedyny ma dodatkową ikonkę w hubie (przełącznik bonusu z bruku, patrz
 * PickaxeSkillManager#populateExtraHubIcons) poza standardowym zestawem.
 *
 * WAŻNE: każda instancja tworzy WŁASNE obiekty Holder (HubHolder/OfferHolder) ze
 * wskaźnikiem "owner" na siebie - onInventoryClick sprawdza owner == this, żeby przy
 * kilku zarejestrowanych instancjach (siekiera + motyka + miecz naraz) każda reagowała
 * WYŁĄCZNIE na swoje własne GUI, nie na cudze.
 */
public abstract class ToolSkillManager implements Listener {

    protected static final int MAX_LEVEL = 100;
    protected static final int LEVELS_PER_TIER = 20;
    protected static final int MAX_TIER = 4;
    protected static final int[] EXP_PER_LEVEL_BY_TIER = {25, 35, 43, 48, 50};

    private static final int DEFAULT_HUB_SIZE = 27;
    private static final int DEFAULT_TOOL_ICON_SLOT = 4;
    private static final int DEFAULT_OFFER_SLOT = 13;
    private static final int DEFAULT_CLOSE_SLOT = 22;
    private static final int DEFAULT_INFO_SLOT = 23;

    private static final int OFFER_HUB_SIZE = 36;
    private static final int[] OFFER_CHOICE_SLOTS = {10, 12, 14, 16};
    private static final int OFFER_BACK_SLOT = 22;
    protected static final int AURA_DURATION_TICKS = 1_000_000;

    // Menu "Informacje" (launcher) - małe, symetryczne, otwierane z beacona w hubie. Samo
    // NIC nie pokazuje - to tylko 3 książki/beacon prowadzące do właściwych ekranów.
    private static final int INFO_LAUNCHER_SIZE = 27;
    private static final int INFO_LAUNCHER_STATS_SLOT = 11;
    private static final int INFO_LAUNCHER_SKILLS_SLOT = 13;
    private static final int INFO_LAUNCHER_SYNERGY_SLOT = 15;
    private static final int INFO_LAUNCHER_BACK_SLOT = 22;

    // Statystyki - osobny mały ekran, bloczki wyśrodkowane symetrycznie w jednym rzędzie.
    private static final int INFO_STATS_SIZE = 27;
    private static final int INFO_STATS_ROW_START = 9;
    private static final int INFO_STATS_ROW_WIDTH = 9;
    private static final int INFO_STATS_BACK_SLOT = 22;

    // Umiejętności (Rzadkie Perki + Karty razem) - realnie więcej treści niż reszta, stąd
    // większy ekran; treść wypełnia sloty 0-44, przycisk powrotu wyśrodkowany w dolnym rzędzie.
    private static final int INFO_SKILLS_SIZE = 54;
    private static final int INFO_SKILLS_CONTENT_SLOTS = 45;
    private static final int INFO_SKILLS_BACK_SLOT = 49;

    // Kombinacje - osobny mały ekran: rząd bloczków (wyśrodkowany) + rząd podświetlenia pod spodem.
    private static final int INFO_SYNERGY_SIZE = 27;
    private static final int INFO_SYNERGY_ROW_START = 9;
    private static final int INFO_SYNERGY_ROW_WIDTH = 9;
    private static final int INFO_SYNERGY_HIGHLIGHT_ROW_START = 18;
    private static final int INFO_SYNERGY_BACK_SLOT = 22;

    protected int hubSize;
    private int slotToolIcon;
    private int slotOffer;
    private int slotClose;
    private int slotInfo;

    protected final Plugin plugin;
    protected final EconomyService economyService;
    protected final String toolType;
    protected final String displayName;
    protected final List<SkillBranch> branches;
    protected final List<RarePerk> rarePerks;
    protected final List<Synergy> synergies;
    protected final String hubConfigFileName;

    protected final NamespacedKey keyType;
    protected final NamespacedKey keyOwner;
    protected final NamespacedKey pkLevel;
    protected final NamespacedKey pkExp;
    protected final NamespacedKey pkTier;
    protected final NamespacedKey pkCardCounts;
    protected final NamespacedKey pkRare;
    protected final NamespacedKey pkPendingOffer;
    protected final NamespacedKey pkPendingOfferQueue;

    /** @param branches musi mieć DOKŁADNIE 3 elementy - czysto porządkowe (patrz SkillBranch). */
    protected ToolSkillManager(Plugin plugin, EconomyService economyService, String toolType, String displayName,
                                List<SkillBranch> branches, List<RarePerk> rarePerks, String hubConfigFileName) {
        this(plugin, economyService, toolType, displayName, branches, rarePerks, List.of(), hubConfigFileName);
    }

    /** @param branches musi mieć DOKŁADNIE 3 elementy - czysto porządkowe (patrz SkillBranch). */
    protected ToolSkillManager(Plugin plugin, EconomyService economyService, String toolType, String displayName,
                                List<SkillBranch> branches, List<RarePerk> rarePerks, List<Synergy> synergies,
                                String hubConfigFileName) {
        if (branches.size() != 3) {
            throw new IllegalArgumentException("ToolSkillManager wymaga dokładnie 3 gałęzi, dostano: " + branches.size());
        }
        this.plugin = plugin;
        this.economyService = economyService;
        this.toolType = toolType;
        this.displayName = displayName;
        this.branches = branches;
        this.rarePerks = rarePerks;
        this.synergies = synergies;
        this.hubConfigFileName = hubConfigFileName;

        this.keyType = new NamespacedKey(plugin, "tool_type");
        this.keyOwner = new NamespacedKey(plugin, "tool_owner");
        this.pkLevel = new NamespacedKey(plugin, "pk_level");
        this.pkExp = new NamespacedKey(plugin, "pk_exp");
        this.pkTier = new NamespacedKey(plugin, "pk_tier");
        this.pkCardCounts = new NamespacedKey(plugin, "pk_card_counts");
        this.pkRare = new NamespacedKey(plugin, "pk_rare");
        this.pkPendingOffer = new NamespacedKey(plugin, "pk_pending_offer");
        this.pkPendingOfferQueue = new NamespacedKey(plugin, "pk_pending_offer_queue");

        wczytajUkladHuba();
    }

    // ==================================================== Konfiguracja huba ====

    private void wczytajUkladHuba() {
        File plik = new File(plugin.getDataFolder(), hubConfigFileName);
        if (!plik.exists()) {
            plugin.saveResource(hubConfigFileName, false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);

        hubSize = clampSize(cfg.getInt("rozmiar", DEFAULT_HUB_SIZE));
        slotToolIcon = clampSlot(cfg.getInt("sloty.narzedzie", DEFAULT_TOOL_ICON_SLOT));
        slotOffer = clampSlot(cfg.getInt("sloty.wybor_karty", DEFAULT_OFFER_SLOT));
        slotClose = clampSlot(cfg.getInt("sloty.wyjdz", DEFAULT_CLOSE_SLOT));
        slotInfo = clampSlot(cfg.getInt("sloty.info", DEFAULT_INFO_SLOT));
    }

    protected int clampSize(int rozmiar) {
        int ograniczony = Math.max(9, Math.min(54, rozmiar));
        return Math.min(54, ((ograniczony + 8) / 9) * 9);
    }

    protected int clampSlot(int slot) {
        return Math.max(0, Math.min(slot, hubSize - 1));
    }

    // ======================================================== Progresja ====

    /** [DEBUG] Dodaje `levels` poziomów trzymanemu narzędziu - pod /addlvl. */
    public void debugAddLevels(Player player, ItemStack item, int levels) {
        ensureInitialized(item);
        int startLevel = item.getItemMeta().getPersistentDataContainer().getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int target = Math.min(MAX_LEVEL, startLevel + levels);
        while (item.getItemMeta().getPersistentDataContainer().getOrDefault(pkLevel, PersistentDataType.INTEGER, 1) < target) {
            addExp(player, item);
        }
    }

    protected void addExp(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        if (level >= MAX_LEVEL) return;

        int tierBefore = tierOf(pdc);
        int exp = pdc.getOrDefault(pkExp, PersistentDataType.INTEGER, 0) + 1;

        boolean leveledUp = false;
        boolean offerRolled = false;
        boolean tierUp = false;

        if (exp >= expPerLevel(tierBefore)) {
            exp = 0;
            level++;
            leveledUp = true;

            if (level % offerCadence(pdc) == 0) {
                offerRolled = rollCardOffer(pdc);
            }
            int tierAfter = tierForLevel(level);
            if (tierAfter != tierBefore) {
                pdc.set(pkTier, PersistentDataType.INTEGER, tierAfter);
                tierUp = true;
            }
        }

        pdc.set(pkLevel, PersistentDataType.INTEGER, level);
        pdc.set(pkExp, PersistentDataType.INTEGER, exp);
        item.setItemMeta(meta);

        refreshDisplay(item);

        if (leveledUp) {
            String nazwa = displayNameFor(pdc);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            player.sendActionBar(Component.text(nazwa + ": poziom " + level, NamedTextColor.AQUA));

            if (tierUp) {
                int tier = pdc.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
                player.showTitle(Title.title(
                        Component.text(nazwa + " Ewoluował!", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("Nowy tier: " + tierName(tier), NamedTextColor.YELLOW)
                ));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            if (offerRolled) {
                player.sendMessage(Component.text("★ Nowy poziom - masz kartę do wyboru! (Shift + PPM)",
                        NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
            }
            if (level >= MAX_LEVEL) {
                player.sendMessage(Component.text("★ " + nazwa + " osiągnęła maksymalny poziom (" + MAX_LEVEL + ")!",
                        NamedTextColor.GOLD, TextDecoration.BOLD));
            }
        }
    }

    /**
     * @return true, jeśli gracz dostał nową rundę wyboru (od razu albo w kolejce) - false
     * tylko wtedy, gdy WSZYSTKIE karty (łącznie z legendarnymi) są już zmaksowane/wykupione.
     */
    private boolean rollCardOffer(PersistentDataContainer pdc) {
        String pending = pdc.getOrDefault(pkPendingOffer, PersistentDataType.STRING, "");
        if (!pending.isEmpty()) {
            int queued = pdc.getOrDefault(pkPendingOfferQueue, PersistentDataType.INTEGER, 0);
            pdc.set(pkPendingOfferQueue, PersistentDataType.INTEGER, queued + 1);
            return true;
        }
        return offerNewCardChoice(pdc);
    }

    private boolean offerNewCardChoice(PersistentDataContainer pdc) {
        List<String> offer = drawOffer(pdc);
        if (offer.isEmpty()) return false;
        pdc.set(pkPendingOffer, PersistentDataType.STRING, String.join(",", offer));
        return true;
    }

    /** Losuje do 4 RÓŻNYCH kart: rzadkość ważona (patrz Rarity), konkretna karta w tierze - równomiernie. */
    private List<String> drawOffer(PersistentDataContainer pdc) {
        List<String> offer = new ArrayList<>();
        Set<String> chosen = new LinkedHashSet<>();
        int attempts = 0;
        int totalWeight = 0;
        for (Rarity r : Rarity.values()) totalWeight += r.waga;

        while (offer.size() < 4 && attempts < 60) {
            attempts++;
            Rarity tier = weightedRandomTier(totalWeight);
            String candidate = randomAvailableCardOfTier(pdc, tier, chosen);
            if (candidate == null) continue;
            offer.add(candidate);
            chosen.add(candidate);
        }
        return offer;
    }

    private Rarity weightedRandomTier(int totalWeight) {
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (Rarity r : Rarity.values()) {
            cumulative += r.waga;
            if (roll < cumulative) return r;
        }
        return Rarity.COMMON;
    }

    private String randomAvailableCardOfTier(PersistentDataContainer pdc, Rarity tier, Set<String> exclude) {
        int currentTier = tierOf(pdc);
        List<String> candidates = new ArrayList<>();
        if (tier == Rarity.LEGENDARY) {
            Set<String> owned = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
            for (RarePerk perk : rarePerksFor(pdc)) {
                if (perk.minTier() <= currentTier && !owned.contains(perk.id()) && !exclude.contains(perk.id())) candidates.add(perk.id());
            }
        } else {
            for (SkillBranch b : branchesFor(pdc)) {
                for (SkillCard c : b.cards()) {
                    if (c.rarity() == tier && c.minTier() <= currentTier && !exclude.contains(c.id()) && cardCountOf(pdc, c.id()) < c.maxStacks()) {
                        candidates.add(c.id());
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    protected void ensureInitialized(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(pkLevel, PersistentDataType.INTEGER)) return;

        pdc.set(pkLevel, PersistentDataType.INTEGER, 1);
        pdc.set(pkExp, PersistentDataType.INTEGER, 0);
        pdc.set(pkTier, PersistentDataType.INTEGER, 0);
        pdc.set(pkCardCounts, PersistentDataType.STRING, "");
        pdc.set(pkRare, PersistentDataType.STRING, "");
        pdc.set(pkPendingOffer, PersistentDataType.STRING, "");
        pdc.set(pkPendingOfferQueue, PersistentDataType.INTEGER, 0);
        initializeToolSpecific(pdc);
        item.setItemMeta(meta);

        refreshDisplay(item);
    }

    /** Hook: dodatkowa inicjalizacja PDC specyficzna dla narzędzia (np. kilof: pk_bruk_on) - domyślnie brak. */
    protected void initializeToolSpecific(PersistentDataContainer pdc) {
    }

    protected void refreshDisplay(ItemStack item) {
        ItemMeta metaBefore = item.getItemMeta();
        PersistentDataContainer pdcBefore = metaBefore.getPersistentDataContainer();
        int tier = pdcBefore.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
        Material correctMaterial = materialOverride(pdcBefore);
        if (correctMaterial == null) correctMaterial = materialForTier(tier);
        if (item.getType() != correctMaterial) {
            item.setType(correctMaterial);
        }

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int exp = pdc.getOrDefault(pkExp, PersistentDataType.INTEGER, 0);
        String pending = pdc.getOrDefault(pkPendingOffer, PersistentDataType.STRING, "");
        int queuedOffers = pdc.getOrDefault(pkPendingOfferQueue, PersistentDataType.INTEGER, 0);

        meta.displayName(Component.text(displayNameFor(pdc) + " ", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text("[Poziom " + level + "]", NamedTextColor.YELLOW, TextDecoration.BOLD)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Tier: " + tierName(tier), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (level >= MAX_LEVEL) {
            lore.add(Component.text("Postęp: MAKSYMALNY POZIOM", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        } else {
            int expNeeded = expPerLevel(tier);
            lore.add(Component.text("Postęp: " + expBar(exp, expNeeded) + " " + exp + "/" + expNeeded, NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));
        }
        if (!pending.isEmpty()) {
            lore.add(Component.empty());
            lore.add(Component.text("★ Masz kartę do wyboru!" + (queuedOffers > 0 ? " (+" + queuedOffers + " w kolejce)" : ""),
                    NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Prywatne narzędzie", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Shift + PPM - otwórz drzewko umiejętności", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        if (meta instanceof Damageable damageable) {
            short maxDurability = item.getType().getMaxDurability();
            double progress = level >= MAX_LEVEL ? 1.0 : (double) exp / expPerLevel(tier);
            int damage = (int) Math.round(maxDurability - maxDurability * progress);
            damageable.setDamage(Math.max(0, Math.min(damage, maxDurability - 1)));
        }

        syncToolSpecificStats(meta, pdc);
        item.setItemMeta(meta);
    }

    private String expBar(int exp, int expNeeded) {
        int filled = (int) Math.round(10.0 * exp / expNeeded);
        return "▮".repeat(Math.max(0, filled)) + "▯".repeat(Math.max(0, 10 - filled));
    }

    protected int expPerLevel(int tier) {
        return EXP_PER_LEVEL_BY_TIER[Math.max(0, Math.min(tier, EXP_PER_LEVEL_BY_TIER.length - 1))];
    }

    protected int tierForLevel(int level) {
        return Math.min((level - 1) / LEVELS_PER_TIER, MAX_TIER);
    }

    protected String rzymskie(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    /** Materiał narzędzia dla danego tieru (0=drewno .. 4=netheryt) - dostarcza podklasa. */
    protected abstract Material materialForTier(int tier);

    /**
     * Hook: Material NIEZALEŻNY od tieru (np. kilof: stały Material per typ - Pieniężny/
     * Wydajnościowy/Szczęścia/Obszarowy, patrz PickaxeType). Zwraca null domyślnie - wtedy
     * refreshDisplay/openHub wracają do materialForTier(tier) jak dotychczas (siekiera/
     * motyka/miecz/łopata bez zmian). Podklasa nadpisuje TYLKO jeśli identyczność narzędzia
     * nie jest już tierowa.
     */
    protected Material materialOverride(PersistentDataContainer pdc) {
        return null;
    }

    /**
     * Hook: nazwa NIEZALEŻNA od pdc, do których wraca się domyślnie (null = stała nazwa
     * z konstruktora, jak dotychczas). Kilof nadpisuje, żeby każdy PickaxeType miał
     * WŁASNĄ nazwę ("Kilof Wydajnościowy" zamiast samego "Kilof") - inaczej wszystkie
     * typy wyglądają identycznie w ekwipunku/tytułach GUI.
     */
    protected String displayNameFor(PersistentDataContainer pdc) {
        return displayName;
    }

    /**
     * Hook: tytuł okna huba - domyślnie zwykły tekstowy tytuł (jak dotychczas). Kilof
     * nadpisuje na tytuł złożony z niestandardowej czcionki (patrz hubUsesCustomBackground)
     * - dwa niewidzialne/obrazkowe znaki zamiast czytelnego tekstu, renderujące duży
     * obrazek tła zamiast zwykłego napisu.
     */
    protected Component hubTitleFor(PersistentDataContainer pdc, String nazwa) {
        return Component.text(nazwa + " — Umiejętności", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD);
    }

    /**
     * Hook: czy hub ma w pełni customowe tło (obrazek renderowany przez tytuł zamiast
     * zwykłego szkła w tle) - domyślnie false (siekiera/motyka/miecz bez zmian). Gdy true,
     * openHub NIE wypełnia tła szkłem (GuiUtils.fillBackground) - obrazek z hubTitleFor
     * sam pełni rolę tła, a puste sloty zostają prawdziwie puste (AIR), żeby przez nie
     * "prześwitywał" obrazek.
     */
    protected boolean hubUsesCustomBackground(PersistentDataContainer pdc) {
        return false;
    }

    /**
     * Hook: co ile poziomów gracz dostaje ofertę karty do wyboru - domyślnie 1 (każdy
     * poziom, jak dotychczas). Kilof nadpisuje na 5 (auto-staty co level, wybór co 5 lvl).
     */
    protected int offerCadence(PersistentDataContainer pdc) {
        return 1;
    }

    /** Hook: pula gałęzi/kart do losowania ofert - domyślnie stała pula z konstruktora. Kilof zwraca pulę WŁAŚCIWĄ dla pk_type danego przedmiotu. */
    protected List<SkillBranch> branchesFor(PersistentDataContainer pdc) {
        return branches;
    }

    /** Jak {@link #branchesFor(PersistentDataContainer)}, ale dla rzadkich (legendarnych) perków. */
    protected List<RarePerk> rarePerksFor(PersistentDataContainer pdc) {
        return rarePerks;
    }

    protected String tierName(int tier) {
        return switch (tier) {
            case 0 -> "Drewno";
            case 1 -> "Kamień";
            case 2 -> "Żelazo";
            case 3 -> "Diament";
            default -> "Netherite";
        };
    }

    protected int tierOf(PersistentDataContainer pdc) {
        return pdc.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
    }

    /**
     * Synchronizuje realne enchanty/atrybuty specyficzne dla narzędzia (np. miecz:
     * Ostrość/Grabież/szybkość ataku) - treść w 100% zależy od narzędzia, więc to
     * obowiązkowy hook do zaimplementowania w podklasie.
     */
    protected abstract void syncToolSpecificStats(ItemMeta meta, PersistentDataContainer pdc);

    /** Treść ikonki Statystyk w hubie - realne wartości specyficzne dla narzędzia. */
    protected abstract List<Component> statsLore(PersistentDataContainer pdc);

    /**
     * Statystyki jako fizyczne bloczki (menu Informacje) zamiast tekstu w opisie - domyślnie
     * pojedyncza ikonka z tekstową listą z statsLore (fallback dla narzędzi, które nie mają
     * jeszcze własnego rozbicia na bloczki). Podklasa może nadpisać, żeby zwrócić osobny
     * ItemStack na każdą statystykę (np. kilof: Wydajność/Fortuna/Prędkość/Aura Pośpiechu),
     * z ilością w stosie odzwierciedlającą poziom danej statystyki.
     */
    protected List<ItemStack> statsBlocks(PersistentDataContainer pdc) {
        return List.of(statsIcon(pdc));
    }

    // ==================================================== Karty - odczyt ====

    protected SkillCard findCard(PersistentDataContainer pdc, String id) {
        for (SkillBranch b : branchesFor(pdc)) {
            for (SkillCard c : b.cards()) {
                if (c.id().equals(id)) return c;
            }
        }
        return null;
    }

    protected RarePerk findRarePerk(PersistentDataContainer pdc, String id) {
        for (RarePerk perk : rarePerksFor(pdc)) {
            if (perk.id().equals(id)) return perk;
        }
        return null;
    }

    // =================================================== Kombinacje kart ====
    // Bonusy emergentne z jednoczesnego zainwestowania w kilka konkretnych kart (patrz
    // Synergy) - stan liczony na żywo z liczników kart, bez osobnej flagi w PDC (skoro
    // karty nigdy nie tracą poziomów, spełnienie wymagań jest z definicji trwałe).

    /** Czy dana kombinacja jest aktywna (wszystkie jej wymagania spełnione naraz) - pod efekty w podklasie. */
    protected boolean hasSynergy(PersistentDataContainer pdc, String synergyId) {
        for (Synergy s : synergies) {
            if (s.id().equals(synergyId)) return synergyRequirementsMet(pdc, s);
        }
        return false;
    }

    private boolean synergyRequirementsMet(PersistentDataContainer pdc, Synergy synergy) {
        for (Map.Entry<String, Integer> req : synergy.wymagania().entrySet()) {
            if (cardCountOf(pdc, req.getKey()) < req.getValue()) return false;
        }
        return true;
    }

    private Set<String> activeSynergyIds(PersistentDataContainer pdc) {
        Set<String> active = new LinkedHashSet<>();
        for (Synergy s : synergies) {
            if (synergyRequirementsMet(pdc, s)) active.add(s.id());
        }
        return active;
    }

    private void announceSynergyUnlock(Player player, String synergyId) {
        for (Synergy s : synergies) {
            if (!s.id().equals(synergyId)) continue;
            player.sendMessage(Component.text("✦ Odkryto kombinację: " + s.displayName() + "!", NamedTextColor.GOLD, TextDecoration.BOLD));
            player.showTitle(Title.title(
                    Component.text("Nowa Kombinacja!", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text(s.displayName(), NamedTextColor.YELLOW)
            ));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            return;
        }
    }

    private Map<String, Integer> parseCardCounts(String csv) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (csv != null && !csv.isEmpty()) {
            for (String entry : csv.split(",")) {
                int sep = entry.indexOf(':');
                if (sep <= 0) continue;
                try {
                    map.put(entry.substring(0, sep), Integer.parseInt(entry.substring(sep + 1)));
                } catch (NumberFormatException ignored) {
                    // uszkodzony wpis - pomijamy, nie wywalamy całego stanu gracza
                }
            }
        }
        return map;
    }

    private String serializeCardCounts(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /** Aktualny poziom (liczba stacków) danej stackowalnej karty - 0, jeśli gracz jej nie ma. */
    protected int cardCountOf(PersistentDataContainer pdc, String cardId) {
        return parseCardCounts(pdc.getOrDefault(pkCardCounts, PersistentDataType.STRING, "")).getOrDefault(cardId, 0);
    }

    protected boolean hasRare(PersistentDataContainer pdc, String rareId) {
        String csv = pdc.getOrDefault(pkRare, PersistentDataType.STRING, "");
        if (csv.isEmpty()) return false;
        for (String id : csv.split(",")) {
            if (id.equals(rareId)) return true;
        }
        return false;
    }

    protected Set<String> csvToSet(String csv) {
        if (csv == null || csv.isEmpty()) return new LinkedHashSet<>();
        return new LinkedHashSet<>(Arrays.asList(csv.split(",")));
    }

    private void grantCard(PersistentDataContainer pdc, String cardId) {
        SkillCard card = findCard(pdc, cardId);
        if (card != null) {
            Map<String, Integer> counts = parseCardCounts(pdc.getOrDefault(pkCardCounts, PersistentDataType.STRING, ""));
            counts.merge(cardId, 1, Integer::sum);
            pdc.set(pkCardCounts, PersistentDataType.STRING, serializeCardCounts(counts));
        } else {
            Set<String> owned = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
            owned.add(cardId);
            pdc.set(pkRare, PersistentDataType.STRING, String.join(",", owned));
        }
    }

    protected boolean isOwnedTool(ItemStack item, Player player) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!toolType.equals(pdc.get(keyType, PersistentDataType.STRING))) return false;
        String owner = pdc.get(keyOwner, PersistentDataType.STRING);
        return owner != null && owner.equals(player.getUniqueId().toString());
    }

    // ===================================================== Pasywna aura ====
    // Opcjonalny mechanizm (jak Aura Pośpiechu kilofa) - domyślnie wyłączony (null),
    // podklasa może nadpisać oba hooki, żeby któraś karta dawała stały efekt mikstury.

    protected PotionEffectType auraEffectType() {
        return null;
    }

    /** Amplifier aury (0 = poziom I) albo null, jeśli odpowiednia karta nie jest wykupiona. */
    protected Integer auraAmplifierFor(PersistentDataContainer pdc) {
        return null;
    }

    // =============================================== Rozszerzenia huba ====
    // Opcjonalne hooki dla narzędzi z dodatkową ikonką/przełącznikiem w hubie poza
    // standardowym zestawem (np. kilof: przełącznik bonusu z bruku) - domyślnie brak.

    /** Hook: dodatkowe ikonki w hubie poza standardowym zestawem - domyślnie brak. */
    protected void populateExtraHubIcons(Inventory gui, PersistentDataContainer pdc) {
    }

    /** Hook: obsługa kliknięcia w dodatkową ikonkę z populateExtraHubIcons - domyślnie brak (false = nieobsłużone). */
    protected boolean handleExtraHubClick(Player player, ItemStack tool, int slot) {
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        syncAura(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand());
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        ItemStack incoming = event.getPlayer().getInventory().getItem(event.getNewSlot());
        syncAura(event.getPlayer(), incoming);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        syncAura(event.getPlayer(), event.getMainHandItem());
    }

    private void syncAura(Player player, ItemStack candidateMainHand) {
        PotionEffectType effectType = auraEffectType();
        if (effectType == null) return;

        Integer amplifier = null;
        if (isOwnedTool(candidateMainHand, player)) {
            ItemMeta meta = candidateMainHand.getItemMeta();
            amplifier = auraAmplifierFor(meta.getPersistentDataContainer());
        }

        PotionEffect current = player.getPotionEffect(effectType);
        boolean oursActive = current != null && current.getDuration() > 50_000;

        if (amplifier != null) {
            player.addPotionEffect(new PotionEffect(effectType, AURA_DURATION_TICKS, amplifier, true, false, false));
        } else if (oursActive) {
            player.removePotionEffect(effectType);
        }
    }

    // ==================================================== Pomocnicze fx ====

    protected void payBonus(Player player, double amount) {
        economyService.dodajKase(player.getUniqueId(), amount);
        player.sendActionBar(Component.text("+" + formatMoney(amount) + " $", NamedTextColor.GREEN));
    }

    protected void spawnXp(Location loc, int amount) {
        loc.getWorld().spawn(loc, ExperienceOrb.class, orb -> orb.setExperience(amount));
    }

    protected void pullNearbyDrops(Player player, Location center) {
        for (Entity e : center.getWorld().getNearbyEntities(center, 4, 4, 4)) {
            if (e instanceof Item dropped) {
                Vector dir = player.getLocation().add(0, 1, 0).toVector().subtract(dropped.getLocation().toVector());
                if (dir.lengthSquared() > 0.01) {
                    dropped.setVelocity(dropped.getVelocity().add(dir.normalize().multiply(0.25)));
                }
            }
        }
    }

    protected void dropCopy(Block block, ItemStack tool) {
        for (ItemStack drop : block.getDrops(tool)) {
            block.getWorld().dropItemNaturally(block.getLocation(), drop);
        }
    }

    protected String formatMoney(double amount) {
        return amount == Math.floor(amount) ? String.valueOf((long) amount) : String.format("%.1f", amount);
    }

    protected String formatPercent(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.format("%.2f", value);
    }

    /**
     * Prawdopodobieństwo przynajmniej jednego trafienia przy `count` NIEZALEŻNYCH próbach
     * z szansą `perStackChance` każda - w odróżnieniu od naiwnego `count * perStackChance`
     * nigdy nie przekracza 1.0 (asymptotycznie się do niej zbliża), więc kolejne poziomy
     * karty nigdy nie stają się "bezużyteczne" po przekroczeniu 100% sumy.
     */
    protected double stackedChance(int count, double perStackChance) {
        if (count <= 0 || perStackChance <= 0) return 0;
        if (perStackChance >= 1) return 1;
        return 1 - Math.pow(1 - perStackChance, count);
    }

    /**
     * Łączy kilka NIEZALEŻNYCH szans w jedno prawdopodobieństwo "co najmniej jedna się
     * uda" (dopełnienie iloczynu dopełnień) - tak jak stackedChance, NIGDY nie przekracza
     * 1.0, więc suma wielu różnych kart tego samego typu bonusu nie robi się deterministyczna
     * po przekroczeniu 100%. Zastępuje dawne naiwne sumowanie (`a + b + c + ...`).
     */
    protected double combineChances(double... chances) {
        double missAll = 1.0;
        for (double c : chances) {
            missAll *= (1 - Math.max(0, Math.min(1, c)));
        }
        return 1 - missAll;
    }

    /** Poniżej tego ułamka nominalnej mocy nie spada nawet poziom 1 - patrz procScale(). */
    private static final double MIN_PROC_SCALE = 0.25;

    /**
     * Mnożnik (MIN_PROC_SCALE..1.0) skalujący WSZYSTKIE losowe "szanse na bonus" (drop/
     * xp/pieniądze z kart) razem z ogólnym postępem narzędzia - poziom 1-100 koduje
     * jednocześnie tier (patrz tierForLevel), więc jeden ciągły mnożnik pokrywa zarówno
     * "tier" jak i "poziom" naraz. Bez tego drewniany kilof, któremu (rzadko, ale możliwe)
     * trafi się od razu kilka mocnych kart, byłby tak samo silny w farmieniu bonusów jak
     * w pełni rozwinięty netherytowy - card-level dawałby 100% swojej nominalnej mocy od
     * pierwszego wyboru. Świadomie NIE dotyczy deterministycznych efektów (prawdziwe
     * enchanty Fortuna/Wydajność, % prędkości z atrybutów) - tam inwestycja w kartę i tak
     * jest jedynym warunkiem, skalowanie "częściowej Fortuny" nie ma sensu.
     */
    protected double procScale(PersistentDataContainer pdc) {
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        double progress = (double) (level - 1) / (MAX_LEVEL - 1);
        return MIN_PROC_SCALE + (1 - MIN_PROC_SCALE) * Math.max(0, Math.min(1, progress));
    }

    /** Wygodny skrót na `rawChance * procScale(pdc)` - patrz procScale(). */
    protected double scaledChance(PersistentDataContainer pdc, double rawChance) {
        return rawChance * procScale(pdc);
    }

    // ============================================================= GUI ====

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getPlayer().isSneaking()) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(item, player)) return;

        event.setCancelled(true);
        ensureInitialized(item);
        openHub(player, item);
    }

    protected void openHub(Player player, ItemStack tool) {
        ItemMeta meta = tool.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int tier = pdc.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
        String pending = pdc.getOrDefault(pkPendingOffer, PersistentDataType.STRING, "");
        int queuedOffers = pdc.getOrDefault(pkPendingOfferQueue, PersistentDataType.INTEGER, 0);

        String nazwa = displayNameFor(pdc);
        HubHolder holder = new HubHolder(this);
        Inventory gui = Bukkit.createInventory(holder, hubSize, hubTitleFor(pdc, nazwa));
        holder.inventory = gui;
        if (!hubUsesCustomBackground(pdc)) {
            GuiUtils.fillBackground(gui, Material.PURPLE_STAINED_GLASS_PANE);
        }

        Material toolIconMaterial = materialOverride(pdc);
        if (toolIconMaterial == null) toolIconMaterial = materialForTier(tier);
        gui.setItem(slotToolIcon, GuiUtils.namedItem(toolIconMaterial,
                Component.text(nazwa + " [Poziom " + level + "]", NamedTextColor.AQUA, TextDecoration.BOLD),
                Component.text("Tier: " + tierName(tier), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));

        gui.setItem(slotInfo, GuiUtils.namedItem(Material.BEACON,
                Component.text("Informacje", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(synergies.isEmpty() ? "Statystyki i umiejętności." : "Statystyki, umiejętności i kombinacje.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Kliknij, aby otworzyć.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        populateExtraHubIcons(gui, pdc);

        if (!pending.isEmpty()) {
            gui.setItem(slotOffer, GuiUtils.namedItem(Material.NETHER_STAR,
                    Component.text("★ Nowa karta do wyboru!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                    Component.text("Kliknij, aby wybrać jedną z ofiarowanych", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("ulepszeń dla swojego narzędzia.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text(queuedOffers > 0 ? "+" + queuedOffers + " kolejnych w kolejce" : " ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        } else {
            gui.setItem(slotOffer, GuiUtils.namedItem(Material.GRAY_DYE,
                    Component.text("Brak oferty", NamedTextColor.DARK_GRAY, TextDecoration.BOLD),
                    Component.text("Wróć po zdobyciu kolejnego poziomu.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        }

        gui.setItem(slotClose, GuiUtils.namedItem(Material.BARRIER, Component.text("Zamknij", NamedTextColor.RED, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private ItemStack statsIcon(PersistentDataContainer pdc) {
        List<Component> lore = statsLore(pdc);
        return GuiUtils.namedItem(Material.KNOWLEDGE_BOOK,
                Component.text("Statystyki", NamedTextColor.GOLD, TextDecoration.BOLD),
                lore.toArray(Component[]::new));
    }

    // ==================================================== Menu Informacje ====
    // Otwierane z beacona w hubie (patrz slotInfo) - NIE jest jednym wielkim ekranem:
    // to mały, symetryczny launcher (3 książki/beacon), z którego dopiero kliknięcie
    // otwiera właściwy, osobno rozmiarowany ekran (Statystyki/Umiejętności/Kombinacje).
    // Wszystko jako fizyczne bloczki (nie tekst w opisie) - ilość w stosie = poziom
    // danej statystyki/karty, gdzie to ma sens.

    protected void openInfo(Player player, ItemStack tool) {
        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
        InfoLauncherHolder holder = new InfoLauncherHolder(this);
        // Bez nazwy narzędzia w tytule (kilof: nazwy typów jak "Kilof Wydajnościowy" + ten
        // dopisek wychodziły poza ramkę GUI - tytuł kontenera, w odróżnieniu od dymka
        // podpowiedzi przy najechaniu, jest realnie ograniczony szerokością okna).
        Inventory gui = Bukkit.createInventory(holder, INFO_LAUNCHER_SIZE, Component.text("Informacje", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.BLACK_STAINED_GLASS_PANE);

        gui.setItem(INFO_LAUNCHER_STATS_SLOT, GuiUtils.namedItem(Material.KNOWLEDGE_BOOK,
                Component.text("Statystyki", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Realne efekty aktualnie działające na narzędziu.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        gui.setItem(INFO_LAUNCHER_SKILLS_SLOT, GuiUtils.namedItem(Material.ENCHANTED_BOOK,
                Component.text("Umiejętności", NamedTextColor.AQUA, TextDecoration.BOLD),
                Component.text("Wykupione karty i rzadkie perki.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        // Kombinacje - tylko jeśli narzędzie w ogóle ma zdefiniowane synergie (kilof: pusta lista, patrz PickaxeSkillManager).
        if (!synergies.isEmpty()) {
            gui.setItem(INFO_LAUNCHER_SYNERGY_SLOT, GuiUtils.namedItem(Material.BEACON,
                    Component.text("Kombinacje", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Bonusy za jednoczesne zainwestowanie w kilka kart.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        }

        gui.setItem(INFO_LAUNCHER_BACK_SLOT, GuiUtils.namedItem(Material.ARROW, Component.text("« Wróć", NamedTextColor.GOLD, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private void openInfoStats(Player player, ItemStack tool) {
        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();

        InfoStatsHolder holder = new InfoStatsHolder(this);
        Inventory gui = Bukkit.createInventory(holder, INFO_STATS_SIZE, Component.text("Statystyki", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.BLACK_STAINED_GLASS_PANE);

        List<ItemStack> statBlocks = statsBlocks(pdc);
        int start = centeredRowStart(INFO_STATS_ROW_START, INFO_STATS_ROW_WIDTH, statBlocks.size());
        for (int i = 0; i < statBlocks.size(); i++) {
            gui.setItem(start + i, statBlocks.get(i));
        }

        gui.setItem(INFO_STATS_BACK_SLOT, GuiUtils.namedItem(Material.ARROW, Component.text("« Wróć", NamedTextColor.GOLD, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private void openInfoSkills(Player player, ItemStack tool) {
        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();

        InfoSkillsHolder holder = new InfoSkillsHolder(this);
        Inventory gui = Bukkit.createInventory(holder, INFO_SKILLS_SIZE, Component.text("Umiejętności", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.BLACK_STAINED_GLASS_PANE);

        List<ItemStack> content = new ArrayList<>(rarePerkBlocks(pdc));
        content.addAll(ownedCardBlocks(pdc));
        for (int i = 0; i < content.size() && i < INFO_SKILLS_CONTENT_SLOTS; i++) {
            gui.setItem(i, content.get(i));
        }
        if (content.isEmpty()) {
            gui.setItem(INFO_SKILLS_CONTENT_SLOTS / 2, GuiUtils.namedItem(Material.GRAY_DYE,
                    Component.text("Brak - zdobądź pierwszy poziom!", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        }

        gui.setItem(INFO_SKILLS_BACK_SLOT, GuiUtils.namedItem(Material.ARROW, Component.text("« Wróć", NamedTextColor.GOLD, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private void openInfoSynergies(Player player, ItemStack tool, String selectedSynergyId) {
        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();

        InfoSynergyHolder holder = new InfoSynergyHolder(this, selectedSynergyId);
        Inventory gui = Bukkit.createInventory(holder, INFO_SYNERGY_SIZE, Component.text("Kombinacje", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.BLACK_STAINED_GLASS_PANE);

        int start = centeredRowStart(INFO_SYNERGY_ROW_START, INFO_SYNERGY_ROW_WIDTH, synergies.size());
        for (int i = 0; i < synergies.size(); i++) {
            Synergy s = synergies.get(i);
            gui.setItem(start + i, synergyBlock(pdc, s, s.id().equals(selectedSynergyId)));
        }
        if (synergies.isEmpty()) {
            gui.setItem(INFO_SYNERGY_ROW_START + INFO_SYNERGY_ROW_WIDTH / 2, GuiUtils.namedItem(Material.GRAY_DYE,
                    Component.text("Brak kombinacji dla tego narzędzia.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        }

        if (selectedSynergyId != null) {
            Synergy selected = findSynergy(selectedSynergyId);
            if (selected != null) {
                Material highlightGlass = glassForColor(selected.highlightColor());
                for (int slot = INFO_SYNERGY_HIGHLIGHT_ROW_START; slot < INFO_SYNERGY_HIGHLIGHT_ROW_START + 9; slot++) {
                    if (slot == INFO_SYNERGY_BACK_SLOT) continue;
                    gui.setItem(slot, GuiUtils.namedItem(highlightGlass,
                            Component.text(selected.displayName(), selected.highlightColor(), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)));
                }
            }
        }

        gui.setItem(INFO_SYNERGY_BACK_SLOT, GuiUtils.namedItem(Material.ARROW, Component.text("« Wróć", NamedTextColor.GOLD, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    /** Symetryczne wyśrodkowanie `count` bloczków w rzędzie szerokości `rowWidth` - jeśli nie mieszczą się, zaczyna od początku rzędu. */
    private int centeredRowStart(int rowStart, int rowWidth, int count) {
        return rowStart + Math.max(0, (rowWidth - count) / 2);
    }

    private List<ItemStack> rarePerkBlocks(PersistentDataContainer pdc) {
        Set<String> owned = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        List<ItemStack> blocks = new ArrayList<>();
        for (String id : owned) {
            RarePerk perk = findRarePerk(pdc, id);
            if (perk == null) continue;
            ItemStack icon = GuiUtils.namedItem(perk.icon(),
                    Component.text(perk.displayName(), Rarity.LEGENDARY.color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                    Component.text(perk.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text(Rarity.LEGENDARY.label, Rarity.LEGENDARY.color).decoration(TextDecoration.ITALIC, false));
            ItemMeta iconMeta = icon.getItemMeta();
            iconMeta.setEnchantmentGlintOverride(true);
            icon.setItemMeta(iconMeta);
            blocks.add(icon);
        }
        return blocks;
    }

    private List<ItemStack> ownedCardBlocks(PersistentDataContainer pdc) {
        List<ItemStack> blocks = new ArrayList<>();
        for (SkillBranch b : branchesFor(pdc)) {
            for (SkillCard c : b.cards()) {
                int count = cardCountOf(pdc, c.id());
                if (count <= 0) continue;
                ItemStack icon = GuiUtils.namedItem(c.icon(),
                        Component.text(c.displayName(), c.rarity().color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                        Component.text(c.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("Poziom: " + count + "/" + c.maxStacks(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                        Component.text(c.rarity().label, c.rarity().color).decoration(TextDecoration.ITALIC, false));
                icon.setAmount(Math.min(64, count));
                blocks.add(icon);
            }
        }
        return blocks;
    }

    private ItemStack synergyBlock(PersistentDataContainer pdc, Synergy s, boolean selected) {
        boolean unlocked = synergyRequirementsMet(pdc, s);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(s.rarity().label, s.rarity().color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text(s.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        for (Map.Entry<String, Integer> req : s.wymagania().entrySet()) {
            SkillCard card = findCard(pdc, req.getKey());
            String name = card != null ? card.displayName() : req.getKey();
            int have = cardCountOf(pdc, req.getKey());
            int need = req.getValue();
            lore.add(Component.text(name + ": " + have + "/" + need,
                    have >= need ? NamedTextColor.GREEN : NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text(unlocked ? "✔ Odblokowana" : "○ Niekompletna",
                unlocked ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(selected ? "Kliknij, aby zdjąć podświetlenie" : "Kliknij, aby podświetlić", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));

        ItemStack icon = GuiUtils.namedItem(s.icon(), Component.text(s.displayName(), s.highlightColor(), TextDecoration.BOLD), lore.toArray(Component[]::new));
        if (unlocked) {
            ItemMeta iconMeta = icon.getItemMeta();
            iconMeta.setEnchantmentGlintOverride(true);
            icon.setItemMeta(iconMeta);
        }
        return icon;
    }

    private Synergy findSynergy(String id) {
        for (Synergy s : synergies) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }

    private void openOfferChoice(Player player, String pendingCsv) {
        PersistentDataContainer pdc = player.getInventory().getItemInMainHand().getItemMeta().getPersistentDataContainer();
        List<String> ids = Arrays.asList(pendingCsv.split(","));
        OfferHolder holder = new OfferHolder(this);
        Inventory gui = Bukkit.createInventory(holder, OFFER_HUB_SIZE, Component.text("Wybierz Ulepszenie", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.BLACK_STAINED_GLASS_PANE);

        for (int i = 0; i < ids.size() && i < OFFER_CHOICE_SLOTS.length; i++) {
            String id = ids.get(i);
            ItemStack glass = categoryGlass(pdc, id);
            gui.setItem(OFFER_CHOICE_SLOTS[i] - 9, glass);
            gui.setItem(OFFER_CHOICE_SLOTS[i], offerCardIcon(pdc, id));
            gui.setItem(OFFER_CHOICE_SLOTS[i] + 9, glass.clone());
        }

        gui.setItem(OFFER_BACK_SLOT, GuiUtils.namedItem(Material.BARRIER, Component.text("Zdecyduj później", NamedTextColor.GRAY, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    /**
     * Szybka pod ikonką oferty (slot+9, rząd niżej) - kolor odpowiada gałęzi (kategorii),
     * do której należy karta, żeby gracz od razu widział "skąd" jest dana oferta bez
     * czytania opisu. Rzadkie perki nie należą do żadnej gałęzi - dostają żółtą szybkę.
     */
    private ItemStack categoryGlass(PersistentDataContainer pdc, String id) {
        SkillBranch branch = branchOfCard(pdc, id);
        if (branch == null) {
            return GuiUtils.namedItem(Material.YELLOW_STAINED_GLASS_PANE,
                    Component.text("Kategoria: " + Rarity.LEGENDARY.label, Rarity.LEGENDARY.color).decoration(TextDecoration.ITALIC, false));
        }
        return GuiUtils.namedItem(glassForColor(branch.color()),
                Component.text("Kategoria: " + branch.displayName(), branch.color()).decoration(TextDecoration.ITALIC, false));
    }

    /** Gałąź (kategoria), do której należy karta o danym id - null, jeśli to rzadki perk (bez gałęzi). */
    private SkillBranch branchOfCard(PersistentDataContainer pdc, String id) {
        for (SkillBranch b : branchesFor(pdc)) {
            for (SkillCard c : b.cards()) {
                if (c.id().equals(id)) return b;
            }
        }
        return null;
    }

    /** Mapuje kolor gałęzi (patrz SkillBranch) na najbliższy odpowiednik szkła witrażowego. */
    private Material glassForColor(NamedTextColor color) {
        if (color == NamedTextColor.GOLD) return Material.ORANGE_STAINED_GLASS_PANE;
        if (color == NamedTextColor.AQUA) return Material.LIGHT_BLUE_STAINED_GLASS_PANE;
        if (color == NamedTextColor.LIGHT_PURPLE) return Material.MAGENTA_STAINED_GLASS_PANE;
        if (color == NamedTextColor.GREEN) return Material.LIME_STAINED_GLASS_PANE;
        if (color == NamedTextColor.RED) return Material.RED_STAINED_GLASS_PANE;
        if (color == NamedTextColor.YELLOW) return Material.YELLOW_STAINED_GLASS_PANE;
        return Material.GRAY_STAINED_GLASS_PANE;
    }

    private ItemStack offerCardIcon(PersistentDataContainer pdc, String id) {
        SkillCard card = findCard(pdc, id);
        if (card != null) {
            int current = cardCountOf(pdc, id);
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(card.rarity().label, card.rarity().color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text(card.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("Poziom karty: " + current + "/" + card.maxStacks(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Kliknij, aby wybrać", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            ItemStack icon = GuiUtils.namedItem(card.icon(), Component.text(card.displayName(), card.rarity().color, TextDecoration.BOLD), lore.toArray(Component[]::new));
            if (card.rarity() != Rarity.COMMON) {
                ItemMeta iconMeta = icon.getItemMeta();
                iconMeta.setEnchantmentGlintOverride(true);
                icon.setItemMeta(iconMeta);
            }
            return icon;
        }

        RarePerk perk = findRarePerk(pdc, id);
        if (perk == null) return GuiUtils.namedItem(Material.BARRIER, Component.text("???", NamedTextColor.RED));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(Rarity.LEGENDARY.label, Rarity.LEGENDARY.color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text(perk.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Kliknij, aby wybrać - NIEODWRACALNE", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        ItemStack icon = GuiUtils.namedItem(perk.icon(), Component.text(perk.displayName(), Rarity.LEGENDARY.color, TextDecoration.BOLD), lore.toArray(Component[]::new));
        ItemMeta iconMeta = icon.getItemMeta();
        iconMeta.setEnchantmentGlintOverride(true);
        icon.setItemMeta(iconMeta);
        return icon;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder rawHolder = event.getInventory().getHolder();
        if (!(rawHolder instanceof HubHolder) && !(rawHolder instanceof OfferHolder) && !(rawHolder instanceof InfoScreenHolder)) return;

        // Kilka instancji ToolSkillManager (siekiera/motyka/miecz) nasłuchuje tego samego
        // eventu jednocześnie - to GUI może wcale nie należeć do TEJ instancji, więc trzeba
        // to jawnie sprawdzić, inaczej każda instancja próbowałaby obsłużyć cudzy klik.
        if (!belongsToThis(rawHolder)) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(tool, player)) {
            player.closeInventory();
            return;
        }
        ensureInitialized(tool);

        if (rawHolder instanceof HubHolder) {
            handleHubClick(player, tool, clicked, event.getSlot());
        } else if (rawHolder instanceof OfferHolder) {
            handleOfferClick(player, tool, event.getSlot());
        } else if (rawHolder instanceof InfoLauncherHolder) {
            handleInfoLauncherClick(player, tool, event.getSlot());
        } else if (rawHolder instanceof InfoStatsHolder) {
            handleInfoStatsClick(player, tool, event.getSlot());
        } else if (rawHolder instanceof InfoSkillsHolder) {
            handleInfoSkillsClick(player, tool, event.getSlot());
        } else if (rawHolder instanceof InfoSynergyHolder h) {
            handleInfoSynergyClick(player, tool, h, event.getSlot());
        }
    }

    private boolean belongsToThis(InventoryHolder holder) {
        if (holder instanceof HubHolder h) return h.owner == this;
        if (holder instanceof OfferHolder h) return h.owner == this;
        if (holder instanceof InfoScreenHolder h) return h.owner() == this;
        return false;
    }

    private void handleHubClick(Player player, ItemStack tool, ItemStack clicked, int slot) {
        if (clicked.getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }
        if (slot == slotOffer) {
            PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
            String pending = pdc.getOrDefault(pkPendingOffer, PersistentDataType.STRING, "");
            if (!pending.isEmpty()) openOfferChoice(player, pending);
            return;
        }
        if (slot == slotInfo) {
            openInfo(player, tool);
            return;
        }
        if (handleExtraHubClick(player, tool, slot)) {
            openHub(player, tool);
        }
    }

    private void handleInfoLauncherClick(Player player, ItemStack tool, int slot) {
        if (slot == INFO_LAUNCHER_BACK_SLOT) {
            openHub(player, tool);
        } else if (slot == INFO_LAUNCHER_STATS_SLOT) {
            openInfoStats(player, tool);
        } else if (slot == INFO_LAUNCHER_SKILLS_SLOT) {
            openInfoSkills(player, tool);
        } else if (slot == INFO_LAUNCHER_SYNERGY_SLOT && !synergies.isEmpty()) {
            openInfoSynergies(player, tool, null);
        }
    }

    private void handleInfoStatsClick(Player player, ItemStack tool, int slot) {
        if (slot == INFO_STATS_BACK_SLOT) openInfo(player, tool);
    }

    private void handleInfoSkillsClick(Player player, ItemStack tool, int slot) {
        if (slot == INFO_SKILLS_BACK_SLOT) openInfo(player, tool);
    }

    private void handleInfoSynergyClick(Player player, ItemStack tool, InfoSynergyHolder holder, int slot) {
        if (slot == INFO_SYNERGY_BACK_SLOT) {
            openInfo(player, tool);
            return;
        }
        int start = centeredRowStart(INFO_SYNERGY_ROW_START, INFO_SYNERGY_ROW_WIDTH, synergies.size());
        int idx = slot - start;
        if (idx < 0 || idx >= synergies.size()) return;

        String clickedId = synergies.get(idx).id();
        String newSelection = clickedId.equals(holder.selectedSynergyId) ? null : clickedId;
        openInfoSynergies(player, tool, newSelection);
    }

    private void handleOfferClick(Player player, ItemStack tool, int slot) {
        ItemMeta meta = tool.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String pending = pdc.getOrDefault(pkPendingOffer, PersistentDataType.STRING, "");

        if (slot == OFFER_BACK_SLOT || pending.isEmpty()) {
            player.closeInventory();
            return;
        }

        List<String> ids = Arrays.asList(pending.split(","));
        int idx = -1;
        for (int i = 0; i < OFFER_CHOICE_SLOTS.length; i++) {
            if (OFFER_CHOICE_SLOTS[i] == slot) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx >= ids.size()) return;

        String chosenId = ids.get(idx);
        SkillCard chosenCard = findCard(pdc, chosenId);
        RarePerk chosenRare = chosenCard == null ? findRarePerk(pdc, chosenId) : null;
        if (chosenCard == null && chosenRare == null) return;

        Set<String> synergiesBefore = activeSynergyIds(pdc);
        grantCard(pdc, chosenId);
        pdc.set(pkPendingOffer, PersistentDataType.STRING, "");

        int queued = pdc.getOrDefault(pkPendingOfferQueue, PersistentDataType.INTEGER, 0);
        if (queued > 0) {
            pdc.set(pkPendingOfferQueue, PersistentDataType.INTEGER, queued - 1);
            offerNewCardChoice(pdc);
        }

        tool.setItemMeta(meta);
        refreshDisplay(tool);

        String chosenName = chosenCard != null ? chosenCard.displayName() : chosenRare.displayName();
        Rarity chosenRarity = chosenCard != null ? chosenCard.rarity() : Rarity.LEGENDARY;
        player.sendMessage(Component.text("★ Wybrano: " + chosenName, chosenRarity.color, TextDecoration.BOLD));
        if (chosenRarity == Rarity.EPIC || chosenRarity == Rarity.LEGENDARY) {
            player.showTitle(Title.title(
                    Component.text(chosenRarity.label + "!", chosenRarity.color, TextDecoration.BOLD),
                    Component.text(chosenName, NamedTextColor.WHITE)
            ));
        }
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);

        Set<String> synergiesAfter = activeSynergyIds(pdc);
        synergiesAfter.removeAll(synergiesBefore);
        for (String newSynergyId : synergiesAfter) {
            announceSynergyUnlock(player, newSynergyId);
        }

        openHub(player, tool);
    }

    // ========================================================= Holdery ====

    private static final class HubHolder implements InventoryHolder {
        private final ToolSkillManager owner;
        private Inventory inventory;
        private HubHolder(ToolSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class OfferHolder implements InventoryHolder {
        private final ToolSkillManager owner;
        private Inventory inventory;
        private OfferHolder(ToolSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    /** Wspólny interfejs 4 ekranów menu Informacje (launcher + Statystyki/Umiejętności/Kombinacje) - pozwala je odróżnić od HubHolder/OfferHolder w onInventoryClick jednym instanceof. */
    private interface InfoScreenHolder extends InventoryHolder {
        ToolSkillManager owner();
    }

    private static final class InfoLauncherHolder implements InfoScreenHolder {
        private final ToolSkillManager owner;
        private Inventory inventory;
        private InfoLauncherHolder(ToolSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public ToolSkillManager owner() { return owner; }
    }

    private static final class InfoStatsHolder implements InfoScreenHolder {
        private final ToolSkillManager owner;
        private Inventory inventory;
        private InfoStatsHolder(ToolSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public ToolSkillManager owner() { return owner; }
    }

    private static final class InfoSkillsHolder implements InfoScreenHolder {
        private final ToolSkillManager owner;
        private Inventory inventory;
        private InfoSkillsHolder(ToolSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
        @Override public ToolSkillManager owner() { return owner; }
    }

    private static final class InfoSynergyHolder implements InfoScreenHolder {
        private final ToolSkillManager owner;
        private final String selectedSynergyId;
        private Inventory inventory;
        private InfoSynergyHolder(ToolSkillManager owner, String selectedSynergyId) {
            this.owner = owner;
            this.selectedSynergyId = selectedSynergyId;
        }
        @Override public Inventory getInventory() { return inventory; }
        @Override public ToolSkillManager owner() { return owner; }
    }
}