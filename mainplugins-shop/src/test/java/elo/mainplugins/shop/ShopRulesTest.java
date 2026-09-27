package elo.mainplugins.shop;

import elo.mainplugins.shop.model.Category;
import elo.mainplugins.shop.model.Rounding;
import elo.mainplugins.shop.model.ShopConfig;
import elo.mainplugins.shop.model.ShopItem;
import elo.mainplugins.shop.model.ShopSettings;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static elo.mainplugins.shop.model.Rounding.CENTS;
import static elo.mainplugins.shop.model.Rounding.WHOLE;
import static org.junit.jupiter.api.Assertions.*;

class ShopRulesTest {

    private static ShopItem item(Double buy, int amount, Double sell, int sellAmount) {
        return new ShopItem("STONE", null, buy, sell, amount, sellAmount, null, List.of(), null, true);
    }

    @Test
    void buyPriceIsProRataRoundedUp() {
        ShopItem lot = item(10.0, 64, null, 1);
        assertEquals(10, ShopRules.buyPrice(lot, 64, CENTS));
        assertEquals(20, ShopRules.buyPrice(lot, 128, CENTS));
        assertEquals(0.16, ShopRules.buyPrice(lot, 1, CENTS));
        assertEquals(1.25, ShopRules.buyPrice(lot, 8, CENTS));
        assertEquals(1, ShopRules.buyPrice(lot, 1, WHOLE));
        assertEquals(2, ShopRules.buyPrice(lot, 8, WHOLE));
        assertEquals(10, ShopRules.buyPrice(lot, 64, WHOLE));
        assertEquals(10.24, ShopRules.buyPrice(item(0.16, 1, null, 1), 64, CENTS));
        assertEquals(0.01, ShopRules.buyPrice(item(1.0, 1000, null, 1), 1, CENTS));
        assertEquals(0, ShopRules.buyPrice(item(0.0, 1, null, 1), 5, WHOLE));
    }

    @Test
    void realPricesFromOurServerStayTheSame() {
        ShopItem cobble = item(640.0, 64, 50.0, 64);
        assertEquals(10, ShopRules.buyPrice(cobble, 1, WHOLE));
        assertEquals(80, ShopRules.buyPrice(cobble, 8, WHOLE));
        assertEquals(640, ShopRules.buyPrice(cobble, 64, WHOLE));
        ShopItem beetroot = item(1500.0, 64, null, 1);
        assertEquals(24, ShopRules.buyPrice(beetroot, 1, WHOLE));   // 23.4375 -> 24
        assertEquals(1500, ShopRules.buyPrice(beetroot, 64, WHOLE));
    }

    @Test
    void sellUsesFullLotsAndDynamicMultiplier() {
        ShopItem it = item(80.0, 64, 4.0, 16);
        ShopRules.SellResult r = ShopRules.sell(it, 40, 1.0, 0.9, WHOLE);
        assertEquals(2, r.lots());
        assertEquals(32, r.pieces());
        assertEquals(8, r.money());
        assertEquals(12, ShopRules.sell(it, 40, 1.5, 0.9, WHOLE).money()); // 6 za paczkę, sufit 18 nie działa
        assertEquals(0, ShopRules.sell(it, 15, 1.0, 0.9, WHOLE).lots());
    }

    @Test
    void sellPerLotCopiesTodaysRoundingAndCap() {
        ShopItem capped = item(12.0, 1, 10.0, 1);
        assertEquals(10, ShopRules.sellPerLot(capped, 1.5, 0.9, WHOLE));   // 15 -> sufit floor(10.8)=10
        assertEquals(10.8, ShopRules.sellPerLot(capped, 1.5, 0.9, CENTS));
        ShopItem cheap = item(null, 1, 1.0, 1);
        assertEquals(1, ShopRules.sellPerLot(cheap, 0.5, 0.9, WHOLE));     // minimum 1
        assertEquals(0.5, ShopRules.sellPerLot(cheap, 0.5, 0.9, CENTS));
        assertEquals(7, ShopRules.sellPerLot(item(null, 1, 5.0, 1), 1.3, 0.9, WHOLE)); // 6.5 -> 7 (jak Math.round)
        assertEquals(6, ShopRules.sellPerLot(item(null, 1, 5.0, 1), 1.28, 0.9, WHOLE)); // 6.4 -> 6
    }

