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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generyczny silnik drzewka umiejętności narzędzia - wyciągnięty z PickaxeSkillManager
 * (pierwszego, ręcznie napisanego systemu dla kilofa) tak, żeby siekiera/motyka/miecz
 * mogły dostać DOKŁADNIE tę samą skalę progresji (100 poziomów, 5 tierów x 20, 3 gałęzie,
 * rzadkie perki co RARE_ROLL_INTERVAL poziomów) bez kopiowania ~1300 linii kodu 3 razy.
 * Kilof CELOWO zostaje na swoim osobnym, już przetestowanym PickaxeSkillManager - nie
 * został przepisany na ten silnik, żeby nie ryzykować regresji w działającym kodzie bez
 * możliwości kompilacji/testów na tej maszynie.
 *
 * Podklasa musi dostarczyć: listę 3 gałęzi (branches), pulę rzadkich perków, mapowanie
 * tier→Material, treść statsIcon (statsLore) oraz WŁASNY listener na zdarzenie, które
 * napędza progresję (BlockBreakEvent dla siekiery/motyki, EntityDamageByEntityEvent dla
 * miecza) - to jedyna część, która musi różnić się na tyle, że nie da się jej uogólnić
 * jednym wspólnym hookiem (różne typy eventów, różne konteksty).
 *
 * WAŻNE: każda instancja tworzy WŁASNE obiekty Holder (HubHolder/BranchHolder/RareHolder)
 * ze wskaźnikiem "owner" na siebie - onInventoryClick sprawdza owner == this, żeby przy
 * kilku zarejestrowanych instancjach (siekiera + motyka + miecz naraz) każda reagowała
 * WYŁĄCZNIE na swoje własne GUI, nie na cudze.
 */
public abstract class ToolSkillManager implements Listener {

    protected static final int MAX_LEVEL = 100;
    protected static final int LEVELS_PER_TIER = 20;
    protected static final int MAX_TIER = 4;
    protected static final int POINTS_PER_LEVEL = 1;
    protected static final int[] EXP_PER_LEVEL_BY_TIER = {25, 35, 43, 48, 50};
    protected static final int RARE_ROLL_INTERVAL = 10;

    private static final int DEFAULT_HUB_SIZE = 27;
    private static final int DEFAULT_TOOL_ICON_SLOT = 4;
    private static final int DEFAULT_SLOT_BRANCH1 = 10;
    private static final int DEFAULT_SLOT_BRANCH2 = 13;
    private static final int DEFAULT_SLOT_BRANCH3 = 16;
    private static final int DEFAULT_RARE_BUTTON_SLOT = 18;
    private static final int DEFAULT_STATS_SLOT = 20;
    private static final int DEFAULT_CLOSE_SLOT = 22;
    private static final int DEFAULT_UPGRADES_SLOT = 24;

    private static final int NODES_PER_PAGE = 5;
    private static final int[] BRANCH_NODE_SLOTS = {11, 12, 13, 14, 15};
    private static final int PAGE_LEFT_SLOT = 9;
    private static final int PAGE_RIGHT_SLOT = 17;
    private static final int BRANCH_BACK_SLOT = 22;
    private static final int[] RARE_CHOICE_SLOTS = {11, 13, 15};
    protected static final int AURA_DURATION_TICKS = 1_000_000;

    private int hubSize;
    private int slotToolIcon;
    private int slotRareButton;
    private int slotStats;
    private int slotClose;
    private int slotUpgrades;
    private final int[] branchSlots = new int[3];

    protected final Plugin plugin;
    protected final EconomyService economyService;
    protected final String toolType;
    protected final String displayName;
    protected final List<SkillBranch> branches;
    protected final List<RarePerk> rarePerks;
    protected final String hubConfigFileName;

    protected final NamespacedKey keyType;
    protected final NamespacedKey keyOwner;
    protected final NamespacedKey pkLevel;
    protected final NamespacedKey pkExp;
    protected final NamespacedKey pkTier;
    protected final NamespacedKey pkPoints;
    private final NamespacedKey[] pkBranchMask = new NamespacedKey[3];
    protected final NamespacedKey pkRare;
    protected final NamespacedKey pkPendingRare;
    protected final NamespacedKey pkPendingRareQueue;

