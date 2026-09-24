package elo.mainplugins.shop;

import elo.mainplugins.shop.model.Category;
import elo.mainplugins.shop.model.DynamicSettings;
import elo.mainplugins.shop.model.Rounding;
import elo.mainplugins.shop.model.ShopConfig;
import elo.mainplugins.shop.model.ShopItem;
import elo.mainplugins.shop.model.ShopSettings;
import elo.mainplugins.shop.model.SlotRole;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class ShopConfigParserTest {

    private static final Predicate<String> MATERIAL = m -> m.matches("[A-Z0-9_]+");

    private static YamlConfiguration yml(String text) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    private static Category cat(String id, String text, List<String> w) throws Exception {
        return ShopConfigParser.parseCategory(id, yml(text), MATERIAL, w::add);
    }

    @Test
    void readsAFullCategory() throws Exception {
        List<String> w = new ArrayList<>();
        Category c = cat("ores", """
                name: "&bOres"
                icon: DIAMOND
                items:
                  - item: DIAMOND
                    buy: 150
                    sell: 60
                  - item: COBBLESTONE
                    buy: 640
                    amount: 64
                    sell: 50
                    sell-amount: 64
                    name: "Bruk"
                    lore: ["&7a", "&7b"]
                  - custom: spawner_zombie
                    buy: 20000
                  - item: GOAT_HORN
                    buy: 20000
                    instrument: ponder_goat_horn
                  - item: WHEAT
                    buy: 0.16
                """, w);
        assertTrue(w.isEmpty(), w.toString());
        assertEquals("&bOres", c.name());
        assertEquals("DIAMOND", c.iconMaterial());
        assertNull(c.rotation());
        assertEquals(5, c.items().size());
        assertEquals(new ShopItem("DIAMOND", null, 150.0, 60.0, 1, 1, null, List.of(), null, true), c.items().get(0));
        ShopItem cobble = c.items().get(1);
        assertEquals(64, cobble.amount());
        assertEquals(64, cobble.sellAmount());
        assertEquals("Bruk", cobble.name());
        assertEquals(List.of("&7a", "&7b"), cobble.lore());
        ShopItem spawner = c.items().get(2);
        assertEquals("spawner_zombie", spawner.customId());
        assertNull(spawner.material());
        assertFalse(spawner.sellable());
        assertEquals("custom:spawner_zombie", spawner.key());
        assertEquals("ponder_goat_horn", c.items().get(3).instrument());
        assertEquals(0.16, c.items().get(4).buy());
        assertEquals("DIAMOND", c.items().get(0).key());
    }

    @Test
    void skipsBadItemsWithAWarningNamingThePlace() throws Exception {
        List<String> w = new ArrayList<>();
        Category c = cat("ores", """
                name: Ores
                icon: DIAMOND
                items:
                  - item: DIAMOND
                    buy: 1
                  - item: COAL
                  - item: IRON_INGOT
                    buy: -5
                  - item: "not a material"
                    buy: 5
                  - item: GOLD_INGOT
                    buy: 5
                    amount: 0
                """, w);
        assertEquals(List.of("DIAMOND", "GOLD_INGOT"), c.items().stream().map(ShopItem::material).toList());
        assertEquals(1, c.items().get(1).amount());
        assertTrue(w.stream().anyMatch(x -> x.contains("categories/ores.yml items[2]")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("categories/ores.yml items[3]")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("categories/ores.yml items[4]")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("categories/ores.yml items[5]")), w.toString());
    }

    @Test
    void readsRotation() throws Exception {
        List<String> w = new ArrayList<>();
        Category c = cat("collection", """
                name: Collection
                icon: CHEST_MINECART
                rotation:
                  enabled: true
                  show: 5
                  every-days: 14
                  pool:
                    - {item: MUSIC_DISC_13, buy: 10000}
                    - {item: ELYTRA, buy: 75000}
                """, w);
        assertTrue(w.isEmpty(), w.toString());
        assertTrue(c.items().isEmpty());
        assertTrue(c.rotation().enabled());
        assertEquals(5, c.rotation().show());
        assertEquals(14, c.rotation().everyDays());
        assertEquals(2, c.rotation().pool().size());
    }

    @Test
    void badRotationNumbersAreFixed() throws Exception {
        List<String> w = new ArrayList<>();
        Category c = cat("x", "name: X\nicon: CHEST\nrotation: {enabled: true, show: 0, every-days: 0}", w);
        assertEquals(1, c.rotation().show());
        assertEquals(14, c.rotation().everyDays());
        assertEquals(2, w.size());
    }

    @Test
    void unknownIconFallsBackToChest() throws Exception {
        List<String> w = new ArrayList<>();
        Category c = cat("x", "name: X\nicon: nope nope", w);
        assertEquals("CHEST", c.iconMaterial());
        assertEquals(1, w.size());
        Category custom = cat("y", "name: Y\nicon: {custom: my_gem}", new ArrayList<>());
        assertEquals("my_gem", custom.iconCustom());
    }

    @Test
    void emptySettingsAreDefaultsWithoutWarnings() throws Exception {
        List<String> w = new ArrayList<>();
        assertEquals(ShopSettings.defaults(), ShopConfigParser.parseSettings(yml(""), MATERIAL, w::add));
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test
    void readsSettings() throws Exception {
        List<String> w = new ArrayList<>();
        ShopSettings s = ShopConfigParser.parseSettings(yml("""
                categories: [blocks, ores]
                price-rounding: whole
                dynamic-prices:
                  enabled: false
                  cycle-minutes: 30
                  min-multiplier: 0.6
                  max-multiplier: 2
                  reset-days: 7
                  max-sell-share: 0.8
                stats:
                  enabled: true
                menus:
                  buy-picker:
                    size: 27
                    layout:
                      - {slot: 13, role: AMOUNT_SLOT, amount: 64}
                      - {slot: 22, role: NAV_BACK}
                  buttons:
                    exit: BEDROCK
                """), MATERIAL, w::add);
        assertTrue(w.isEmpty(), w.toString());
        assertEquals(List.of("blocks", "ores"), s.categoryOrder());
        assertEquals(Rounding.WHOLE, s.rounding());
        assertFalse(s.dynamic().enabled());
        assertEquals(30, s.dynamic().cycleMinutes());
        assertEquals(0.6, s.dynamic().minMultiplier());
        assertEquals(2.0, s.dynamic().maxMultiplier());
        assertEquals(7, s.dynamic().resetDays());
        assertEquals(0.8, s.dynamic().maxSellShare());
        assertTrue(s.statsEnabled());
        assertEquals(2, s.menu("buy-picker").layout().size());
        assertEquals(64, s.menu("buy-picker").layout().get(0).amount());
        assertEquals(ShopSettings.defaults().menu("main-menu"), s.menu("main-menu"));
        assertEquals("BEDROCK", s.button("exit"));
        assertEquals("OAK_SIGN", s.button("search"));
    }

    @Test
    void categoryPageSortAndCentering() throws Exception {
        List<String> w = new ArrayList<>();
        ShopSettings s = ShopConfigParser.parseSettings(yml("""
                category-page-sort: buy
                center-small-categories: false
                """), MATERIAL, w::add);
        assertTrue(w.isEmpty(), w.toString());
        assertEquals(ShopSettings.CategorySort.BUY, s.categorySort());
        assertFalse(s.centerSmallCategories());
        ShopSettings d = ShopConfigParser.parseSettings(yml("stats: {enabled: false}"), MATERIAL, w::add);
        assertEquals(ShopSettings.CategorySort.ORDER, d.categorySort());
        assertFalse(d.centerSmallCategories());
        List<String> w2 = new ArrayList<>();
        ShopSettings bad = ShopConfigParser.parseSettings(yml("category-page-sort: chaos"), MATERIAL, w2::add);
        assertEquals(ShopSettings.CategorySort.ORDER, bad.categorySort());
        assertFalse(w2.isEmpty());
    }

    @Test
    void resetDaysZeroTurnsResetOffAndNoGrowthIsAllowed() throws Exception {
        List<String> w = new ArrayList<>();
        ShopSettings s = ShopConfigParser.parseSettings(yml("""
                dynamic-prices:
                  max-multiplier: 1
                  reset-days: 0
                """), MATERIAL, w::add);
        assertTrue(w.isEmpty(), w.toString());
        assertEquals(0, s.dynamic().resetDays());
        assertEquals(1.0, s.dynamic().maxMultiplier());
        List<String> w2 = new ArrayList<>();
        ShopSettings bad = ShopConfigParser.parseSettings(yml("""
                dynamic-prices:
                  reset-days: -3
                """), MATERIAL, w2::add);
        assertEquals(ShopSettings.defaults().dynamic().resetDays(), bad.dynamic().resetDays());
        assertFalse(w2.isEmpty());
    }

    @Test
    void badSettingsAreFixedWithWarnings() throws Exception {
        List<String> w = new ArrayList<>();
        ShopSettings s = ShopConfigParser.parseSettings(yml("""
                price-rounding: dollars
                dynamic-prices:
                  min-multiplier: 1.2
                  max-multiplier: 0.9
                menus:
                  category-page:
                    size: 50
                    layout:
                      - {slot: 60, role: ITEM_SLOT}
                      - {slot: 10, role: ITEM_SLOT}
                      - {slot: 10, role: SORT}
                      - {slot: 11, role: WHAT}
                  buy-picker:
                    layout:
                      - {slot: 11, role: AMOUNT_SLOT}
                """), MATERIAL, w::add);
        assertEquals(Rounding.CENTS, s.rounding());
        assertEquals(0.5, s.dynamic().minMultiplier());
        assertEquals(1.5, s.dynamic().maxMultiplier());
        assertEquals(54, s.menu("category-page").size());
        assertEquals(List.of(10), s.menu("category-page").layout().stream().map(e -> e.slot()).toList());
        assertTrue(s.menu("buy-picker").withRole(SlotRole.AMOUNT_SLOT).isEmpty());
        assertTrue(w.size() >= 7, w.toString());
    }

    @Test
    void combineWarnsAboutOrder() throws Exception {
        List<String> w = new ArrayList<>();
        ShopSettings s = ShopConfigParser.parseSettings(yml("categories: [a, ghost]"), MATERIAL, w::add);
        Map<String, Category> cats = new LinkedHashMap<>();
        cats.put("b", cat("b", "name: B\nicon: STONE", w));
        cats.put("a", cat("a", "name: A\nicon: STONE", w));
        ShopConfig cfg = ShopConfigParser.combine(s, cats, w::add);
        assertEquals(List.of("a", "b"), List.copyOf(cfg.categories().keySet()));
        assertEquals(List.of("a"), cfg.settings().categoryOrder());
        assertTrue(w.stream().anyMatch(x -> x.contains("ghost")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("'b'")), w.toString());
    }

    @Test
    void itemsHaveMovingPricesUnlessTurnedOff() throws Exception {
        List<String> w = new ArrayList<>();
        Category c = cat("ores", """
                name: "Ores"
                icon: DIAMOND
                items:
                  - {item: DIAMOND, buy: 100, sell: 50}
                  - {item: COBBLESTONE, buy: 10, sell: 5, dynamic: false}
                """, w);
        assertTrue(c.items().get(0).dynamic(), "brak wpisu = ceny dynamiczne dzialaja jak dotad");
        assertFalse(c.items().get(1).dynamic(), "dynamic: false = cena stala");
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test
    void readsDynamicTuningAndResetAnnouncement() throws Exception {
        List<String> w = new ArrayList<>();
        ShopSettings s = ShopConfigParser.parseSettings(yml("""
                dynamic-prices:
                  announce-reset: false
                  tuning:
                    max-drop-per-cycle: 0.1
                    drop-at-top: 3.0
                    cycles-to-rise: 4
                """), MATERIAL, w::add);
        assertFalse(s.dynamic().announceReset());
        assertEquals(0.1, s.dynamic().tuning().maxDropPerCycle());
        assertEquals(3.0, s.dynamic().tuning().dropAtTop());
        assertEquals(4, s.dynamic().tuning().cyclesToRise());
        // Niepodane zostaja domyslne.
        assertEquals(DynamicSettings.Tuning.defaults().risePerCycle(), s.dynamic().tuning().risePerCycle());
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test
    void badTuningFallsBackWithAWarning() throws Exception {
        List<String> w = new ArrayList<>();
        ShopSettings s = ShopConfigParser.parseSettings(yml("""
                dynamic-prices:
                  tuning:
                    quiet-threshold: 5
                    cycles-frozen: -2
                    drop-at-top: 0.5
                """), MATERIAL, w::add);
        DynamicSettings.Tuning d = DynamicSettings.Tuning.defaults();
        assertEquals(d.quietThreshold(), s.dynamic().tuning().quietThreshold());
        assertEquals(d.cyclesFrozen(), s.dynamic().tuning().cyclesFrozen());
        assertEquals(d.dropAtTop(), s.dynamic().tuning().dropAtTop());
        assertEquals(3, w.size(), w.toString());
    }
}
