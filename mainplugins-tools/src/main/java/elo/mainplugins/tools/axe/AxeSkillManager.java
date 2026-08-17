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
 * Siekiera na silniku ToolSkillManager (model kart). Mechaniki niezmienione względem
 * poprzedniej, węzłowej wersji - tylko czytane z liczników kart (cardCountOf) zamiast
 * pętli po bitmaskowych węzłach. Patrz AxeSkillTrees dla treści/rzadkości kart.
 */
public class AxeSkillManager extends ToolSkillManager {

    private static final double[] DUCH_DRZEWA_CHANCE = {0.06, 0.12, 0.16, 0.20, 0.24};

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
        double scale = procScale(pdc);

        // Duch Drzewa - podmienia plon rąbanego drewna na cenny surowiec
        int duchDrzewa = cardCountOf(pdc, "NAT_DUCHDRZEWA");
        if (duchDrzewa > 0 && rnd.nextDouble() < scaledChance(pdc, DUCH_DRZEWA_CHANCE[duchDrzewa - 1])) {
            if (rnd.nextBoolean()) {
                ItemStack reward = rnd.nextBoolean() ? new ItemStack(Material.IRON_INGOT) : new ItemStack(Material.GOLD_NUGGET, 3);
                block.getWorld().dropItemNaturally(block.getLocation(), reward);
            } else {
                payBonus(player, 3 + tierOf(pdc));
            }
        }

        // Ostre Ostrze / Precyzyjny Cios / Podwójny Pień / Mistrzostwo (x3) / Druga Szansa -
        // dodatkowa kopia plonu (wszystko łączone jako niezależne szanse, patrz
        // combineChances/stackedChance - suma NIGDY nie przekracza 100%)
        double bonusDropChance = scale * combineChances(
                stackedChance(cardCountOf(pdc, "CIECIE_OSTRZE"), 0.10),
                stackedChance(cardCountOf(pdc, "LES_CIOS"), 0.15),
                stackedChance(cardCountOf(pdc, "NAT_PIEN"), 0.10),
                cardCountOf(pdc, "CIECIE_MISTRZOSTWO") > 0 ? 0.15 : 0,
                cardCountOf(pdc, "LES_MISTRZOSTWO") > 0 ? 0.20 : 0,
                cardCountOf(pdc, "NAT_MISTRZOSTWO") > 0 ? 0.10 : 0,
                hasRare(pdc, "RARE_SIEK_SECOND_CHANCE") ? 0.05 : 0
        );
        if (rnd.nextDouble() < bonusDropChance) {
            dropCopy(block, item);
        }

        // Trzask Konarów - sąsiednie bloki drewna pękają razem z rąbanym
        int trzaskLevel = cardCountOf(pdc, "CIECIE_TRZASK");
        if (trzaskLevel > 0 && rnd.nextDouble() < scaledChance(pdc, 0.15 + trzaskLevel * 0.05)) {
            breakRandomNeighbors(block, item, mat, trzaskLevel);
        }

        // Rdzeń Chaosu (rzadka) - do 3 sąsiednich bloków drewna naraz
        if (hasRare(pdc, "RARE_SIEK_CHAOS_CORE") && rnd.nextDouble() < scaledChance(pdc, 0.05)) {
            breakRandomNeighbors(block, item, null, 3);
        }

        // Zielony Kciuk - dodatkowa sadzonka/jabłko, każdy poziom karty to niezależny rzut
        int zielonyKciuk = cardCountOf(pdc, "LES_ZLOTYKCIUK");
        for (int i = 0; i < zielonyKciuk; i++) {
            if (rnd.nextDouble() < 0.05 * scale) {
                block.getWorld().dropItemNaturally(block.getLocation(),
                        new ItemStack(rnd.nextBoolean() ? Material.APPLE : Material.OAK_SAPLING));
            }
        }

        // Oko Handlarza / Ręka Tracza / Dotyk Midasa - bonusowa wypłata
        int okoHandlarza = cardCountOf(pdc, "LES_OKO");
        for (int i = 0; i < okoHandlarza; i++) {
            if (rnd.nextDouble() < 0.05 * scale) payBonus(player, 4 + tierOf(pdc) * 2);
        }
        int rekaTracza = cardCountOf(pdc, "LES_TRACZ");
        for (int i = 0; i < rekaTracza; i++) {
            if (rnd.nextDouble() < 0.08 * scale) payBonus(player, 6 + tierOf(pdc) * 4);
        }
        if (hasRare(pdc, "RARE_SIEK_MIDAS") && rnd.nextDouble() < 0.03 * scale) payBonus(player, 2 + tierOf(pdc));