    /**
     * @param branches musi mieć DOKŁADNIE 3 elementy (jak drzewko kilofa) - indeksy 0/1/2
     *                 odpowiadają kolejno slotom branch1/branch2/branch3 w hub-configu.
     */
    protected ToolSkillManager(Plugin plugin, EconomyService economyService, String toolType, String displayName,
                                List<SkillBranch> branches, List<RarePerk> rarePerks, String hubConfigFileName) {
        if (branches.size() != 3) {
            throw new IllegalArgumentException("ToolSkillManager wymaga dokładnie 3 gałęzi, dostano: " + branches.size());
        }
        this.plugin = plugin;
        this.economyService = economyService;
        this.toolType = toolType;
        this.displayName = displayName;
        this.branches = branches;
        this.rarePerks = rarePerks;
        this.hubConfigFileName = hubConfigFileName;

        this.keyType = new NamespacedKey(plugin, "tool_type");
        this.keyOwner = new NamespacedKey(plugin, "tool_owner");
        this.pkLevel = new NamespacedKey(plugin, "pk_level");
        this.pkExp = new NamespacedKey(plugin, "pk_exp");
        this.pkTier = new NamespacedKey(plugin, "pk_tier");
        this.pkPoints = new NamespacedKey(plugin, "pk_points");
        this.pkBranchMask[0] = new NamespacedKey(plugin, "pk_branch1");
        this.pkBranchMask[1] = new NamespacedKey(plugin, "pk_branch2");
        this.pkBranchMask[2] = new NamespacedKey(plugin, "pk_branch3");
        this.pkRare = new NamespacedKey(plugin, "pk_rare");
        this.pkPendingRare = new NamespacedKey(plugin, "pk_pending_rare");
        this.pkPendingRareQueue = new NamespacedKey(plugin, "pk_pending_rare_queue");

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
        slotRareButton = clampSlot(cfg.getInt("sloty.rzadki_wybor", DEFAULT_RARE_BUTTON_SLOT));
        slotStats = clampSlot(cfg.getInt("sloty.statystyki", DEFAULT_STATS_SLOT));
        slotClose = clampSlot(cfg.getInt("sloty.wyjdz", DEFAULT_CLOSE_SLOT));
        slotUpgrades = clampSlot(cfg.getInt("sloty.ulepszenia", DEFAULT_UPGRADES_SLOT));
        branchSlots[0] = clampSlot(cfg.getInt("sloty.galaz1", DEFAULT_SLOT_BRANCH1));
        branchSlots[1] = clampSlot(cfg.getInt("sloty.galaz2", DEFAULT_SLOT_BRANCH2));
        branchSlots[2] = clampSlot(cfg.getInt("sloty.galaz3", DEFAULT_SLOT_BRANCH3));
    }

    private int clampSize(int rozmiar) {
        int ograniczony = Math.max(9, Math.min(54, rozmiar));
        return Math.min(54, ((ograniczony + 8) / 9) * 9);
    }

