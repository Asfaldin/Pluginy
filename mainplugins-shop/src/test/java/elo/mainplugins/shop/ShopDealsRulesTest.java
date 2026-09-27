package elo.mainplugins.shop;

import elo.mainplugins.shop.model.ShopItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static elo.mainplugins.shop.model.Rounding.CENTS;
import static elo.mainplugins.shop.model.Rounding.WHOLE;
import static org.junit.jupiter.api.Assertions.*;

/** Promocje, premie rang i historia sprzedaży - bez serwera. */
class ShopDealsRulesTest {

    private static ShopItem item(Double buy, int amount, Double sell, int sellAmount) {
        return new ShopItem("STONE", null, buy, sell, amount, sellAmount, null, List.of(), null, true);
    }

    @Test
    void saleAndRankDiscountAddUp() {
        assertEquals(0.72, ShopRules.buyFactor(20, 10), 1e-9);
        assertEquals(1.0, ShopRules.buyFactor(0, 0), 1e-9);
        ShopItem it = item(100.0, 1, null, 1);
        // 0.8 * 0.9 w double to 0.7200000000000001 - cena ma być 72, a nie 72.01
        assertEquals(72, ShopRules.buyPrice(it, 1, CENTS, ShopRules.buyFactor(20, 10)));
        assertEquals(80, ShopRules.buyPrice(it, 1, CENTS, ShopRules.buyFactor(20, 0)));
        assertEquals(ShopRules.buyPrice(it, 7, CENTS), ShopRules.buyPrice(it, 7, CENTS, 1.0));
        // zniżka nie schodzi poniżej minimum
        assertEquals(1, ShopRules.buyPrice(item(1.0, 1, null, 1), 1, WHOLE, 0.1));
    }

    @Test
    void maxBuyUsesTheDiscountedPrice() {
        ShopItem it = item(10.0, 1, null, 1);
        assertEquals(10, ShopRules.maxBuyPieces(it, 100, 64, CENTS));
        assertEquals(20, ShopRules.maxBuyPieces(it, 100, 64, CENTS, 0.5));
        assertEquals(12, ShopRules.maxBuyPieces(it, 100, 12, CENTS, 0.5));
    }

    @Test
    void narrowestSaleWinsAndExpiredOnesDoNotCount() {
        long now = 1_000_000L;
        Map<String, ShopRules.Sale> sales = Map.of(
                ShopRules.SALE_ALL, new ShopRules.Sale(10, 0),
                ShopRules.saleKeyCategory("bloki"), new ShopRules.Sale(20, 0),
                ShopRules.saleKeyItem("DIAMOND"), new ShopRules.Sale(50, now + 1000),
                ShopRules.saleKeyItem("STONE"), new ShopRules.Sale(40, now - 1));
        assertEquals(50, ShopRules.salePercent(sales, "DIAMOND", "rudy", now));
        assertEquals(20, ShopRules.salePercent(sales, "COBBLESTONE", "bloki", now));
        assertEquals(20, ShopRules.salePercent(sales, "STONE", "bloki", now), "wygasła promocja na przedmiot");
        assertEquals(10, ShopRules.salePercent(sales, "OAK_LOG", "drewno", now));
        assertEquals(10, ShopRules.salePercent(sales, "OAK_LOG", null, now));
        assertEquals(0, ShopRules.salePercent(Map.of(), "OAK_LOG", "drewno", now));
    }

    @Test
    void rankSellBonusRaisesSellButNotOverTheCap() {
        ShopItem it = item(10.0, 1, 5.0, 1);
        assertEquals(5.5, ShopRules.sellPerLot(it, ShopRules.sellFactor(10), 0.9, CENTS));
        // sufit 90% ceny kupna zostaje
        assertEquals(9, ShopRules.sellPerLot(it, 1.5 * ShopRules.sellFactor(50), 0.9, CENTS));
        assertEquals(5, ShopRules.bestBonus(List.of(2.0, 5.0, 1.0)));
        assertEquals(0, ShopRules.bestBonus(List.of()));
    }

    @Test
    void eventPlusSaleNeverLetsYouSellForMoreThanYouBuy() {
        // kupno 10, skup 5; event +500% i promocja -20% naraz
        ShopItem it = item(10.0, 1, 5.0, 1);
        double buyNow = ShopRules.buyPrice(it, 1, CENTS, ShopRules.buyFactor(20, 0));
        double sellNow = ShopRules.sellPerLot(it, 6.0, 0.9 * ShopRules.buyFactor(20, 0), CENTS);
        assertEquals(8, buyNow);
        assertTrue(sellNow < buyNow, "skup " + sellNow + " >= kupno " + buyNow);
        assertEquals(7.2, sellNow);
    }

    @Test
    void historyKeepsDaysPlayersAndTop() {
        ShopHistory h = new ShopHistory(null, w -> { });
        LocalDate today = LocalDate.of(2026, 9, 25);
        h.record(today, "DIAMOND", "u1", "Steve", 10, 500);
        h.record(today, "DIAMOND", "u2", "Alex", 64, 3200);
        h.record(today.minusDays(1), "DIAMOND", "u1", "Steve", 100, 6000);
        h.record(today.minusDays(3), "STONE", "u1", "Steve", 64, 50);

        List<ShopHistory.DayLine> d = h.itemHistory("DIAMOND", today, 7);
        assertEquals(2, d.size());
        assertEquals(new ShopHistory.DayLine(today, 74, 3700), d.get(0));

        List<ShopHistory.TopLine> todayTop = h.top(today, 1, 10);
        assertEquals("Alex", todayTop.get(0).name());
        assertEquals(3200, todayTop.get(0).money());
        List<ShopHistory.TopLine> week = h.top(today, 7, 10);
        assertEquals("Steve", week.get(0).name());
        assertEquals(6550, week.get(0).money());
        assertEquals(174, week.get(0).amount());

        h.prune(today, 2); // zostaje dziś i wczoraj
        assertTrue(h.itemHistory("STONE", today, 7).isEmpty());
        assertEquals(2, h.itemHistory("DIAMOND", today, 7).size());
    }
}
