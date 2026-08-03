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
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 */
public class PickaxeSkillManager implements Listener {

    private static final int EXP_PER_LEVEL = 50;
    private static final int MAX_TIER = 4;
    private static final int RARE_BUTTON_SLOT = 22;
    private static final int[] BRANCH_NODE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] RARE_CHOICE_SLOTS = {11, 13, 15};
    private static final int AURA_DURATION_TICKS = 1_000_000;
    private static final BlockFace[] NEIGHBOR_FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final Plugin plugin;
    private final EconomyService economyService;

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

    // Krótkotrwały licznik "kopnięć z rzędu" pod Impet Górnika - celowo w pamięci,
    // nie w PDC: to tylko chwilowy combo-mechanizm, nie trzeba go trzymać po restarcie.
    private final Map<UUID, Long> streakLastBreak = new HashMap<>();
    private final Map<UUID, Integer> streakCount = new HashMap<>();

    public PickaxeSkillManager(Plugin plugin, EconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
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

        // Kamień Filozoficzny - podmienia plon kamienia/brukowca na coś cenniejszego
        if (stoneLike && hasNode(pdc, Branch.MAGIA, "MAG_PHILOSOPHERS_STONE") && rnd.nextDouble() < 0.06) {
            if (rnd.nextBoolean()) {
                ItemStack reward = rnd.nextBoolean() ? new ItemStack(Material.IRON_INGOT) : new ItemStack(Material.GOLD_NUGGET, 3);
                block.getWorld().dropItemNaturally(block.getLocation(), reward);
            } else {
                payBonus(player, 3 + tierOf(pdc));
            }
        }

        // Żelazna Pięść / Precyzyjne Uderzenie / Wrota Głębi / Druga Szansa - dodatkowa kopia plonu
        double bonusDropChance = 0;
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_IRON_GRIP")) bonusDropChance += 0.10;
        if (hasNode(pdc, Branch.PRECYZJA, "PREC_CRIT")) bonusDropChance += 0.15;
        if (hasNode(pdc, Branch.MAGIA, "MAG_DEPTHS_GATE")) bonusDropChance += 0.10;
        if (hasRare(pdc, "RARE_SECOND_CHANCE")) bonusDropChance += 0.05;
        if (bonusDropChance > 0 && rnd.nextDouble() < bonusDropChance) {
            dropCopy(block, item);
        }

        // Oko Sokoła - dodatkowy plon z rzadkich rud
        if (isRareOre(mat) && hasNode(pdc, Branch.PRECYZJA, "PREC_HAWK_EYE") && rnd.nextDouble() < 0.10) {
            dropCopy(block, item);
        }

        // Rezonans Rudy - sąsiedni blok tej samej rudy pęka razem z kopanym
        if (ore && hasNode(pdc, Branch.WYDAJNOSC, "WYD_RESONANCE") && rnd.nextDouble() < 0.20) {
            breakRandomNeighbors(block, item, mat, 1);
        }

        // Rdzeń Chaosu (rzadka) - do 3 sąsiednich bloków kamienia/rudy naraz
        if (hasRare(pdc, "RARE_CHAOS_CORE") && rnd.nextDouble() < 0.05) {
            breakRandomNeighbors(block, item, null, 3);
        }

        // Bystre Oko / Ręka Jubilera / Dotyk Midasa - bonusowa wypłata
        if (ore) {
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_KEEN_EYE") && rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
            if (hasNode(pdc, Branch.PRECYZJA, "PREC_JEWELER") && rnd.nextDouble() < 0.08) payBonus(player, 6 + tierOf(pdc) * 4);
        }
        if (hasRare(pdc, "RARE_MIDAS") && rnd.nextDouble() < 0.03) payBonus(player, 2 + tierOf(pdc));

        // Iskra Many / Nieugięty Duch - bonusowe orby xp
        if (hasNode(pdc, Branch.MAGIA, "MAG_SPARK") && rnd.nextDouble() < 0.25) spawnXp(block, 1 + rnd.nextInt(3));
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

        // Impet Górnika - kopnięcia z rzędu dają chwilowy Pośpiech
        if (hasNode(pdc, Branch.WYDAJNOSC, "WYD_MOMENTUM")) {
            handleMomentum(player);
        }
    }

    private void handleMomentum(Player player) {
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        long last = streakLastBreak.getOrDefault(id, 0L);
        int streak = (now - last <= 3000) ? streakCount.getOrDefault(id, 0) + 1 : 1;
        streakLastBreak.put(id, now);

        if (streak >= 3) {
            streak = 0;
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 80, 0, true, false, true));
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
        for (int i = 0; i < levels * EXP_PER_LEVEL; i++) {
            addExp(player, item);
        }
    }

    private void addExp(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        int exp = pdc.getOrDefault(pkExp, PersistentDataType.INTEGER, 0) + 1;
        int points = pdc.getOrDefault(pkPoints, PersistentDataType.INTEGER, 0);

        boolean leveledUp = false;
        boolean rareRolled = false;
        boolean tierUp = false;

        if (exp >= EXP_PER_LEVEL) {
            exp = 0;
            level++;
            points++;
            leveledUp = true;

            if (level % 5 == 0) {
                rareRolled = rollRareChoice(pdc);
            }
            if (level % 10 == 0) {
                pdc.set(pkTier, PersistentDataType.INTEGER, Math.min(level / 10, MAX_TIER));
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
            player.sendActionBar(Component.text("Kilof: poziom " + level + " (+1 punkt umiejętności)", NamedTextColor.AQUA));

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
        }
    }

    /** @return true, jeśli faktycznie wylosowano nową trójkę - false, gdy poprzedni wybór wciąż czeka albo pula się wyczerpała. */
    private boolean rollRareChoice(PersistentDataContainer pdc) {
        String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
        if (!pending.isEmpty()) return false;

        Set<String> owned = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
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

        meta.displayName(Component.text("Kilof Umiejętności ", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text("[Poziom " + level + "]", NamedTextColor.YELLOW, TextDecoration.BOLD)));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Tier: " + tierName(tier), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Postęp: " + expBar(exp) + " " + exp + "/" + EXP_PER_LEVEL, NamedTextColor.DARK_AQUA).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Punkty umiejętności: " + points, points > 0 ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Wydajność " + wyd + "/7  •  Precyzja " + prec + "/7  •  Magia " + mag + "/7", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (!pending.isEmpty()) {
            lore.add(Component.empty());
            lore.add(Component.text("★ Dostępny Rzadki Wybór!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Prywatny kilof", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("PPM - otwórz drzewko umiejętności", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        if (meta instanceof Damageable damageable) {
            short maxDurability = item.getType().getMaxDurability();
            double progress = (double) exp / EXP_PER_LEVEL;
            int damage = (int) Math.round(maxDurability - maxDurability * progress);
            damageable.setDamage(Math.max(0, Math.min(damage, maxDurability - 1)));
        }

        syncEnchants(meta, pdc);
        item.setItemMeta(meta);
    }

    private String expBar(int exp) {
        int filled = (int) Math.round(10.0 * exp / EXP_PER_LEVEL);
        return "▮".repeat(Math.max(0, filled)) + "▯".repeat(Math.max(0, 10 - filled));
    }

    private void syncEnchants(ItemMeta meta, PersistentDataContainer pdc) {
        int wydMask = pdc.getOrDefault(pkWyd, PersistentDataType.INTEGER, 0);
        int precMask = pdc.getOrDefault(pkPrec, PersistentDataType.INTEGER, 0);
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));

        int effLevel;
        if (bitSet(wydMask, indexOf(Branch.WYDAJNOSC, "WYD_EFF4"))) effLevel = 4;
        else if (bitSet(wydMask, indexOf(Branch.WYDAJNOSC, "WYD_EFF3"))) effLevel = 3;
        else if (bitSet(wydMask, indexOf(Branch.WYDAJNOSC, "WYD_EFF2"))) effLevel = 2;
        else if (bitSet(wydMask, indexOf(Branch.WYDAJNOSC, "WYD_EFF1"))) effLevel = 1;
        else effLevel = 0;
        if (rare.contains("RARE_EFF5") && effLevel > 0) effLevel += 1;

        int fortLevel;
        if (bitSet(precMask, indexOf(Branch.PRECYZJA, "PREC_FORT3"))) fortLevel = 3;
        else if (bitSet(precMask, indexOf(Branch.PRECYZJA, "PREC_FORT2"))) fortLevel = 2;
        else if (bitSet(precMask, indexOf(Branch.PRECYZJA, "PREC_FORT1"))) fortLevel = 1;
        else fortLevel = 0;
        if (rare.contains("RARE_FORT4") && fortLevel > 0) fortLevel += 1;

        meta.removeEnchant(Enchantment.EFFICIENCY);
        if (effLevel > 0) meta.addEnchant(Enchantment.EFFICIENCY, effLevel, true);
        meta.removeEnchant(Enchantment.FORTUNE);
        if (fortLevel > 0) meta.addEnchant(Enchantment.FORTUNE, fortLevel, true);
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
            if (hasNode(pdc, Branch.MAGIA, "MAG_HASTE2")) amplifier = 1;
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
        if (event.getCause() != EntityDamageEvent.DamageCause.FALLING_BLOCK) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedPickaxe(item, player)) return;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (hasNode(pdc, Branch.MAGIA, "MAG_GRAVITY_WARD")) {
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

        HubHolder holder = new HubHolder();
        Inventory gui = Bukkit.createInventory(holder, 27, Component.text("Kilof — Umiejętności", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui);

        gui.setItem(4, GuiUtils.namedItem(materialForTier(tier),
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

        if (!pending.isEmpty()) {
            gui.setItem(RARE_BUTTON_SLOT, GuiUtils.namedItem(Material.NETHER_STAR,
                    Component.text("★ Rzadki Wybór dostępny!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD),
                    Component.text("Kliknij, aby wybrać jedną z 3 rzadkich", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("umiejętności dla swojego kilofa.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        }

        gui.setItem(26, GuiUtils.namedItem(Material.BARRIER, Component.text("Zamknij", NamedTextColor.RED, TextDecoration.BOLD)));
        player.openInventory(gui);
    }

    private void openBranch(Player player, ItemStack pickaxe, Branch branch) {
        ItemMeta meta = pickaxe.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int mask = pdc.getOrDefault(maskKeyFor(branch), PersistentDataType.INTEGER, 0);
        int points = pdc.getOrDefault(pkPoints, PersistentDataType.INTEGER, 0);

        BranchHolder holder = new BranchHolder();
        holder.branch = branch;
        Inventory gui = Bukkit.createInventory(holder, 27, Component.text(branch.displayName + " — Drzewko", branch.color, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui);

        List<SkillNode> nodes = branch.nodes;
        for (int i = 0; i < nodes.size(); i++) {
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
            gui.setItem(BRANCH_NODE_SLOTS[i], icon);
        }

        gui.setItem(22, GuiUtils.namedItem(Material.ARROW, Component.text("« Wróć", NamedTextColor.GOLD, TextDecoration.BOLD)));
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
        return switch (branch) {
            case WYDAJNOSC -> 11;
            case PRECYZJA -> 13;
            case MAGIA -> 15;
        };
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
            handleBranchClick(player, pickaxe, branchHolder.branch, event.getSlot());
        } else {
            handleRareClick(player, pickaxe, event.getSlot());
        }
    }

    private void handleHubClick(Player player, ItemStack pickaxe, ItemStack clicked, int slot) {
        if (clicked.getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }
        if (slot == RARE_BUTTON_SLOT) {
            PersistentDataContainer pdc = pickaxe.getItemMeta().getPersistentDataContainer();
            String pending = pdc.getOrDefault(pkPendingRare, PersistentDataType.STRING, "");
            if (!pending.isEmpty()) openRareChoice(player, pending);
            return;
        }
        for (Branch branch : Branch.values()) {
            if (slot == hubSlotFor(branch)) {
                openBranch(player, pickaxe, branch);
                return;
            }
        }
    }

    private void handleBranchClick(Player player, ItemStack pickaxe, Branch branch, int slot) {
        if (slot == 22) {
            openHub(player, pickaxe);
            return;
        }

        int idx = -1;
        for (int i = 0; i < BRANCH_NODE_SLOTS.length; i++) {
            if (BRANCH_NODE_SLOTS[i] == slot) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return;

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

        openBranch(player, pickaxe, branch);
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
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class RareHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}