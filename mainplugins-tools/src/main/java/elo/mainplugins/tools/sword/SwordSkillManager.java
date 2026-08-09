package elo.mainplugins.tools.sword;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.tools.skilltree.ToolSkillManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Miecz na silniku ToolSkillManager (model kart). Mechaniki niezmienione względem
 * poprzedniej, węzłowej wersji - tylko czytane z liczników kart (cardCountOf) zamiast
 * pętli po bitmaskowych węzłach. Patrz SwordSkillTrees dla treści/rzadkości kart.
 * Dwa osobne zdarzenia napędzają progresję: onEntityDamage (co trafienie: exp,
 * obrażenia, aury, pieniądze, xp) i onEntityDeath (dopiero przy zabiciu: bonusowy/
 * zdublowany łup, bo dropy przeciwnika istnieją dopiero w tym momencie).
 */
public class SwordSkillManager extends ToolSkillManager {

    private static final double[] KREW_CHANCE = {0.06, 0.12, 0.16, 0.20, 0.24};

    private final NamespacedKey pkAttackSpeedModifierKey;
    private final NamespacedKey pkAttackSpeedPassiveModifierKey;

    private final Map<UUID, Long> streakLastHit = new HashMap<>();
    private final Map<UUID, Integer> streakCount = new HashMap<>();

    public SwordSkillManager(Plugin plugin, EconomyService economyService) {
        super(plugin, economyService, "sword", "Miecz", SwordSkillTrees.BRANCHES, SwordRarePerks.WSZYSTKIE, "miecz-hub.yml");
        this.pkAttackSpeedModifierKey = new NamespacedKey(plugin, "pk_miecz_atkspeed_bonus");
        this.pkAttackSpeedPassiveModifierKey = new NamespacedKey(plugin, "pk_miecz_atkspeed_passive");
    }

