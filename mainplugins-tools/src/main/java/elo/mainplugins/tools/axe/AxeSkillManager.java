package elo.mainplugins.tools.axe;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.tools.skilltree.ToolSkillManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Siekiera na silniku ToolSkillManager - drugi pełnoprawny system drzewka umiejętności
 * po kilofie (który zostaje osobno). Mechaniki 1:1 skopiowane z applyMiningPerks
 * PickaxeSkillManager, tylko przełączone z kopania rudy na rąbanie drewna - patrz
 * AxeSkillTrees dla treści węzłów i uzasadnienia tego wyboru.
 */
public class AxeSkillManager extends ToolSkillManager {

    private static final int BRANCH_CIECIE = 0;
    private static final int BRANCH_LESNICTWO = 1;
    private static final int BRANCH_NATURA = 2;

    private final NamespacedKey pkSpeedModifierKey;
    private final NamespacedKey pkSpeedPassiveModifierKey;

    private final Map<UUID, Long> streakLastBreak = new HashMap<>();
    private final Map<UUID, Integer> streakCount = new HashMap<>();

    public AxeSkillManager(Plugin plugin, EconomyService economyService) {
        super(plugin, economyService, "axe", "Siekiera", AxeSkillTrees.BRANCHES, AxeRarePerks.WSZYSTKIE, "siekiera-hub.yml");
        this.pkSpeedModifierKey = new NamespacedKey(plugin, "pk_axe_speed_bonus");
        this.pkSpeedPassiveModifierKey = new NamespacedKey(plugin, "pk_axe_speed_passive");
    }

