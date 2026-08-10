package elo.mainplugins.tools.pickaxe;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.GuiUtils;
import elo.mainplugins.tools.pickaxe.BrukSurowce.SurowiecDrop;
import elo.mainplugins.tools.skilltree.ToolSkillManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Kilof na silniku ToolSkillManager (model kart) - tak samo jak siekiera/motyka/miecz.
 * Mechaniki niezmienione względem poprzedniej, węzłowej wersji - tylko czytane z
 * liczników kart (cardCountOf) zamiast bitmaskowych węzłów kup-po-kolei. Patrz
 * PickaxeSkillTrees dla treści/rzadkości kart, PickaxeRarePerks dla puli legendarnej.
 *
 * Różnice względem pozostałych narzędzi:
 * 1) kilof ma dodatkową ikonkę w hubie (przełącznik/konfiguracja "Bonus z Bruku", patrz
 *    BrukSurowceManager) - stąd nadpisane populateExtraHubIcons/handleExtraHubClick/
 *    initializeToolSpecific z ToolSkillManager, plus własny ekran (BrukConfigHolder).
 * 2) na tym Skyblocku jedyny sposób zdobycia "rudy" to Bonus z Bruku (brak realnego
 *    świata z blokami rudy) - dlatego rollBonusZBruku jest wołany na SAMYM POCZĄTKU
 *    applyMiningPerks, a jego wynik (nie isOre(mat)/isRareOre(mat)) zasila karty rudowe
 *    (Oko Sokoła, Rezonans Rudy, Serce Głębi, Szczęśliwa Ruda, Bystre Oko/Ręka Jubilera).
 */
public class PickaxeSkillManager extends ToolSkillManager {

    private final BrukSurowceManager brukSurowceManager;
    private final NamespacedKey pkBrukOn;
    private final NamespacedKey pkBrukDisabled;
    private final NamespacedKey pkSpeedModifierKey;
    private final NamespacedKey pkSpeedPassiveModifierKey;
    private final NamespacedKey pkSpeedSynergyKey;

    private int slotBrukInfo;

    private final Map<UUID, Long> streakLastBreak = new HashMap<>();
    private final Map<UUID, Integer> streakCount = new HashMap<>();

    public PickaxeSkillManager(Plugin plugin, EconomyService economyService, BrukSurowceManager brukSurowceManager) {
        super(plugin, economyService, "pickaxe", "Kilof", PickaxeSkillTrees.BRANCHES, PickaxeRarePerks.WSZYSTKIE,
                PickaxeSkillTrees.SYNERGIE, "kilof-hub.yml");
        this.brukSurowceManager = brukSurowceManager;
        this.pkBrukOn = new NamespacedKey(plugin, "pk_bruk_on");
        this.pkBrukDisabled = new NamespacedKey(plugin, "pk_bruk_disabled");
        this.pkSpeedModifierKey = new NamespacedKey(plugin, "pk_speed_bonus");
        this.pkSpeedPassiveModifierKey = new NamespacedKey(plugin, "pk_speed_passive");
        this.pkSpeedSynergyKey = new NamespacedKey(plugin, "pk_speed_synergy");
        wczytajBrukSlot();
    }

