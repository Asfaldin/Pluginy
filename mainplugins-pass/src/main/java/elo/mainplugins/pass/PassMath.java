package elo.mainplugins.pass;

import java.time.LocalDate;

/** Czysta logika przepustki (bez Bukkita) - poziom z XP, postęp do następnego, seria dni. */
public final class PassMath {

    private PassMath() {}

    /** Osiągnięty poziom: co xpPerLevel jeden poziom, najwyżej maxLevel. */
    public static int level(long xp, int xpPerLevel, int maxLevel) {
        if (xpPerLevel <= 0 || maxLevel <= 0 || xp <= 0) return 0;
        return (int) Math.min(maxLevel, xp / xpPerLevel);
    }

    /** XP zdobyte w bieżącym poziomie (0..xpPerLevel); na maksymalnym poziomie = xpPerLevel. */
    public static int progress(long xp, int xpPerLevel, int maxLevel) {
        if (xpPerLevel <= 0) return 0;
        if (level(xp, xpPerLevel, maxLevel) >= maxLevel) return xpPerLevel;
        return (int) (Math.max(0, xp) % xpPerLevel);
    }

    /** Pasek postępu z kratek, np. "■■■■□□□□□□" - do opisu w oknie. */
    public static String bar(int value, int max, int cells) {
        int filled = max <= 0 ? cells : (int) Math.round((double) Math.max(0, Math.min(value, max)) / max * cells);
        return "■".repeat(filled) + "□".repeat(cells - filled);
    }

    /**
     * Seria dni logowania. lastClaim = dzień ostatniego odebrania (null = nigdy).
     * Zwraca, czy dziś można odebrać, jaka będzie seria po odebraniu i który dzień nagród (0..days-1).
     */
    public static Daily daily(LocalDate lastClaim, int streak, LocalDate today, boolean resetIfMissed, int days) {
        if (days <= 0) return new Daily(false, streak, 0);
        if (lastClaim != null && !lastClaim.isBefore(today)) {
            // Już odebrane dziś - pokazujemy dzień, który został odebrany.
            return new Daily(false, streak, Math.floorMod(Math.max(1, streak) - 1, days));
        }
        boolean continues = lastClaim != null && lastClaim.plusDays(1).equals(today);
        int next = continues || !resetIfMissed ? streak + 1 : 1;
        if (next < 1) next = 1;
        return new Daily(true, next, Math.floorMod(next - 1, days));
    }

    public record Daily(boolean canClaim, int streakAfter, int dayIndex) {}
}