    private int clampSlot(int slot) {
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
            player.sendActionBar(Component.text(displayName + ": poziom " + level + " (+" + POINTS_PER_LEVEL + " punkt umiejętności)", NamedTextColor.AQUA));

            if (tierUp) {
                int tier = pdc.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
                player.showTitle(Title.title(
                        Component.text(displayName + " Ewoluował!", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("Nowy tier: " + tierName(tier), NamedTextColor.YELLOW)
                ));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }
            if (rareRolled) {
                player.sendMessage(Component.text("★ Nowy Rzadki Wybór dostępny w drzewku umiejętności! (Shift + PPM)",
                        NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
            }
            if (level >= MAX_LEVEL) {
                player.sendMessage(Component.text("★ " + displayName + " osiągnęła maksymalny poziom (" + MAX_LEVEL + ")!",
                        NamedTextColor.GOLD, TextDecoration.BOLD));
            }
        }
    }

    private boolean rollRareChoice(PersistentDataContainer pdc) {
        Set<String> owned = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        if (owned.size() >= rarePerks.size()) return false;

        String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
        if (!pending.isEmpty()) {
            int queued = pdc.getOrDefault(pkPendingRareQueue, PersistentDataType.INTEGER, 0);
            pdc.set(pkPendingRareQueue, PersistentDataType.INTEGER, queued + 1);
            return true;
        }

        return offerNewRareChoice(pdc, owned);
    }

    private boolean offerNewRareChoice(PersistentDataContainer pdc, Set<String> owned) {
        List<RarePerk> remaining = new ArrayList<>();
        for (RarePerk perk : rarePerks) {
            if (!owned.contains(perk.id())) remaining.add(perk);
        }
        if (remaining.isEmpty()) return false;

        Collections.shuffle(remaining);
        List<String> picks = remaining.stream().limit(3).map(RarePerk::id).toList();
        pdc.set(pkPendingRare, PersistentDataType.STRING, String.join(",", picks));
        return true;
    }

    protected void ensureInitialized(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(pkLevel, PersistentDataType.INTEGER)) return;

        pdc.set(pkLevel, PersistentDataType.INTEGER, 1);
        pdc.set(pkExp, PersistentDataType.INTEGER, 0);
        pdc.set(pkTier, PersistentDataType.INTEGER, 0);
        pdc.set(pkPoints, PersistentDataType.INTEGER, 0);
        for (NamespacedKey key : pkBranchMask) {
            pdc.set(key, PersistentDataType.INTEGER, 0);
        }
        pdc.set(pkRare, PersistentDataType.STRING, "");
        pdc.set(pkPendingRare, PersistentDataType.STRING, "");
        pdc.set(pkPendingRareQueue, PersistentDataType.INTEGER, 0);
        item.setItemMeta(meta);

        refreshDisplay(item);
    }

    protected void refreshDisplay(ItemStack item) {
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
        String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
        int queuedRare = pdc.getOrDefault(pkPendingRareQueue, PersistentDataType.INTEGER, 0);

        meta.displayName(Component.text(displayName + " ", NamedTextColor.AQUA, TextDecoration.BOLD)
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

        StringBuilder counts = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) counts.append("  •  ");
            int owned = Integer.bitCount(pdc.getOrDefault(pkBranchMask[i], PersistentDataType.INTEGER, 0));
            counts.append(branches.get(i).displayName()).append(" ").append(owned).append("/").append(branches.get(i).nodes().size());
        }
        lore.add(Component.text(counts.toString(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));

        if (!pending.isEmpty()) {
            lore.add(Component.empty());
            lore.add(Component.text("★ Dostępny Rzadki Wybór!" + (queuedRare > 0 ? " (+" + queuedRare + " w kolejce)" : ""),
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

    protected boolean bitSet(int mask, int index) {
        return (mask & (1 << index)) != 0;
    }

    /** Materiał narzędzia dla danego tieru (0=drewno .. 4=netheryt) - dostarcza podklasa. */
    protected abstract Material materialForTier(int tier);

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
     * Synchronizuje realne enchanty/atrybuty specyficzne dla narzędzia (np. kilof:
     * Wydajność/Fortuna/prędkość kopania; miecz: Ostrość/obrażenia/szybkość ataku) -
     * odpowiednik syncEnchants z PickaxeSkillManager, ale treść w 100% zależy od
     * narzędzia, więc to jedyny obowiązkowy hook do zaimplementowania w podklasie.
     */
    protected abstract void syncToolSpecificStats(ItemMeta meta, PersistentDataContainer pdc);

    /** Treść ikonki Statystyk/Ulepszeń w hubie - realne wartości specyficzne dla narzędzia. */
    protected abstract List<Component> statsLore(PersistentDataContainer pdc);

    // ================================================= Drzewko - odczyt ====

    protected int indexOf(int branchIndex, String nodeId) {
        List<SkillNode> nodes = branches.get(branchIndex).nodes();
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id().equals(nodeId)) return i;
        }
        throw new IllegalArgumentException("Nieznany węzeł: " + nodeId);
    }

    protected boolean hasNode(PersistentDataContainer pdc, int branchIndex, String nodeId) {
        int mask = pdc.getOrDefault(pkBranchMask[branchIndex], PersistentDataType.INTEGER, 0);
        return bitSet(mask, indexOf(branchIndex, nodeId));
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
    // podklasa może nadpisać oba hooki, żeby któraś gałąź dawała stały efekt mikstury.

    protected PotionEffectType auraEffectType() {
        return null;
    }

    /** Amplifier aury (0 = poziom I) albo null, jeśli węzeł dający aurę nie jest wykupiony. */
    protected Integer auraAmplifierFor(PersistentDataContainer pdc) {
        return null;
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

    protected RarePerk findRarePerk(String id) {
        for (RarePerk perk : rarePerks) {
            if (perk.id().equals(id)) return perk;
        }
        return null;
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
        int points = pdc.getOrDefault(pkPoints, PersistentDataType.INTEGER, 0);
        String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
        int queuedRare = pdc.getOrDefault(pkPendingRareQueue, PersistentDataType.INTEGER, 0);

        HubHolder holder = new HubHolder(this);
        Inventory gui = Bukkit.createInventory(holder, hubSize, Component.text(displayName + " — Umiejętności", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.PURPLE_STAINED_GLASS_PANE);

        gui.setItem(slotToolIcon, GuiUtils.namedItem(materialForTier(tier),
                Component.text(displayName + " [Poziom " + level + "]", NamedTextColor.AQUA, TextDecoration.BOLD),
                Component.text("Tier: " + tierName(tier), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Wolne punkty: " + points, points > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));

        for (int i = 0; i < 3; i++) {
            SkillBranch branch = branches.get(i);
            int mask = pdc.getOrDefault(pkBranchMask[i], PersistentDataType.INTEGER, 0);
            int owned = Integer.bitCount(mask);
            ItemStack icon = GuiUtils.namedItem(branch.icon(),
                    Component.text(branch.displayName(), branch.color(), TextDecoration.BOLD),
                    Component.text("Wykupione: " + owned + "/" + branch.nodes().size(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Kliknij, aby otworzyć", branch.color()).decoration(TextDecoration.ITALIC, false));
            gui.setItem(branchSlots[i], icon);
        }

        gui.setItem(slotStats, statsIcon(pdc, "Statystyki"));
        gui.setItem(slotUpgrades, statsIcon(pdc, "Aktualne Ulepszenia"));

        if (!pending.isEmpty()) {
            gui.setItem(slotRareButton, GuiUtils.namedItem(Material.NETHER_STAR,
                    Component.text("★ Rzadki Wybór dostępny!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                    Component.text("Kliknij, aby wybrać jedną z 3 rzadkich", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("umiejętności dla swojego narzędzia.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text(queuedRare > 0 ? "+" + queuedRare + " kolejnych w kolejce" : " ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        }

        gui.setItem(slotClose, GuiUtils.namedItem(Material.BARRIER, Component.text("Zamknij", NamedTextColor.RED, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private ItemStack statsIcon(PersistentDataContainer pdc, String title) {
        List<Component> lore = statsLore(pdc);
        return GuiUtils.namedItem(Material.KNOWLEDGE_BOOK,
                Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD),
                lore.toArray(Component[]::new));
    }

    private int maxPageFor(int branchIndex) {
        return (branches.get(branchIndex).nodes().size() - 1) / NODES_PER_PAGE;
    }

    private void openBranch(Player player, ItemStack tool, int branchIndex, int page) {
        SkillBranch branch = branches.get(branchIndex);
        ItemMeta meta = tool.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int mask = pdc.getOrDefault(pkBranchMask[branchIndex], PersistentDataType.INTEGER, 0);
        int points = pdc.getOrDefault(pkPoints, PersistentDataType.INTEGER, 0);
        int maxPage = maxPageFor(branchIndex);
        page = Math.max(0, Math.min(page, maxPage));

        BranchHolder holder = new BranchHolder(this);
        holder.branchIndex = branchIndex;
        holder.page = page;
        Inventory gui = Bukkit.createInventory(holder, 27, Component.text(
                branch.displayName() + " — Drzewko (" + (page + 1) + "/" + (maxPage + 1) + ")", branch.color(), TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui);

        List<SkillNode> nodes = branch.nodes();
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
        RareHolder holder = new RareHolder(this);
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

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder rawHolder = event.getInventory().getHolder();
        if (!(rawHolder instanceof HubHolder) && !(rawHolder instanceof BranchHolder) && !(rawHolder instanceof RareHolder)) return;

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
        } else if (rawHolder instanceof BranchHolder branchHolder) {
            handleBranchClick(player, tool, branchHolder, event.getSlot());
        } else {
            handleRareClick(player, tool, event.getSlot());
        }
    }

    private boolean belongsToThis(InventoryHolder holder) {
        if (holder instanceof HubHolder h) return h.owner == this;
        if (holder instanceof BranchHolder h) return h.owner == this;
        if (holder instanceof RareHolder h) return h.owner == this;
        return false;
    }

    private void handleHubClick(Player player, ItemStack tool, ItemStack clicked, int slot) {
        if (clicked.getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }
        if (slot == slotRareButton) {
            PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
            String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
            if (!pending.isEmpty()) openRareChoice(player, pending);
            return;
        }
        for (int i = 0; i < 3; i++) {
            if (slot == branchSlots[i]) {
                openBranch(player, tool, i, 0);
                return;
            }
        }
    }

    private void handleBranchClick(Player player, ItemStack tool, BranchHolder branchHolder, int slot) {
        int branchIndex = branchHolder.branchIndex;
        int page = branchHolder.page;

        if (slot == BRANCH_BACK_SLOT) {
            openHub(player, tool);
            return;
        }
        if (slot == PAGE_LEFT_SLOT && page > 0) {
            openBranch(player, tool, branchIndex, page - 1);
            return;
        }
        if (slot == PAGE_RIGHT_SLOT && page < maxPageFor(branchIndex)) {
            openBranch(player, tool, branchIndex, page + 1);
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
        List<SkillNode> nodes = branches.get(branchIndex).nodes();
        int idx = page * NODES_PER_PAGE + posOnPage;
        if (idx >= nodes.size()) return;

        ItemMeta meta = tool.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        NamespacedKey maskKey = pkBranchMask[branchIndex];
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
            tool.setItemMeta(meta);
            refreshDisplay(tool);
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            player.sendActionBar(Component.text("Wykupiono: " + nodes.get(idx).displayName(), NamedTextColor.GREEN));
        }

        openBranch(player, tool, branchIndex, page);
    }

    private void handleRareClick(Player player, ItemStack tool, int slot) {
        ItemMeta meta = tool.getItemMeta();
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

        int queued = pdc.getOrDefault(pkPendingRareQueue, PersistentDataType.INTEGER, 0);
        if (queued > 0) {
            pdc.set(pkPendingRareQueue, PersistentDataType.INTEGER, queued - 1);
            offerNewRareChoice(pdc, owned);
        }

        tool.setItemMeta(meta);
        refreshDisplay(tool);

        player.sendMessage(Component.text("★ Wybrano rzadką umiejętność: " + chosen.displayName(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        player.showTitle(Title.title(
                Component.text("Rzadka Umiejętność!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                Component.text(chosen.displayName(), NamedTextColor.WHITE)
        ));
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);

        openHub(player, tool);
    }

    // ========================================================= Holdery ====

    private static final class HubHolder implements InventoryHolder {
        private final ToolSkillManager owner;
        private Inventory inventory;
        private HubHolder(ToolSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class BranchHolder implements InventoryHolder {
        private final ToolSkillManager owner;
        private Inventory inventory;
        private int branchIndex;
        private int page;
        private BranchHolder(ToolSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class RareHolder implements InventoryHolder {
        private final ToolSkillManager owner;
        private Inventory inventory;
        private RareHolder(ToolSkillManager owner) { this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
}