    /** Slot dodatkowej ikonki "Bonus z Bruku" - jedyny klucz w kilof-hub.yml nieznany bazowemu ToolSkillManager. */
    private void wczytajBrukSlot() {
        File plik = new File(plugin.getDataFolder(), "kilof-hub.yml");
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);
        slotBrukInfo = clampSlot(cfg.getInt("sloty.bonus_bruku", 21));
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
    }

    private void applyMiningPerks(Player player, Block block, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Material mat = block.getType();
        boolean stoneLike = mat == Material.STONE || mat == Material.COBBLESTONE
                || mat == Material.DEEPSLATE || mat == Material.COBBLED_DEEPSLATE;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double scale = procScale(pdc);

        // Bonus z Bruku - JEDEN roll na to kopnięcie, ZANIM cokolwiek innego sprawdza
        // ore/rareOre poniżej. Na tym Skyblocku jedyny sposób zdobycia "rudy" to właśnie
        // ten mechanizm (brak realnego świata z blokami rudy) - więc karty rudowe (Oko
        // Sokoła, Rezonans Rudy, Serce Głębi, Szczęśliwa Ruda, Bystre Oko/Ręka Jubilera)
        // musiały zostać przepięte z isOre(mat)/isRareOre(mat) na WYNIK tego rolla. Realny
        // blok rudy (isOre/isRareOre) zostaje jako fallback - gdyby kiedyś powstał sposób
        // na kopanie prawdziwej rudy (np. przez questy), te karty ożyją też od niego.
        SurowiecDrop bonusSurowiec = rollBonusZBruku(player, block, mat, pdc);
        boolean ore = isOre(mat) || bonusSurowiec != null;
        boolean rareOre = isRareOre(mat) || (bonusSurowiec != null && bonusSurowiec.cenny());

        // Klucz Górnika (legendarna) - śmiesznie mała, ale realna szansa przy DOWOLNYM
        // bloku. Cichy no-op, jeśli mainplugins-crates nie jest wgrany (opcjonalny serwis).
        if (hasRare(pdc, "RARE_KIL_MINER_KEY") && rnd.nextDouble() < 0.00001 * scale) {
            grantKluczGornika(player);
        }

        // Kamień Filozoficzny - podmienia plon kamienia/brukowca, szansa rośnie z poziomem karty
        int philStone = cardCountOf(pdc, "MAG_PHILOSOPHERS_STONE");
        if (stoneLike && philStone > 0) {
            double chance = switch (philStone) {
                case 1 -> 0.06;
                case 2 -> 0.12;
                case 3 -> 0.16;
                case 4 -> 0.20;
                default -> 0.24;
            } * scale;
            if (rnd.nextDouble() < chance) {
                if (rnd.nextBoolean()) {
                    ItemStack reward = rnd.nextBoolean() ? new ItemStack(Material.IRON_INGOT) : new ItemStack(Material.GOLD_NUGGET, 3);
                    block.getWorld().dropItemNaturally(block.getLocation(), reward);
                } else {
                    payBonus(player, 3 + tierOf(pdc));
                }
            }
        }

        // Żelazna Pięść / Precyzyjne Uderzenie / Wrota Głębi / Mistrzostwo (x3) / Perfekcja /
        // Druga Szansa - dodatkowa kopia plonu (wszystko łączone jako niezależne szanse,
        // patrz combineChances/stackedChance - suma NIGDY nie przekracza 100%). WAŻNE: fizyczna
        // duplikacja (dropCopy) działa TYLKO na kamieniu/bruku/deepslate - na rudzie te same
        // karty dają w zamian bonus $, żeby nie dublować diamentu/szmaragdu za darmo.
        double bonusDropChance = scale * combineChances(
                stackedChance(cardCountOf(pdc, "WYD_IRONGRIP"), 0.10),
                stackedChance(cardCountOf(pdc, "PREC_CRIT"), 0.15),
                stackedChance(cardCountOf(pdc, "MAG_DEPTHS_GATE"), 0.10),
                cardCountOf(pdc, "PREC_PERFECTION") > 0 ? 0.20 : 0,
                cardCountOf(pdc, "PREC_MASTERY") > 0 ? 0.20 : 0,
                cardCountOf(pdc, "WYD_MASTERY") > 0 ? 0.15 : 0,
                cardCountOf(pdc, "MAG_MASTERY") > 0 ? 0.10 : 0,
                hasRare(pdc, "RARE_KIL_SECOND_CHANCE") ? 0.05 : 0
        );
        if (rnd.nextDouble() < bonusDropChance) {
            if (stoneLike) {
                dropCopy(block, item);
            } else if (ore) {
                payBonus(player, 6 + tierOf(pdc) * 3);
            }
        }

        // Oko Sokoła - na rzadkich rudach bonus $ (NIE dodatkowy plon - patrz komentarz wyżej)
        if (rareOre && rnd.nextDouble() < scale * stackedChance(cardCountOf(pdc, "PREC_HAWK_EYE"), 0.10)) {
            payBonus(player, 6 + tierOf(pdc) * 4);
        }

        // Szczęśliwa Ruda - spory, niezależny wybuch orbów exp
        if (cardCountOf(pdc, "MAG_LUCKY_ORE") > 0 && rareOre && rnd.nextDouble() < 0.15 * scale) {
            spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 5 + rnd.nextInt(6));
        }

        // Rezonans Rudy - sąsiednie bloki bruku pękają razem z kopanym, gdy trafienie
        // wypadnie na "rudę" (patrz ore/rareOre wyżej) - to REALNE bloki, nie duplikacja
        // (patrz komentarz przy bonusDropChance); poziom karty podnosi zarówno szansę
        // bazowego triggera, jak i liczbę sąsiadów. Łowca Żył (kombinacja PREC_HAWK_EYE+
        // WYD_RESONANCE) - na rzadkich rudach trigger gwarantowany.
        int resonance = cardCountOf(pdc, "WYD_RESONANCE");
        if (ore && resonance > 0) {
            boolean guaranteed = rareOre && hasSynergy(pdc, "SYN_LOWCA_ZYL");
            if (guaranteed || rnd.nextDouble() < scale * (0.15 + resonance * 0.05)) {
                breakRandomNeighbors(block, item, mat, resonance);
            }
        }

        // Rdzeń Chaosu (rzadka) - do 3 sąsiednich bloków kamienia/rudy naraz
        if (hasRare(pdc, "RARE_KIL_CHAOS_CORE") && rnd.nextDouble() < 0.05 * scale) {
            breakRandomNeighbors(block, item, null, 3);
        }

        // Serce Głębi (kombinacja WYD_RESONANCE + MAG_PHILOSOPHERS_STONE + MAG_DEPTHS_GATE,
        // wszystkie na maksie) - gwarantowany bonus $ z KAŻDEGO bloku rudy, NIE fizyczna
        // duplikacja (patrz komentarz przy bonusDropChance) - to i tak endgame'owa synergia.
        if (ore && hasSynergy(pdc, "SYN_GLEBIA")) {
            payBonus(player, scale * (20 + tierOf(pdc) * 8));
        }

        // Bystre Oko / Ręka Jubilera / Dotyk Midasa - bonusowa wypłata (Chciwe Ręce -
        // kombinacja PREC_KEEN_EYE+PREC_JEWELER - mnoży obie wypłaty x1.5)
        if (ore) {
            double chciweRece = hasSynergy(pdc, "SYN_CHCIWOSC") ? 1.5 : 1.0;
            int keenEye = cardCountOf(pdc, "PREC_KEEN_EYE");
            for (int i = 0; i < keenEye; i++) {
                if (rnd.nextDouble() < 0.05 * scale) payBonus(player, (4 + tierOf(pdc) * 2) * chciweRece);
            }
            int jeweler = cardCountOf(pdc, "PREC_JEWELER");
            for (int i = 0; i < jeweler; i++) {
                if (rnd.nextDouble() < 0.08 * scale) payBonus(player, (6 + tierOf(pdc) * 4) * chciweRece);
            }

            // Pasywne Szczęście - rośnie automatycznie z każdym poziomem (niezależnie od
            // wykupionych kart), wyraźnie słabiej niż ręczne Bystre Oko (max +60%). Już
            // ze swojej natury skaluje się z poziomem - BEZ dodatkowego mnożnika scale.
            int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
            if (rnd.nextDouble() < level * 0.0006) payBonus(player, 2 + tierOf(pdc));
        }
        if (hasRare(pdc, "RARE_KIL_MIDAS") && rnd.nextDouble() < 0.03 * scale) payBonus(player, 2 + tierOf(pdc));

        // Iskra Many / Nieugięty Duch - bonusowe orby xp (Duch Kopalni - kombinacja
        // WYD_IRONGRIP+MAG_SPARK - podnosi szansę Iskry Many z 25% do 40%)
        double sparkChance = scale * (hasSynergy(pdc, "SYN_DUCH_KOPALNI") ? 0.40 : 0.25);
        int spark = cardCountOf(pdc, "MAG_SPARK");
        for (int i = 0; i < spark; i++) {
            if (rnd.nextDouble() < sparkChance) spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 1 + rnd.nextInt(3));
        }
        if (hasRare(pdc, "RARE_KIL_UNYIELDING_SPIRIT") && rnd.nextDouble() < 0.08 * scale) {
            spawnXp(block.getLocation().add(0.5, 0.5, 0.5), 10 + rnd.nextInt(11));
        }

        // Magnes Górnika (rzadka)
        if (hasRare(pdc, "RARE_KIL_MAGNET")) pullNearbyDrops(player, block.getLocation());

        // Błogosławieństwo Głębi (rzadka)
        if (hasRare(pdc, "RARE_KIL_DEPTHS_BLESSING")) {
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + 0.5f));
            if (rnd.nextDouble() < 0.10) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false));
            }
        }

        // Nienasycenie - poziom 2+ daje powtarzalną, słabszą regenerację sytości
        int insatiable = cardCountOf(pdc, "MAG_INSATIABLE");
        if (insatiable >= 2) {
            float amount = insatiable >= 3 ? 0.2f : 0.1f;
            player.setSaturation((float) Math.min(20.0, player.getSaturation() + amount));
        }

        // Impet Górnika / Adrenalina Górnika - kopnięcia z rzędu dają chwilowy Pośpiech
        // (handleMomentum sama sprawdza, czy karta jest wykupiona - patrz momentum<=0 return)
        handleMomentum(player, pdc);
    }

    /**
     * Bonus z Bruku - patrz komentarz klasowy BrukSurowceManager. Liczy trigger + losuje
     * konkretny surowiec (respektując przełącznik pk_bruk_on i ręcznie wyłączone surowce),
     * OD RAZU wypłaca go graczowi (drop + dźwięk/actionbar) i zwraca wynik - żeby
     * applyMiningPerks mogło na tej podstawie zasilić karty "rudowe" (ore/rareOre).
     * Zwraca null, jeśli bonus jest wyłączony, nie trafił, albo blok to nie bruk.
     */
    private SurowiecDrop rollBonusZBruku(Player player, Block block, Material mat, PersistentDataContainer pdc) {
        if (mat != Material.COBBLESTONE) return null;
        boolean enabled = pdc.getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
        if (!enabled) return null;

        int tier = tierOf(pdc);
        double szansa = brukSurowceManager.calkowitaSzansa(tier, cardCountOf(pdc, "MAG_BRUK_SZCZESCIE"));
        if (szansa <= 0 || ThreadLocalRandom.current().nextDouble(100.0) >= szansa) return null;

        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tier);
        if (pula.isEmpty()) return null;
        SurowiecDrop wylosowany = brukSurowceManager.losujWazony(pula, cardCountOf(pdc, "MAG_BRUK_INSTYNKT"), brukSurowceManager.wylaczoneSurowce(pdc));
        if (wylosowany == null) return null;

        Location loc = block.getLocation();
        block.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), new ItemStack(wylosowany.item(), wylosowany.ilosc()));
        player.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1f);
        player.sendActionBar(Component.text("W bruku znalazłeś " + wylosowany.nazwa() + "!", NamedTextColor.AQUA));
        return wylosowany;
    }

    private void handleMomentum(Player player, PersistentDataContainer pdc) {
        int momentum = cardCountOf(pdc, "WYD_MOMENTUM");
        if (momentum <= 0) return;

        UUID id = player.getUniqueId();
        long windowMs = momentum >= 3 ? 5000 : 3000;
        long now = System.currentTimeMillis();
        long last = streakLastBreak.getOrDefault(id, 0L);
        int streak = (now - last <= windowMs) ? streakCount.getOrDefault(id, 0) + 1 : 1;
        streakLastBreak.put(id, now);

        int threshold = momentum >= 2 ? 2 : 3;
        if (streak >= threshold) {
            streak = 0;
            int amplifier = cardCountOf(pdc, "WYD_ADRENALINE") > 0 ? 1 : 0;
            player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 80, amplifier, true, false, true));
        }
        streakCount.put(id, streak);
    }

    private void breakRandomNeighbors(Block origin, ItemStack tool, Material requiredType, int maxCount) {
        List<Block> candidates = new ArrayList<>();
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
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

    private boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    private boolean isRareOre(Material m) {
        return switch (m) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE, DEEPSLATE_EMERALD_ORE, ANCIENT_DEBRIS -> true;
            default -> false;
        };
    }

    /**
     * Klucz Górnika (rzadka) - opcjonalny bonus, cichy no-op jeśli mainplugins-crates nie
     * jest wgrany/włączony (patrz CoreAPI#getCrateService, ten sam wzorzec co
     * FishingManager#rzucBonusowaSkrzynke).
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
        int gravityWard = cardCountOf(pdc, "MAG_GRAVITY_WARD");
        if (fallingBlock && gravityWard >= 1) {
            event.setCancelled(true);
        } else if (fall && gravityWard >= 2) {
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
        if (cardCountOf(pdc, "MAG_INSATIABLE") >= 1) {
            event.setCancelled(true);
        }
    }

    @Override
    protected PotionEffectType auraEffectType() {
        return PotionEffectType.HASTE;
    }

    @Override
    protected Integer auraAmplifierFor(PersistentDataContainer pdc) {
        int haste = cardCountOf(pdc, "MAG_HASTE");
        return haste > 0 ? haste - 1 : null;
    }

    // ==================================================== Statystyki/wygląd ====

    @Override
    protected Material materialForTier(int tier) {
        return switch (tier) {
            case 0 -> Material.WOODEN_PICKAXE;
            case 1 -> Material.STONE_PICKAXE;
            case 2 -> Material.IRON_PICKAXE;
            case 3 -> Material.DIAMOND_PICKAXE;
            default -> Material.NETHERITE_PICKAXE;
        };
    }

    private int effLevelOf(PersistentDataContainer pdc) {
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        return rare.contains("RARE_KIL_EFFICIENCY") ? 1 : 0;
    }

    private int fortLevelOf(PersistentDataContainer pdc) {
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));
        int fortLevel = cardCountOf(pdc, "PREC_FORT");
        if (rare.contains("RARE_KIL_FORT4") && fortLevel > 0) fortLevel += 1;
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

        int speedCards = cardCountOf(pdc, "WYD_SPEED");
        meta.removeAttributeModifier(Attribute.BLOCK_BREAK_SPEED);
        if (speedCards > 0) {
            meta.addAttributeModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(
                    pkSpeedModifierKey, speedCards * 0.03, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }
        if (level > 0) {
            meta.addAttributeModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(
                    pkSpeedPassiveModifierKey, level * 0.0008, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }
        // Błyskawiczny Górnik (kombinacja WYD_SPEED≥10 + MAG_HASTE≥3) - dodatkowe +5%,
        // osobny modifier żeby nie mieszać się z pkSpeedModifierKey powyżej.
        if (hasSynergy(pdc, "SYN_SPEED")) {
            meta.addAttributeModifier(Attribute.BLOCK_BREAK_SPEED, new AttributeModifier(
                    pkSpeedSynergyKey, 0.05, AttributeModifier.Operation.ADD_SCALAR, EquipmentSlotGroup.MAINHAND));
        }
    }

    @Override
    protected List<Component> statsLore(PersistentDataContainer pdc) {
        int effLevel = effLevelOf(pdc);
        int fortLevel = fortLevelOf(pdc);
        int speedCards = cardCountOf(pdc, "WYD_SPEED");
        int haste = hasteLevelOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        Set<String> rare = csvToSet(pdc.getOrDefault(pkRare, PersistentDataType.STRING, ""));

        List<Component> lore = new ArrayList<>();
        boolean synSpeed = hasSynergy(pdc, "SYN_SPEED");
        lore.add(Component.text("Wydajność (enchant): " + (effLevel > 0 ? rzymskie(effLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Prędkość kopania (karty): +" + (speedCards * 3 + (synSpeed ? 5 : 0)) + "%"
                + (synSpeed ? " (w tym +5% z kombinacji)" : ""), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Fortuna (enchant): " + (fortLevel > 0 ? rzymskie(fortLevel) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Aura Pośpiechu: " + (haste > 0 ? rzymskie(haste) : "Brak"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
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
                var perk = findRarePerk(id);
                if (perk != null) {
                    lore.add(Component.text("• " + perk.displayName(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                }
            }
        }
        return lore;
    }

    /**
     * Te same 4 statystyki co statsLore, ale jako fizyczne bloczki (menu Informacje) -
     * ilość w stosie odzwierciedla poziom danej statystyki (np. Fortuna III = 3 szmaragdy).
     * Zero-owe statystyki są pomijane (jak brakujące rzadkie perki - patrz rarePerkBlocks).
     */
    @Override
    protected List<ItemStack> statsBlocks(PersistentDataContainer pdc) {
        int effLevel = effLevelOf(pdc);
        int fortLevel = fortLevelOf(pdc);
        int speedCards = cardCountOf(pdc, "WYD_SPEED");
        int haste = hasteLevelOf(pdc);
        int level = pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
        boolean synSpeed = hasSynergy(pdc, "SYN_SPEED");
        int totalSpeedPercent = speedCards * 3 + (synSpeed ? 5 : 0);

        List<ItemStack> blocks = new ArrayList<>();
        if (effLevel > 0) {
            blocks.add(statBlock(Material.NETHERITE_PICKAXE, "Wydajność " + rzymskie(effLevel),
                    "Prawdziwy enchant (z rzadkiego perku).", effLevel));
        }
        if (fortLevel > 0) {
            blocks.add(statBlock(Material.EMERALD, "Fortuna " + rzymskie(fortLevel),
                    "Prawdziwy enchant (karty + ew. rzadki perk).", fortLevel));
        }
        blocks.add(statBlock(Material.FEATHER, "Prędkość Kopania +" + totalSpeedPercent + "%",
                speedCards + " kart" + (synSpeed ? " + kombinacja Błyskawiczny Górnik (+5%)" : ""), Math.max(1, speedCards)));
        if (haste > 0) {
            blocks.add(statBlock(Material.AMETHYST_SHARD, "Aura Pośpiechu " + rzymskie(haste),
                    "Stały efekt, dopóki trzymasz kilof.", haste));
        }
        blocks.add(statBlock(Material.GOLD_NUGGET, "Pasywne Szczęście",
                "+" + formatPercent(level * 0.08) + "% prędkości  •  +" + formatPercent(level * 0.06) + "% szczęścia (co poziom, niezależnie od kart)", 1));
        return blocks;
    }

    private ItemStack statBlock(Material material, String name, String opis, int amount) {
        ItemStack icon = GuiUtils.namedItem(material,
                Component.text(name, NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                Component.text(opis, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        icon.setAmount(Math.max(1, Math.min(64, amount)));
        return icon;
    }

    // ==================================================== Bonus z bruku ====
    // Jedyna funkcja bez odpowiednika u pozostałych narzędzi - realny efekt (dodatkowy
    // surowiec z kopanego bruku) liczy BrukSurowceManager (osobny listener, czyta ten sam
    // klucz pk_bruk_on), tu tylko ikonka podglądu/przełącznik w hubie kilofa.

    @Override
    protected void initializeToolSpecific(PersistentDataContainer pdc) {
        pdc.set(pkBrukOn, PersistentDataType.BYTE, (byte) 1);
    }

    @Override
    protected void populateExtraHubIcons(Inventory gui, PersistentDataContainer pdc) {
        boolean enabled = pdc.getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
        gui.setItem(slotBrukInfo, brukInfoIcon(pdc, enabled));
    }

    @Override
    protected boolean handleExtraHubClick(Player player, ItemStack tool, int slot) {
        if (slot != slotBrukInfo) return false;
        openBrukConfig(player, tool);
        return false;
    }

    private ItemStack brukInfoIcon(PersistentDataContainer pdc, boolean enabled) {
        int tier = tierOf(pdc);
        int szczescie = cardCountOf(pdc, "MAG_BRUK_SZCZESCIE");
        int instynkt = cardCountOf(pdc, "MAG_BRUK_INSTYNKT");
        double szansa = brukSurowceManager.calkowitaSzansa(tier, szczescie);
        Set<String> wylaczone = brukSurowceManager.wylaczoneSurowce(pdc);
        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tier);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Status: " + (enabled ? "WŁĄCZONY" : "WYŁĄCZONY"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Kopiąc bruk (cobblestone) masz szansę", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("na dodatkowy surowiec w dropie:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text(formatPercent(szansa) + "% łącznie" + (szczescie > 0 ? " (w tym +" + formatPercent(szczescie) + "% z kart)" : ""),
                NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Losowo jeden z (tier " + tierName(tier) + "):", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        for (SurowiecDrop drop : pula) {
            boolean dropEnabled = !wylaczone.contains(drop.id());
            double udzial = brukSurowceManager.udzialSurowca(tier, drop, instynkt, wylaczone);
            String linia = "• " + drop.nazwa() + " — " + (dropEnabled ? formatPercent(szansa * udzial) + "%" : "wyłączony");
            lore.add(Component.text(linia, dropEnabled ? (drop.cenny() ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.YELLOW) : NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        if (instynkt > 0) {
            lore.add(Component.text("Głębinowy Instynkt: cenne surowce x" + formatPercent(1 + 0.5 * instynkt), NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Kliknij, aby skonfigurować (włącz/wyłącz cały bonus lub pojedyncze surowce)", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));

        return GuiUtils.namedItem(Material.COBBLESTONE,
                Component.text("Bonus z Bruku", NamedTextColor.GOLD, TextDecoration.BOLD),
                lore.toArray(Component[]::new));
    }

    // ============================================== Konfiguracja bonusu z bruku ====
    // Osobny ekran (nie zwykłe Info) - trzeba móc KLIKAĆ pojedyncze surowce, nie tylko je
    // oglądać, stąd własny Holder/listener zamiast wpinania się w launcher Informacji.

    private static final int BRUK_CONFIG_SIZE = 36;
    private static final int BRUK_CONFIG_MASTER_SLOT = 4;
    private static final int BRUK_CONFIG_CONTENT_START = 9;
    private static final int BRUK_CONFIG_BACK_SLOT = 31;

    private void openBrukConfig(Player player, ItemStack tool) {
        PersistentDataContainer pdc = tool.getItemMeta().getPersistentDataContainer();
        int tier = tierOf(pdc);
        boolean enabled = pdc.getOrDefault(pkBrukOn, PersistentDataType.BYTE, (byte) 1) != 0;
        int szczescie = cardCountOf(pdc, "MAG_BRUK_SZCZESCIE");
        int instynkt = cardCountOf(pdc, "MAG_BRUK_INSTYNKT");
        double szansa = brukSurowceManager.calkowitaSzansa(tier, szczescie);
        Set<String> wylaczone = brukSurowceManager.wylaczoneSurowce(pdc);
        List<SurowiecDrop> pula = BrukSurowce.pulaDlaTieru(tier);

        BrukConfigHolder holder = new BrukConfigHolder(this);
        Inventory gui = Bukkit.createInventory(holder, BRUK_CONFIG_SIZE, Component.text("Bonus z Bruku — Surowce", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        holder.inventory = gui;
        GuiUtils.fillBackground(gui, Material.BLACK_STAINED_GLASS_PANE);

        gui.setItem(BRUK_CONFIG_MASTER_SLOT, brukMasterToggleIcon(enabled));
        for (int i = 0; i < pula.size() && BRUK_CONFIG_CONTENT_START + i < BRUK_CONFIG_BACK_SLOT; i++) {
            SurowiecDrop drop = pula.get(i);
            boolean dropEnabled = !wylaczone.contains(drop.id());
            double udzial = brukSurowceManager.udzialSurowca(tier, drop, instynkt, wylaczone);
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
        Set<String> wylaczone = csvToSet(pdc.getOrDefault(pkBrukDisabled, PersistentDataType.STRING, ""));
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