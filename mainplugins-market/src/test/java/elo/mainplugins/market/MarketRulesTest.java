package elo.mainplugins.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketRulesTest {

    @Test
    void payoutTakesTaxAndRoundsDown() {
        assertEquals(100, MarketRules.payout(100, 0));
        assertEquals(95, MarketRules.payout(100, 5));
        assertEquals(9, MarketRules.payout(10, 5));      // 9.5 -> 9
        assertEquals(0, MarketRules.payout(100, 100));
        assertEquals(100, MarketRules.payout(100, -3));  // poza zakresem -> 0%
        assertEquals(0, MarketRules.payout(100, 250));   // poza zakresem -> 100%
    }

    @Test
    void expiryUsesDaysAndZeroMeansNever() {
        long day = 86_400_000L;
        assertFalse(MarketRules.expired(0, 7 * day - 1, 7));
        assertTrue(MarketRules.expired(0, 7 * day, 7));
        assertFalse(MarketRules.expired(0, 999 * day, 0));
    }

    @Test
    void limitTakesHighestPermission() {
        assertEquals(10, MarketRules.limitFor(10, List.of()));
        assertEquals(15, MarketRules.limitFor(10, List.of("mainplugins.market.limit.15", "mainplugins.market.limit.12")));
        assertEquals(10, MarketRules.limitFor(10, List.of("mainplugins.market.limit.5", "mainplugins.market.limit.x", "other.perm")));
    }

    @Test
    void rankLimitsFromTheFileWinWhenHigher() {
        java.util.Map<String, Integer> ranks = java.util.Map.of("vip", 15, "svip", 25);
        assertEquals(10, MarketRules.limitFor(10, List.of(), ranks));
        assertEquals(15, MarketRules.limitFor(10, List.of("mainplugins.market.rank.vip"), ranks));
        assertEquals(25, MarketRules.limitFor(10, List.of("mainplugins.market.rank.vip", "mainplugins.market.rank.svip"), ranks));
        assertEquals(30, MarketRules.limitFor(10, List.of("mainplugins.market.rank.vip", "mainplugins.market.limit.30"), ranks));
    }
}