    @Test
    void sellOfferPrefersFirstCategoryAndMatchesExactKeys() {
        ShopItem stoneA = item(1.0, 1, 1.0, 1);
        ShopItem stoneB = item(1.0, 1, 2.0, 1);
        ShopItem custom = new ShopItem(null, "gem", 5.0, 3.0, 1, 1, null, List.of(), null, true);
        ShopItem rotating = new ShopItem("ELYTRA", null, 100.0, 50.0, 1, 1, null, List.of(), null, true);
        Map<String, Category> cats = new LinkedHashMap<>();
        cats.put("a", new Category("a", "A", "STONE", null, List.of(stoneA, custom), null, null));
        cats.put("b", new Category("b", "B", "STONE", null, List.of(stoneB), null, null));
        ShopConfig cfg = new ShopConfig(ShopSettings.defaults(), cats);
        assertSame(stoneA, ShopRules.sellOffer(cfg, Map.of(), "STONE"));
        assertSame(custom, ShopRules.sellOffer(cfg, Map.of(), "custom:gem"));
        assertNull(ShopRules.sellOffer(cfg, Map.of(), "GEM"));
        assertNull(ShopRules.sellOffer(cfg, Map.of(), "ELYTRA"));
        assertSame(rotating, ShopRules.sellOffer(cfg, Map.of("b", List.of(rotating)), "ELYTRA"));
    }

    @Test
    void maxBuyIsLimitedByMoneyAndSpace() {
        ShopItem cobble = item(640.0, 64, null, 1);
        assertEquals(15, ShopRules.maxBuyPieces(cobble, 155, 1000, WHOLE));
        assertEquals(20, ShopRules.maxBuyPieces(cobble, 100000, 20, WHOLE));
        assertEquals(0, ShopRules.maxBuyPieces(cobble, 5, 1000, WHOLE));
        assertEquals(64, ShopRules.maxBuyPieces(item(0.0, 1, null, 1), 0, 64, WHOLE));
        ShopItem beetroot = item(1500.0, 64, null, 1);
        int n = ShopRules.maxBuyPieces(beetroot, 100, 1000, WHOLE);
        assertTrue(ShopRules.buyPrice(beetroot, n, WHOLE) <= 100);
        assertTrue(ShopRules.buyPrice(beetroot, n + 1, WHOLE) > 100);
    }

    @Test
    void roundingEnumIsUsed() {
        assertNotEquals(ShopRules.buyPrice(item(10.0, 64, null, 1), 1, Rounding.WHOLE),
                ShopRules.buyPrice(item(10.0, 64, null, 1), 1, Rounding.CENTS));
    }

    // ---- eventy: procenty i czas ----

    @Test
    void percentToMultiplierAcceptsSignsAndPercentSign() {
        assertEquals(1.5, ShopRules.percentToMultiplier("+50"), 1e-9);
        assertEquals(1.5, ShopRules.percentToMultiplier("50"), 1e-9);
        assertEquals(1.5, ShopRules.percentToMultiplier("50%"), 1e-9);
        assertEquals(0.8, ShopRules.percentToMultiplier("-20"), 1e-9);
        assertNull(ShopRules.percentToMultiplier("abc"));
    }

    @Test
    void percentRoundTrips() {
        assertEquals(50, ShopRules.multiplierToPercent(1.5));
        assertEquals(-20, ShopRules.multiplierToPercent(0.8));
        assertEquals(0, ShopRules.multiplierToPercent(1.0));
    }

    @Test
    void parseDurationUnderstandsUnits() {
        assertEquals(30 * 60_000L, ShopRules.parseDuration("30m"));
        assertEquals(2 * 3_600_000L, ShopRules.parseDuration("2h"));
        assertEquals(3 * 86_400_000L, ShopRules.parseDuration("3d"));
        assertEquals(2 * 3_600_000L, ShopRules.parseDuration("2"), "sama liczba = godziny");
        assertNull(ShopRules.parseDuration("0h"));
        assertNull(ShopRules.parseDuration("-1h"));
        assertNull(ShopRules.parseDuration("2x"));
        assertNull(ShopRules.parseDuration(""));
    }

    @Test
    void formatDurationShowsAtMostTwoUnits() {
        assertEquals("30m", ShopRules.formatDuration(30 * 60_000L));
        assertEquals("1h 20m", ShopRules.formatDuration(80 * 60_000L));
        assertEquals("2h", ShopRules.formatDuration(2 * 3_600_000L));
        assertEquals("2d 3h", ShopRules.formatDuration(2 * 86_400_000L + 3 * 3_600_000L));
        assertEquals("45s", ShopRules.formatDuration(45_000L));
        assertEquals("0s", ShopRules.formatDuration(-5));
    }

    @Test
    void matchesCategoryByIdOrNameWithoutColorsAndPolishLetters() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("bloki", "&e&lBloki");
        names.put("mineraly", "&e&lRudy i Minerały");
        names.put("roslinki", "&e&lRośliny");
        assertEquals("bloki", ShopRules.matchCategory("BLOKI", names));
        assertEquals("mineraly", ShopRules.matchCategory("rudy i minerały", names));
        assertEquals("mineraly", ShopRules.matchCategory("Rudy i Mineraly", names));
        assertEquals("roslinki", ShopRules.matchCategory("rosliny", names));
        assertNull(ShopRules.matchCategory("drewno", names));
        assertNull(ShopRules.matchCategory("  ", names));
    }
}