    // ============================================================ Rąbanie ====

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(item, player)) return;

        ensureInitialized(item);
        applyChoppingPerks(player, event.getBlock(), item);
        addExp(player, item);
    }

    private boolean isLog(Material m) {
        return m.name().endsWith("_LOG") || m.name().endsWith("_STEM");
    }

    private void applyChoppingPerks(Player player, Block block, ItemStack item) {
        Material mat = block.getType();
        if (!isLog(mat)) return;

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // Duch Drzewa (I-V) - podmienia plon rąbanego drewna na cenny surowiec
        double duchDrzewaChance = hasNode(pdc, BRANCH_NATURA, "NAT_DUCH_DRZEWA5") ? 0.24
                : hasNode(pdc, BRANCH_NATURA, "NAT_DUCH_DRZEWA4") ? 0.20
                : hasNode(pdc, BRANCH_NATURA, "NAT_DUCH_DRZEWA3") ? 0.16
                : hasNode(pdc, BRANCH_NATURA, "NAT_DUCH_DRZEWA2") ? 0.12 : 0.06;
        if (hasNode(pdc, BRANCH_NATURA, "NAT_DUCH_DRZEWA1") && rnd.nextDouble() < duchDrzewaChance) {
            if (rnd.nextBoolean()) {
                ItemStack reward = rnd.nextBoolean() ? new ItemStack(Material.IRON_INGOT) : new ItemStack(Material.GOLD_NUGGET, 3);
                block.getWorld().dropItemNaturally(block.getLocation(), reward);
            } else {
                payBonus(player, 3 + tierOf(pdc));
            }
        }

        // Ostre Ostrze (I-IX) / Precyzyjny Cios (I-IX) / Podwójny Pień (I-VII) / Mistrzostwo
        // (x3, jedno na gałąź) / Druga Szansa - dodatkowa kopia plonu (jedna wspólna szansa)
        double bonusDropChance = 0;
        for (int i = 1; i <= 9; i++) {
            if (hasNode(pdc, BRANCH_CIECIE, "CIECIE_OSTRZE" + i)) bonusDropChance += 0.10;
        }
        for (int i = 1; i <= 9; i++) {
            if (hasNode(pdc, BRANCH_LESNICTWO, "LES_CIOS" + i)) bonusDropChance += 0.15;
        }
        for (int i = 1; i <= 7; i++) {
            if (hasNode(pdc, BRANCH_NATURA, "NAT_PIEN" + i)) bonusDropChance += 0.10;
        }
        if (hasNode(pdc, BRANCH_CIECIE, "CIECIE_MISTRZOSTWO")) bonusDropChance += 0.15;
        if (hasNode(pdc, BRANCH_LESNICTWO, "LES_MISTRZOSTWO")) bonusDropChance += 0.20;
        if (hasNode(pdc, BRANCH_NATURA, "NAT_MISTRZOSTWO")) bonusDropChance += 0.10;
        if (hasRare(pdc, "RARE_SIEK_SECOND_CHANCE")) bonusDropChance += 0.05;
        if (bonusDropChance > 0 && rnd.nextDouble() < bonusDropChance) {
            dropCopy(block, item);
        }

        // Trzask Konarów (I-V) - sąsiednie bloki drewna pękają razem z rąbanym
        int trzaskLevel = trzaskLevelOf(pdc);
        if (trzaskLevel > 0 && rnd.nextDouble() < 0.15 + trzaskLevel * 0.05) {
            breakRandomNeighbors(block, item, mat, trzaskLevel);
        }

        // Rdzeń Chaosu (rzadka) - do 3 sąsiednich bloków drewna naraz
        if (hasRare(pdc, "RARE_SIEK_CHAOS_CORE") && rnd.nextDouble() < 0.05) {
            breakRandomNeighbors(block, item, null, 3);
        }

        // Zielony Kciuk (I-IX) - dodatkowa sadzonka/jabłko, każdy węzeł niezależny
        for (int i = 1; i <= 9; i++) {
            if (hasNode(pdc, BRANCH_LESNICTWO, "LES_ZLOTY_KCIUK" + i) && rnd.nextDouble() < 0.05) {
                block.getWorld().dropItemNaturally(block.getLocation(),
                        new ItemStack(rnd.nextBoolean() ? Material.APPLE : Material.OAK_SAPLING));
            }
        }

        // Oko Handlarza (I-VII) / Ręka Tracza (I-IV) / Dotyk Midasa - bonusowa wypłata
        for (int i = 1; i <= 7; i++) {
            if (hasNode(pdc, BRANCH_LESNICTWO, "LES_OKO" + i) && rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
        }
        for (int i = 1; i <= 4; i++) {
            if (hasNode(pdc, BRANCH_LESNICTWO, "LES_TRACZ" + i) && rnd.nextDouble() < 0.08) payBonus(player, 6 + tierOf(pdc) * 4);
        }
        if (hasRare(pdc, "RARE_SIEK_MIDAS") && rnd.nextDouble() < 0.03) payBonus(player, 2 + tierOf(pdc));

        // Pasywne Szczęście - rośnie automatycznie z każdym poziomem (niezależnie od
        // wykupionych węzłów), wyraźnie słabiej niż ręczne Oko Handlarza (max +35%).
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        if (rnd.nextDouble() < level * 0.0006) payBonus(player, 2 + tierOf(pdc));

        // Duch Puszczy (I-IX) / Szczęśliwe Drzewo / Nieugięty Duch - bonusowe orby xp
        for (int i = 1; i <= 9; i++) {
            if (hasNode(pdc, BRANCH_NATURA, "NAT_DUCH" + i) && rnd.nextDouble() < 0.25) {
                spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 1 + rnd.nextInt(3));
            }
        }
        if (hasNode(pdc, BRANCH_NATURA, "NAT_SZCZESLIWE") && rnd.nextDouble() < 0.15) {
            spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 5 + rnd.nextInt(6));
        }
        if (hasRare(pdc, "RARE_SIEK_UNYIELDING_SPIRIT") && rnd.nextDouble() < 0.08) {
            spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 10 + rnd.nextInt(11));
        }

        // Magnes Drwala (rzadka)
        if (hasRare(pdc, "RARE_SIEK_MAGNET")) pullNearbyDrops(player, block.getLocation());

        // Błogosławieństwo Lasu (rzadka)
        if (hasRare(pdc, "RARE_SIEK_FOREST_BLESSING")) {
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + 0.5f));
            if (rnd.nextDouble() < 0.10) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false));
            }
        }

        // Leśny Głód II/III - powtarzalna, słabsza regeneracja sytości z drzewka
        if (hasNode(pdc, BRANCH_NATURA, "NAT_GLOD2")) {
            float amount = hasNode(pdc, BRANCH_NATURA, "NAT_GLOD3") ? 0.2f : 0.1f;
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + amount));
        }

        // Rytm Drwala (I-III) / Szał Drwala - kolejne kłody z rzędu dają chwilowy Pośpiech
        if (hasNode(pdc, BRANCH_CIECIE, "CIECIE_KOMBO1")) {
            handleKombo(player, pdc);
        }
    }

    private int trzaskLevelOf(PersistentDataContainer pdc) {
        if (hasNode(pdc, BRANCH_CIECIE, "CIECIE_TRZASK5")) return 5;
        if (hasNode(pdc, BRANCH_CIECIE, "CIECIE_TRZASK4")) return 4;
        if (hasNode(pdc, BRANCH_CIECIE, "CIECIE_TRZASK3")) return 3;
        if (hasNode(pdc, BRANCH_CIECIE, "CIECIE_TRZASK2")) return 2;
        if (hasNode(pdc, BRANCH_CIECIE, "CIECIE_TRZASK1")) return 1;
        return 0;
    }

    private void handleKombo(Player player, PersistentDataContainer pdc) {
        UUID id = player.getUniqueId();
        long windowMs = hasNode(pdc, BRANCH_CIECIE, "CIECIE_KOMBO3") ? 5000 : 3000;
        long now = System.currentTimeMillis();
        long last = streakLastBreak.getOrDefault(id, 0L);
        int streak = (now - last <= windowMs) ? streakCount.getOrDefault(id, 0) + 1 : 1;
        streakLastBreak.put(id, now);

        int threshold = hasNode(pdc, BRANCH_CIECIE, "CIECIE_KOMBO2") ? 2 : 3;
        if (streak >= threshold) {
            streak = 0;
            int amplifier = hasNode(pdc, BRANCH_CIECIE, "CIECIE_WSCIEKLOSC") ? 1 : 0;
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 80, amplifier, true, false, true));
        }
        streakCount.put(id, streak);
    }

    private void breakRandomNeighbors(Block origin, ItemStack tool, Material requiredType, int maxCount) {
        List<Block> candidates = new ArrayList<>();
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = origin.getRelative(face);
            Material type = neighbor.getType();
            boolean matches = requiredType != null ? type == requiredType : isLog(type);
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

    // ===================================================== Pasywne efekty ====

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean fallingBlock = cause == EntityDamageEvent.DamageCause.FALLING_BLOCK;
        boolean fall = cause == EntityDamageEvent.DamageCause.FALL;
        if (!fallingBlock && !fall) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(item, player)) return;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (fallingBlock && hasNode(pdc, BRANCH_NATURA, "NAT_LADOWANIE1")) {
            event.setCancelled(true);
        } else if (fall && hasNode(pdc, BRANCH_NATURA, "NAT_LADOWANIE2")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getFoodLevel() >= player.getFoodLevel()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(item, player)) return;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (hasNode(pdc, BRANCH_NATURA, "NAT_GLOD1")) {
            event.setCancelled(true);
        }
    }

    @Override
    protected PotionEffectType auraEffectType() {
        return PotionEffectType.HASTE;
    }

    @Override
    protected Integer auraAmplifierFor(PersistentDataContainer pdc) {
        if (hasNode(pdc, BRANCH_NATURA, "NAT_WIATR5")) return 4;
        if (hasNode(pdc, BRANCH_NATURA, "NAT_WIATR4")) return 3;
        if (hasNode(pdc, BRANCH_NATURA, "NAT_WIATR3")) return 2;
        if (hasNode(pdc, BRANCH_NATURA, "NAT_WIATR2")) return 1;
        if (hasNode(pdc, BRANCH_NATURA, "NAT_WIATR1")) return 0;
        return null;
    }

    // ==================================================== Statystyki/wygląd ====

    @Override
    protected Material materialForTier(int tier) {
        return switch (tier) {
            case 0 -> Material.WOODEN_AXE;
            case 1 -> Material.STONE_AXE;
            case 2 -> Material.IRON_AXE;
            case 3 -> Material.DIAMOND_AXE;
            default -> Material.NETHERITE_AXE;
        };
    }

    private int effLevelOf(PersistentDataContainer pdc) {
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        return rare.contains("RARE_SIEK_EFFICIENCY") ? 1 : 0;
    }

    private int fortLevelOf(PersistentDataContainer pdc) {
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        int fortLevel;
        if (hasNode(pdc, BRANCH_LESNICTWO, "LES_FORT3")) fortLevel = 3;
        else if (hasNode(pdc, BRANCH_LESNICTWO, "LES_FORT2")) fortLevel = 2;
        else if (hasNode(pdc, BRANCH_LESNICTWO, "LES_FORT1")) fortLevel = 1;
        else fortLevel = 0;
        if (rare.contains("RARE_SIEK_FORT4") && fortLevel > 0) fortLevel += 1;
        return fortLevel;
    }

    private int speedNodesOf(PersistentDataContainer pdc) {
        int count = 0;
        for (int i = 1; i <= 15; i++) {
            if (hasNode(pdc, BRANCH_CIECIE, "CIECIE_SPEED" + i)) count++;
        }
        return count;
    }

    private int hasteLevelOf(PersistentDataContainer pdc) {
        Integer amp = auraAmplifierFor(pdc);
        return amp == null ? 0 : amp + 1;
    }

    @Override
    protected void syncToolSpecificStats(ItemMeta meta, PersistentDataContainer pdc) {
        int effLevel = effLevelOf(pdc);
        int fortLevel = fortLevelOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);

        meta.removeEnchant(Enchantment.EFFICIENCY);
        if (effLevel > 0) meta.addEnchant(Enchantment.EFFICIENCY, effLevel, true);
        meta.removeEnchant(Enchantment.FORTUNE);
        if (fortLevel > 0) meta.addEnchant(Enchantment.FORTUNE, fortLevel, true);

        int speedNodes = speedNodesOf(pdc);
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

    @Override
    protected List<Component> statsLore(PersistentDataContainer pdc) {
        int effLevel = effLevelOf(pdc);
        int fortLevel = fortLevelOf(pdc);
        int speedNodes = speedNodesOf(pdc);
        int haste = hasteLevelOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Wydajność (enchant): " + (effLevel > 0 ? rzymskie(effLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Prędkość rąbania (drzewko): +" + (speedNodes * 3) + "%", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Fortuna (enchant): " + (fortLevel > 0 ? rzymskie(fortLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Wiatr Lasu: " + (haste > 0 ? rzymskie(haste) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
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
                var perk = findRarePerk(id);
                if (perk != null) {
                    lore.add(Component.text("• " + perk.displayName(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                }
            }
        }
        return lore;
    }
}