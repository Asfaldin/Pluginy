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
 * Kilof (PickaxeSkillManager) NIE jest na tym silniku - zostaje na swoim osobnym, już
 * przetestowanym systemie punktowo-drzewkowym, żeby nie ryzykować regresji bez
 * możliwości kompilacji/testów na tej maszynie.
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
    private static final int DEFAULT_STATS_SLOT = 20;
    private static final int DEFAULT_CLOSE_SLOT = 22;
    private static final int DEFAULT_UPGRADES_SLOT = 24;

    private static final int[] OFFER_CHOICE_SLOTS = {10, 12, 14, 16};
    private static final int OFFER_BACK_SLOT = 22;
    protected static final int AURA_DURATION_TICKS = 1_000_000;

    private int hubSize;
    private int slotToolIcon;
    private int slotOffer;
    private int slotStats;
    private int slotClose;
    private int slotUpgrades;

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
    protected final NamespacedKey pkCardCounts;
    protected final NamespacedKey pkRare;
    protected final NamespacedKey pkPendingOffer;
    protected final NamespacedKey pkPendingOfferQueue;

    /** @param branches musi mieć DOKŁADNIE 3 elementy - czysto porządkowe (patrz SkillBranch). */
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
        slotStats = clampSlot(cfg.getInt("sloty.statystyki", DEFAULT_STATS_SLOT));
        slotClose = clampSlot(cfg.getInt("sloty.wyjdz", DEFAULT_CLOSE_SLOT));
        slotUpgrades = clampSlot(cfg.getInt("sloty.ulepszenia", DEFAULT_UPGRADES_SLOT));
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

        boolean leveledUp = false;
        boolean offerRolled = false;
        boolean tierUp = false;

        if (exp >= expPerLevel(tierBefore)) {
            exp = 0;
            level++;
            leveledUp = true;

            offerRolled = rollCardOffer(pdc);
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
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            player.sendActionBar(Component.text(displayName + ": poziom " + level, NamedTextColor.AQUA));

            if (tierUp) {
                int tier = pdc.getOrDefault(pkTier, PersistentDataType.INTEGER, 0);
                player.showTitle(Title.title(
                        Component.text(displayName + " Ewoluował!", NamedTextColor.GOLD, TextDecoration.BOLD),
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
                player.sendMessage(Component.text("★ " + displayName + " osiągnęła maksymalny poziom (" + MAX_LEVEL + ")!",
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
        List<String> candidates = new ArrayList<>();
        if (tier == Rarity.LEGENDARY) {
            Set<String> owned = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
            for (RarePerk perk : rarePerks) {
                if (!owned.contains(perk.id()) && !exclude.contains(perk.id())) candidates.add(perk.id());
            }
        } else {
            for (SkillBranch b : branches) {
                for (SkillCard c : b.cards()) {
                    if (c.rarity() == tier && !exclude.contains(c.id()) && cardCountOf(pdc, c.id()) < c.maxStacks()) {
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
        String pending = pdc.getOrDefault(pkPendingOffer, PersistentDataType.STRING, "");
        int queuedOffers = pdc.getOrDefault(pkPendingOfferQueue, PersistentDataType.INTEGER, 0);

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

    // ==================================================== Karty - odczyt ====

    protected SkillCard findCard(String id) {
        for (SkillBranch b : branches) {
            for (SkillCard c : b.cards()) {
                if (c.id().equals(id)) return c;
            }
        }
        return null;
    }

    protected RarePerk findRarePerk(String id) {
        for (RarePerk perk : rarePerks) {
            if (perk.id().equals(id)) return perk;
        }
        return null;
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
        SkillCard card = findCard(cardId);
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

        HubHolder holder = new HubHolder(this);
        Inventory gui = Bukkit.createInventory(holder, hubSize, Component.text(displayName + " — Umiejętności", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.PURPLE_STAINED_GLASS_PANE);

        gui.setItem(slotToolIcon, GuiUtils.namedItem(materialForTier(tier),
                Component.text(displayName + " [Poziom " + level + "]", NamedTextColor.AQUA, TextDecoration.BOLD),
                Component.text("Tier: " + tierName(tier), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));

        gui.setItem(slotStats, statsIcon(pdc));
        gui.setItem(slotUpgrades, ownedCardsIcon(pdc));

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

    /** Lista wszystkich posiadanych kart (stackowalnych wg poziomu + legendarnych), kolorowana wg rzadkości. */
    private ItemStack ownedCardsIcon(PersistentDataContainer pdc) {
        List<Component> lore = new ArrayList<>();
        for (SkillBranch b : branches) {
            for (SkillCard c : b.cards()) {
                int count = cardCountOf(pdc, c.id());
                if (count > 0) {
                    lore.add(Component.text(c.displayName() + " " + count + "/" + c.maxStacks(), c.rarity().color).decoration(TextDecoration.ITALIC, false));
                }
            }
        }
        Set<String> owned = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        if (!owned.isEmpty()) {
            lore.add(Component.empty());
            for (String id : owned) {
                RarePerk perk = findRarePerk(id);
                if (perk != null) {
                    lore.add(Component.text("★ " + perk.displayName(), Rarity.LEGENDARY.color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
                }
            }
        }
        if (lore.isEmpty()) {
            lore.add(Component.text("Brak - zdobądź pierwszy poziom!", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        return GuiUtils.namedItem(Material.WRITTEN_BOOK,
                Component.text("Aktualne Ulepszenia", NamedTextColor.GOLD, TextDecoration.BOLD),
                lore.toArray(Component[]::new));
    }

    private void openOfferChoice(Player player, String pendingCsv) {
        List<String> ids = Arrays.asList(pendingCsv.split(","));
        OfferHolder holder = new OfferHolder(this);
        Inventory gui = Bukkit.createInventory(holder, 27, Component.text("Wybierz Ulepszenie", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.PURPLE_STAINED_GLASS_PANE);

        for (int i = 0; i < ids.size() && i < OFFER_CHOICE_SLOTS.length; i++) {
            gui.setItem(OFFER_CHOICE_SLOTS[i], offerCardIcon(player, ids.get(i)));
        }

        gui.setItem(OFFER_BACK_SLOT, GuiUtils.namedItem(Material.BARRIER, Component.text("Zdecyduj później", NamedTextColor.GRAY, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private ItemStack offerCardIcon(Player player, String id) {
        SkillCard card = findCard(id);
        if (card != null) {
            int current = cardCountOf(player.getInventory().getItemInMainHand().getItemMeta().getPersistentDataContainer(), id);
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

        RarePerk perk = findRarePerk(id);
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
        if (!(rawHolder instanceof HubHolder) && !(rawHolder instanceof OfferHolder)) return;

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
        } else {
            handleOfferClick(player, tool, event.getSlot());
        }
    }

    private boolean belongsToThis(InventoryHolder holder) {
        if (holder instanceof HubHolder h) return h.owner == this;
        if (holder instanceof OfferHolder h) return h.owner == this;
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
        }
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
        SkillCard chosenCard = findCard(chosenId);
        RarePerk chosenRare = chosenCard == null ? findRarePerk(chosenId) : null;
        if (chosenCard == null && chosenRare == null) return;

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
}