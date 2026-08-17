package elo.mainplugins.tools.hoe;

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
 * Motyka na silniku ToolSkillManager (model kart). Mechaniki niezmienione względem
 * poprzedniej, węzłowej wersji - tylko czytane z liczników kart (cardCountOf) zamiast
 * pętli po bitmaskowych węzłach. Patrz HoeSkillTrees dla treści/rzadkości kart.
 */
public class HoeSkillManager extends ToolSkillManager {

    private static final double[] URODZAJ_CHANCE = {0.06, 0.12, 0.16, 0.20, 0.24};

    private static final Set<Material> UPRAWY = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART
    );

    private final NamespacedKey pkSpeedModifierKey;
    private final NamespacedKey pkSpeedPassiveModifierKey;

    private final Map<UUID, Long> streakLastBreak = new HashMap<>();
    private final Map<UUID, Integer> streakCount = new HashMap<>();

    public HoeSkillManager(Plugin plugin, EconomyService economyService) {
        super(plugin, economyService, "hoe", "Motyka", HoeSkillTrees.BRANCHES, HoeRarePerks.WSZYSTKIE, "motyka-hub.yml");
        this.pkSpeedModifierKey = new NamespacedKey(plugin, "pk_hoe_speed_bonus");
        this.pkSpeedPassiveModifierKey = new NamespacedKey(plugin, "pk_hoe_speed_passive");
    }

    // ============================================================ Zbiory ====

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(item, player)) return;

        ensureInitialized(item);
        applyHarvestPerks(player, event.getBlock(), item);
        addExp(player, item);
    }

    private boolean isUprawa(Material m) {
        return UPRAWY.contains(m);
    }

    private void applyHarvestPerks(Player player, Block block, ItemStack item) {
        Material mat = block.getType();
        if (!isUprawa(mat)) return;

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double scale = procScale(pdc);

        // Błogosławieństwo Urodzaju - podmienia plon zbieranej uprawy na cenny surowiec
        int urodzaj = cardCountOf(pdc, "NAT_URODZAJ");
        if (urodzaj > 0 && rnd.nextDouble() < URODZAJ_CHANCE[urodzaj - 1] * scale) {
            if (rnd.nextBoolean()) {
                ItemStack reward = rnd.nextBoolean() ? new ItemStack(Material.IRON_INGOT) : new ItemStack(Material.GOLD_NUGGET, 3);
                block.getWorld().dropItemNaturally(block.getLocation(), reward);
            } else {
                payBonus(player, 3 + tierOf(pdc));
            }
        }

        // Obfity Zbiór / Celny Zbiór / Podwójne Żniwo / Mistrzostwo (x3) / Druga Szansa -
        // dodatkowa kopia plonu (wszystko łączone jako niezależne szanse, patrz
        // combineChances/stackedChance - suma NIGDY nie przekracza 100%)
        double bonusDropChance = scale * combineChances(
                stackedChance(cardCountOf(pdc, "PLON_ZBIOR"), 0.10),
                stackedChance(cardCountOf(pdc, "AGRO_CELNY"), 0.15),
                stackedChance(cardCountOf(pdc, "NAT_OBFITOSC"), 0.10),
                cardCountOf(pdc, "PLON_MISTRZOSTWO") > 0 ? 0.15 : 0,
                cardCountOf(pdc, "AGRO_MISTRZOSTWO") > 0 ? 0.20 : 0,
                cardCountOf(pdc, "NAT_MISTRZOSTWO") > 0 ? 0.10 : 0,
                hasRare(pdc, "RARE_MOT_SECOND_CHANCE") ? 0.05 : 0
        );
        if (rnd.nextDouble() < bonusDropChance) {
            dropCopy(block, item);
        }

        // Żniwo Pola - sąsiednie dojrzałe uprawy tego samego typu zbierają się razem
        int zniwoLevel = cardCountOf(pdc, "PLON_ZNIWO");
        if (zniwoLevel > 0 && rnd.nextDouble() < (0.15 + zniwoLevel * 0.05) * scale) {
            breakRandomNeighbors(block, item, mat, zniwoLevel);
        }

        // Rdzeń Chaosu (rzadka) - do 3 sąsiednich upraw naraz
        if (hasRare(pdc, "RARE_MOT_CHAOS_CORE") && rnd.nextDouble() < 0.05 * scale) {
            breakRandomNeighbors(block, item, null, 3);
        }

        // Bogate Ziarno - dodatkowe nasiona, każdy poziom karty to niezależny rzut
        int bogateZiarno = cardCountOf(pdc, "AGRO_ZIARNO");
        for (int i = 0; i < bogateZiarno; i++) {
            if (rnd.nextDouble() < 0.05 * scale) {
                block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(Material.WHEAT_SEEDS));
            }
        }

        // Oko Farmera / Ręka Kupca / Dotyk Midasa - bonusowa wypłata
        int okoFarmera = cardCountOf(pdc, "AGRO_OKO");
        for (int i = 0; i < okoFarmera; i++) {
            if (rnd.nextDouble() < 0.05 * scale) payBonus(player, 4 + tierOf(pdc) * 2);
        }
        int rekaKupca = cardCountOf(pdc, "AGRO_KUPIEC");
        for (int i = 0; i < rekaKupca; i++) {
            if (rnd.nextDouble() < 0.08 * scale) payBonus(player, 6 + tierOf(pdc) * 4);
        }
        if (hasRare(pdc, "RARE_MOT_MIDAS") && rnd.nextDouble() < 0.03 * scale) payBonus(player, 2 + tierOf(pdc));

        // Pasywne Szczęście - rośnie automatycznie z każdym poziomem (niezależnie od
        // wykupionych kart), wyraźnie słabiej niż ręczne Oko Farmera (max +60%). Już ze
        // swojej natury skaluje się z poziomem - BEZ dodatkowego mnożnika scale.
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        if (rnd.nextDouble() < level * 0.0006) payBonus(player, 2 + tierOf(pdc));

        // Duch Pola / Szczęśliwy Plon / Nieugięty Duch - bonusowe orby xp
        int duchPola = cardCountOf(pdc, "NAT_DUCH");
        for (int i = 0; i < duchPola; i++) {
            if (rnd.nextDouble() < 0.25 * scale) spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 1 + rnd.nextInt(3));
        }
        if (cardCountOf(pdc, "NAT_SZCZESLIWY") > 0 && rnd.nextDouble() < 0.15 * scale) {
            spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 5 + rnd.nextInt(6));
        }
        if (hasRare(pdc, "RARE_MOT_UNYIELDING_SPIRIT") && rnd.nextDouble() < 0.08 * scale) {
            spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 10 + rnd.nextInt(11));
        }

        // Magnes Rolnika (rzadka)
        if (hasRare(pdc, "RARE_MOT_MAGNET")) pullNearbyDrops(player, block.getLocation());

        // Błogosławieństwo Pól (rzadka)
        if (hasRare(pdc, "RARE_MOT_FIELD_BLESSING")) {
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + 0.5f));
            if (rnd.nextDouble() < 0.10 * scale) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false));
            }
        }

        // Głód Rolnika - poziom 2+ daje powtarzalną, słabszą regenerację sytości
        int glod = cardCountOf(pdc, "NAT_GLOD");
        if (glod >= 2) {
            float amount = glod >= 3 ? 0.2f : 0.1f;
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + amount));
        }

        // Rytm Rolnika / Szał Żniwiarza - kolejne uprawy z rzędu dają chwilowy Pośpiech
        // (handleRytm sama sprawdza, czy karta jest wykupiona)
        handleRytm(player, pdc);
    }

    private void handleRytm(Player player, PersistentDataContainer pdc) {
        int rytm = cardCountOf(pdc, "PLON_RYTM");
        if (rytm <= 0) return;

        UUID id = player.getUniqueId();
        long windowMs = rytm >= 3 ? 5000 : 3000;
        long now = System.currentTimeMillis();
        long last = streakLastBreak.getOrDefault(id, 0L);
        int streak = (now - last <= windowMs) ? streakCount.getOrDefault(id, 0) + 1 : 1;
        streakLastBreak.put(id, now);

        int threshold = rytm >= 2 ? 2 : 3;
        if (streak >= threshold) {
            streak = 0;
            int amplifier = cardCountOf(pdc, "PLON_SZAL") > 0 ? 1 : 0;
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 80, amplifier, true, false, true));
        }
        streakCount.put(id, streak);
    }

    private void breakRandomNeighbors(Block origin, ItemStack tool, Material requiredType, int maxCount) {
        List<Block> candidates = new ArrayList<>();
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = origin.getRelative(face);
            Material type = neighbor.getType();
            boolean matches = requiredType != null ? type == requiredType : isUprawa(type);
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
            case 0 -> Material.WOODEN_HOE;
            case 1 -> Material.STONE_HOE;
            case 2 -> Material.IRON_HOE;
            case 3 -> Material.DIAMOND_HOE;
            default -> Material.NETHERITE_HOE;
        };
    }

    private int effLevelOf(PersistentDataContainer pdc) {
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        return rare.contains("RARE_MOT_EFFICIENCY") ? 1 : 0;
    }

    private int fortLevelOf(PersistentDataContainer pdc) {
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        int fortLevel = cardCountOf(pdc, "AGRO_FORT");
        if (rare.contains("RARE_MOT_FORT4") && fortLevel > 0) fortLevel += 1;
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

        int speedNodes = cardCountOf(pdc, "PLON_SPEED");
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
        int speedNodes = cardCountOf(pdc, "PLON_SPEED");
        int haste = hasteLevelOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Wydajność (enchant): " + (effLevel > 0 ? rzymskie(effLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Prędkość zbierania (karty): +" + (speedNodes * 3) + "%", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Fortuna (enchant): " + (fortLevel > 0 ? rzymskie(fortLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Wiatr Pól: " + (haste > 0 ? rzymskie(haste) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
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