        // Pasywne Szczęście - rośnie automatycznie z każdym poziomem (niezależnie od
        // wykupionych kart), wyraźnie słabiej niż ręczne Oko Handlarza (max +60%). Już ze
        // swojej natury skaluje się z poziomem - BEZ dodatkowego mnożnika scale.
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        if (rnd.nextDouble() < level * 0.0006) payBonus(player, 2 + tierOf(pdc));

        // Duch Puszczy / Szczęśliwe Drzewo / Nieugięty Duch - bonusowe orby xp
        int duchPuszczy = cardCountOf(pdc, "NAT_DUCH");
        for (int i = 0; i < duchPuszczy; i++) {
            if (rnd.nextDouble() < 0.25 * scale) spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 1 + rnd.nextInt(3));
        }
        if (cardCountOf(pdc, "NAT_SZCZESLIWE") > 0 && rnd.nextDouble() < 0.15 * scale) {
            spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 5 + rnd.nextInt(6));
        }
        if (hasRare(pdc, "RARE_SIEK_UNYIELDING_SPIRIT") && rnd.nextDouble() < 0.08 * scale) {
            spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 10 + rnd.nextInt(11));
        }

        // Magnes Drwala (rzadka)
        if (hasRare(pdc, "RARE_SIEK_MAGNET")) pullNearbyDrops(player, block.getLocation());

        // Błogosławieństwo Lasu (rzadka)
        if (hasRare(pdc, "RARE_SIEK_FOREST_BLESSING")) {
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + 0.5f));
            if (rnd.nextDouble() < 0.10 * scale) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false));
            }
        }

        // Leśny Głód - poziom 2+ daje powtarzalną, słabszą regenerację sytości
        int glod = cardCountOf(pdc, "NAT_GLOD");
        if (glod >= 2) {
            float amount = glod >= 3 ? 0.2f : 0.1f;
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + amount));
        }

        // Rytm Drwala / Szał Drwala - kolejne kłody z rzędu dają chwilowy Pośpiech
        // (handleKombo sama sprawdza, czy karta jest wykupiona - patrz kombo<=0 return)
        handleKombo(player, pdc);
    }

    private void handleKombo(Player player, PersistentDataContainer pdc) {
        int kombo = cardCountOf(pdc, "CIECIE_KOMBO");
        if (kombo <= 0) return;

        UUID id = player.getUniqueId();
        long windowMs = kombo >= 3 ? 5000 : 3000;
        long now = System.currentTimeMillis();
        long last = streakLastBreak.getOrDefault(id, 0L);
        int streak = (now - last <= windowMs) ? streakCount.getOrDefault(id, 0) + 1 : 1;
        streakLastBreak.put(id, now);

        int threshold = kombo >= 2 ? 2 : 3;
        if (streak >= threshold) {
            streak = 0;
            int amplifier = cardCountOf(pdc, "CIECIE_WSCIEKLOSC") > 0 ? 1 : 0;
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
        int ladowanie = cardCountOf(pdc, "NAT_LADOWANIE");
        if (fallingBlock && ladowanie >= 1) {
            event.setCancelled(true);
        } else if (fall && ladowanie >= 2) {
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
        if (cardCountOf(pdc, "NAT_GLOD") >= 1) {
            event.setCancelled(true);
        }
    }

    @Override
    protected PotionEffectType auraEffectType() {
        return PotionEffectType.HASTE;
    }

    @Override
    protected Integer auraAmplifierFor(PersistentDataContainer pdc) {
        int wiatr = cardCountOf(pdc, "NAT_WIATR");
        return wiatr > 0 ? wiatr - 1 : null;
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
        int fortLevel = cardCountOf(pdc, "LES_FORT");
        if (rare.contains("RARE_SIEK_FORT4") && fortLevel > 0) fortLevel += 1;
        return fortLevel;
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

        int speedNodes = cardCountOf(pdc, "CIECIE_SPEED");
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
        int speedNodes = cardCountOf(pdc, "CIECIE_SPEED");
        int haste = hasteLevelOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Wydajność (enchant): " + (effLevel > 0 ? rzymskie(effLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Prędkość rąbania (karty): +" + (speedNodes * 3) + "%", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Fortuna (enchant): " + (fortLevel > 0 ? rzymskie(fortLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Wiatr Lasu: " + (haste > 0 ? rzymskie(haste) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Pasywne (co poziom, niezależnie od kart):", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("+" + formatPercent(level * 0.08) + "% prędkości  •  +" + formatPercent(level * 0.06) + "% szczęścia",
                NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Rzadkie perki (" + rare.size() + "):", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        if (rare.isEmpty()) {
            lore.add(Component.text("Brak", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            for (String id : rare) {
                var perk = findRarePerk(pdc, id);
                if (perk != null) {
                    lore.add(Component.text("• " + perk.displayName(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                }
            }
        }
        return lore;
    }
}