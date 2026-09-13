package elo.mainplugins.crates;

import elo.mainplugins.crates.model.CrateDef;
import elo.mainplugins.crates.model.Prize;

import java.util.List;
import java.util.Locale;
import java.util.function.IntUnaryOperator;

/** Szanse i losowanie wygranej (czysta logika) + mapowanie starych skrzynek po numerze tieru. */
public final class CrateOdds {

    private CrateOdds() {}

    public static double chancePercent(CrateDef crate, Prize prize) {
        int total = crate.totalWeight();
        return total <= 0 ? 0 : prize.weight() * 100.0 / total;
    }

    public static String formatChance(double percent) {
        return String.format(Locale.US, "%.1f", percent);
    }

    /** randomBelow(n) ma zwrócić liczbę 0..n-1 (w grze: ThreadLocalRandom). */
    public static Prize pick(CrateDef crate, IntUnaryOperator randomBelow) {
        int roll = randomBelow.applyAsInt(crate.totalWeight());
        int acc = 0;
        for (Prize p : crate.prizes()) {
            acc += p.weight();
            if (roll < acc) return p;
        }
        return crate.prizes().getLast();
    }

    /** Stara skrzynka z tagiem tieru 1-3 (albo stare API) -> skrzynka o tej pozycji w pliku; spoza zakresu -> pierwsza. */
    public static String legacyCrateId(int tier, List<String> idsInOrder) {
        if (idsInOrder.isEmpty()) return null;
        return tier >= 1 && tier <= idsInOrder.size() ? idsInOrder.get(tier - 1) : idsInOrder.getFirst();
    }
}
