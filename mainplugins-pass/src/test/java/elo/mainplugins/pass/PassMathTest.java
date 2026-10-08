package elo.mainplugins.pass;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class PassMathTest {

    @Test
    void levelGrowsEveryXpPerLevelAndStopsAtMax() {
        assertEquals(0, PassMath.level(0, 100, 10));
        assertEquals(0, PassMath.level(99, 100, 10));
        assertEquals(1, PassMath.level(100, 100, 10));
        assertEquals(3, PassMath.level(350, 100, 10));
        assertEquals(10, PassMath.level(99_999, 100, 10));
        assertEquals(0, PassMath.level(500, 0, 10));
    }

    @Test
    void progressInsideLevelAndFullAtMax() {
        assertEquals(50, PassMath.progress(350, 100, 10));
        assertEquals(0, PassMath.progress(400, 100, 10));
        assertEquals(100, PassMath.progress(5000, 100, 10));
    }

    @Test
    void barFillsProportionally() {
        assertEquals("■■■■■□□□□□", PassMath.bar(50, 100, 10));
        assertEquals("□□□□□□□□□□", PassMath.bar(0, 100, 10));
        assertEquals("■■■■■■■■■■", PassMath.bar(150, 100, 10));
    }

    @Test
    void firstEverDailyIsDayOne() {
        PassMath.Daily d = PassMath.daily(null, 0, LocalDate.of(2026, 10, 6), true, 7);
        assertTrue(d.canClaim());
        assertEquals(1, d.streakAfter());
        assertEquals(0, d.dayIndex());
    }

    @Test
    void consecutiveDaysContinueTheStreakAndCycle() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        PassMath.Daily d = PassMath.daily(today.minusDays(1), 3, today, true, 7);
        assertTrue(d.canClaim());
        assertEquals(4, d.streakAfter());
        assertEquals(3, d.dayIndex());
        // Po 7. dniu cykl wraca do pierwszej nagrody.
        PassMath.Daily cycle = PassMath.daily(today.minusDays(1), 7, today, true, 7);
        assertEquals(8, cycle.streakAfter());
        assertEquals(0, cycle.dayIndex());
    }

    @Test
    void missedDayResetsOnlyWhenEnabled() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        assertEquals(1, PassMath.daily(today.minusDays(3), 5, today, true, 7).streakAfter());
        assertEquals(6, PassMath.daily(today.minusDays(3), 5, today, false, 7).streakAfter());
    }

    @Test
    void cannotClaimTwiceADay() {
        LocalDate today = LocalDate.of(2026, 10, 6);
        PassMath.Daily d = PassMath.daily(today, 4, today, true, 7);
        assertFalse(d.canClaim());
        assertEquals(4, d.streakAfter());
        assertEquals(3, d.dayIndex());
    }
}