    // ============================================================= Walka ====

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(item, player)) return;

        ensureInitialized(item);
        applyOnHitPerks(player, target, item, event.getDamage());
        addExp(player, item);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player == null) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(item, player)) return;

        applyOnKillPerks(player, event, item);
    }

    private void applyOnHitPerks(Player player, LivingEntity target, ItemStack item, double baseDamage) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // Fala Ciosów - trafienie ma szansę zadać też obrażenia pobliskim wrogom
        int falaLevel = cardCountOf(pdc, "SILA_FALA");
        if (falaLevel > 0 && rnd.nextDouble() < 0.15 + falaLevel * 0.05) {
            cleaveNearby(target, player, baseDamage, falaLevel);
        }

        // Rdzeń Chaosu (rzadka) - natychmiastowe obrażenia do 3 pobliskich wrogów
        if (hasRare(pdc, "RARE_MIECZ_CHAOS_CORE") && rnd.nextDouble() < 0.05) {
            cleaveNearby(target, player, baseDamage, 3);
        }

        // Oko Łowcy / Ręka Najemnika / Dotyk Midasa - bonusowa wypłata
        int okoLowcy = cardCountOf(pdc, "PREC_OKO");
        for (int i = 0; i < okoLowcy; i++) {
            if (rnd.nextDouble() < 0.05) payBonus(player, 4 + tierOf(pdc) * 2);
        }
        int rekaNajemnika = cardCountOf(pdc, "PREC_NAJEMNIK");
        for (int i = 0; i < rekaNajemnika; i++) {
            if (rnd.nextDouble() < 0.08) payBonus(player, 6 + tierOf(pdc) * 4);
        }
        if (hasRare(pdc, "RARE_MIECZ_MIDAS") && rnd.nextDouble() < 0.03) payBonus(player, 2 + tierOf(pdc));

        // Pasywne Szczęście - rośnie automatycznie z każdym poziomem (niezależnie od
        // wykupionych kart), wyraźnie słabiej niż ręczne Oko Łowcy (max +60%).
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        if (rnd.nextDouble() < level * 0.0006) payBonus(player, 2 + tierOf(pdc));

        // Duch Bitwy / Szczęśliwy Cios / Nieugięty Duch - bonusowe orby xp
        int duchBitwy = cardCountOf(pdc, "DUCH_BITWA");
        for (int i = 0; i < duchBitwy; i++) {
            if (rnd.nextDouble() < 0.25) spawnXp(target.getLocation().add(0, 1, 0), 1 + rnd.nextInt(3));
        }
        if (cardCountOf(pdc, "DUCH_SZCZESLIWY") > 0 && rnd.nextDouble() < 0.15) {
            spawnXp(target.getLocation().add(0, 1, 0), 5 + rnd.nextInt(6));
        }
        if (hasRare(pdc, "RARE_MIECZ_UNYIELDING_SPIRIT") && rnd.nextDouble() < 0.08) {
            spawnXp(target.getLocation().add(0, 1, 0), 10 + rnd.nextInt(11));
        }

        // Błogosławieństwo Wojny (rzadka)
        if (hasRare(pdc, "RARE_MIECZ_WAR_BLESSING")) {
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + 0.5f));
            if (rnd.nextDouble() < 0.10) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false));
            }
        }

        // Głód Wojownika - poziom 2+ daje powtarzalną, słabszą regenerację sytości
        int glod = cardCountOf(pdc, "DUCH_GLOD");
        if (glod >= 2) {
            float amount = glod >= 3 ? 0.2f : 0.1f;
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + amount));
        }

        // Rytm Wojownika / Szał Wojownika - kolejne trafienia z rzędu dają chwilową Siłę
        // (handleRytm sama sprawdza, czy karta jest wykupiona)
        handleRytm(player, pdc);
    }

    private void applyOnKillPerks(Player player, EntityDeathEvent event, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location deathLoc = event.getEntity().getLocation();

        // Krew Bestii - pokonanie przeciwnika ma szansę dorzucić cenny surowiec/monety
        int krew = cardCountOf(pdc, "DUCH_KREW");
        if (krew > 0 && rnd.nextDouble() < KREW_CHANCE[krew - 1]) {
            if (rnd.nextBoolean()) {
                event.getDrops().add(rnd.nextBoolean() ? new ItemStack(Material.IRON_INGOT) : new ItemStack(Material.GOLD_NUGGET, 3));
            } else {
                payBonus(player, 3 + tierOf(pdc));
            }
        }

        // Potężny Cios / Precyzyjny Cios / Podwójny Łup / Mistrzostwo (x3) / Druga Szansa -
        // zdublowanie całego łupu (jedna wspólna szansa)
        double bonusLootChance = cardCountOf(pdc, "SILA_CIOS") * 0.10
                + cardCountOf(pdc, "PREC_CIOS") * 0.15
                + cardCountOf(pdc, "DUCH_LUP") * 0.10
                + (cardCountOf(pdc, "SILA_MISTRZOSTWO") > 0 ? 0.15 : 0)
                + (cardCountOf(pdc, "PREC_MISTRZOSTWO") > 0 ? 0.20 : 0)
                + (cardCountOf(pdc, "DUCH_MISTRZOSTWO") > 0 ? 0.10 : 0)
                + (hasRare(pdc, "RARE_MIECZ_SECOND_CHANCE") ? 0.05 : 0);
        if (bonusLootChance > 0 && rnd.nextDouble() < bonusLootChance && !event.getDrops().isEmpty()) {
            event.getDrops().addAll(new ArrayList<>(event.getDrops()));
        }

        // Krwawy Łup - dodatkowy szmaragd, każdy poziom karty to niezależny rzut
        int krwawyLup = cardCountOf(pdc, "PREC_LUP");
        for (int i = 0; i < krwawyLup; i++) {
            if (rnd.nextDouble() < 0.05) {
                event.getDrops().add(new ItemStack(Material.EMERALD));
            }
        }

        // Magnes Wojownika (rzadka) - dropy jeszcze nie istnieją w świecie w trakcie tego
        // eventu (dopiero po nim), więc przyciągamy je z jednotickowym opóźnieniem.
        if (hasRare(pdc, "RARE_MIECZ_MAGNET")) {
            Bukkit.getScheduler().runTask(plugin, () -> pullNearbyDrops(player, deathLoc));
        }
    }

    private void cleaveNearby(LivingEntity primaryTarget, Player attacker, double damage, int maxCount) {
        List<LivingEntity> candidates = new ArrayList<>();
        for (org.bukkit.entity.Entity e : primaryTarget.getNearbyEntities(3, 3, 3)) {
            if (e instanceof LivingEntity living && e != attacker && !living.isDead()) {
                candidates.add(living);
            }
        }
        java.util.Collections.shuffle(candidates);
        int hit = 0;
        for (LivingEntity living : candidates) {
            if (hit >= maxCount) break;
            living.damage(damage, attacker);
            hit++;
        }
    }

    private void handleRytm(Player player, PersistentDataContainer pdc) {
        int kombo = cardCountOf(pdc, "SILA_KOMBO");
        if (kombo <= 0) return;

        UUID id = player.getUniqueId();
        long windowMs = kombo >= 3 ? 5000 : 3000;
        long now = System.currentTimeMillis();
        long last = streakLastHit.getOrDefault(id, 0L);
        int streak = (now - last <= windowMs) ? streakCount.getOrDefault(id, 0) + 1 : 1;
        streakLastHit.put(id, now);

        int threshold = kombo >= 2 ? 2 : 3;
        if (streak >= threshold) {
            streak = 0;
            int amplifier = cardCountOf(pdc, "SILA_WSCIEKLOSC") > 0 ? 1 : 0;
            player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 80, amplifier, true, false, true));
        }
        streakCount.put(id, streak);
    }

    // ===================================================== Pasywne efekty ====

    @EventHandler
    public void onEntityDamageGravity(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean fallingBlock = cause == EntityDamageEvent.DamageCause.FALLING_BLOCK;
        boolean fall = cause == EntityDamageEvent.DamageCause.FALL;
        if (!fallingBlock && !fall) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isOwnedTool(item, player)) return;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        int ladowanie = cardCountOf(pdc, "DUCH_LADOWANIE");
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
        if (cardCountOf(pdc, "DUCH_GLOD") >= 1) {
            event.setCancelled(true);
        }
    }

    @Override
    protected PotionEffectType auraEffectType() {
        return PotionEffectType.STRENGTH;
    }

    @Override
    protected Integer auraAmplifierFor(PersistentDataContainer pdc) {
        int bojowy = cardCountOf(pdc, "DUCH_BOJOWY");
        return bojowy > 0 ? bojowy - 1 : null;
    }

    // ==================================================== Statystyki/wygląd ====

    @Override
    protected Material materialForTier(int tier) {
        return switch (tier) {
            case 0 -> Material.WOODEN_SWORD;
            case 1 -> Material.STONE_SWORD;
            case 2 -> Material.IRON_SWORD;
            case 3 -> Material.DIAMOND_SWORD;
            default -> Material.NETHERITE_SWORD;
        };
    }

    private int sharpnessLevelOf(PersistentDataContainer pdc) {
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        return rare.contains("RARE_MIECZ_SHARPNESS") ? 1 : 0;
    }

    private int lootLevelOf(PersistentDataContainer pdc) {
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        int lootLevel = cardCountOf(pdc, "PREC_LOOT");
        if (rare.contains("RARE_MIECZ_LOOT4") && lootLevel > 0) lootLevel += 1;
        return lootLevel;
    }

    private int strengthLevelOf(PersistentDataContainer pdc) {
        Integer amp = auraAmplifierFor(pdc);
        return amp == null ? 0 : amp + 1;
    }

    @Override
    protected void syncToolSpecificStats(ItemMeta meta, PersistentDataContainer pdc) {
        int sharpness = sharpnessLevelOf(pdc);
        int loot = lootLevelOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);

        meta.removeEnchant(Enchantment.SHARPNESS);
        if (sharpness > 0) meta.addEnchant(Enchantment.SHARPNESS, sharpness, true);
        meta.removeEnchant(Enchantment.LOOTING);
        if (loot > 0) meta.addEnchant(Enchantment.LOOTING, loot, true);

        int speedNodes = cardCountOf(pdc, "SILA_SPEED");
        meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
        if (speedNodes > 0) {
            meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                    pkAttackSpeedModifierKey, speedNodes * 0.03, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }
        if (level > 0) {
            meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                    pkAttackSpeedPassiveModifierKey, level * 0.0008, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }
    }

    @Override
    protected List<Component> statsLore(PersistentDataContainer pdc) {
        int sharpness = sharpnessLevelOf(pdc);
        int loot = lootLevelOf(pdc);
        int speedNodes = cardCountOf(pdc, "SILA_SPEED");
        int strength = strengthLevelOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Ostrość (enchant): " + (sharpness > 0 ? rzymskie(sharpness) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Szybkość ataku (karty): +" + (speedNodes * 3) + "%", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Grabież (enchant): " + (loot > 0 ? rzymskie(loot) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Duch Bojowy: " + (strength > 0 ? rzymskie(strength) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Pasywne (co poziom, niezależnie od kart):", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("+" + formatPercent(level * 0.08) + "% szybkości  •  +" + formatPercent(level * 0.06) + "% szczęścia",
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