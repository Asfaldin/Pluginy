package elo.mainplugins.core.util;

import java.util.Locale;

/**
 * Kompaktowy zapis kwot (1,5tys. / 2,3mln / 1,0mld) - bez tego duże kwoty jako
 * pełna liczba z separatorami tysięcy (np. "1,234,567") rozciągały scoreboard/
 * tablistę na pół ekranu, bo obie tablice auto-dopasowują szerokość do
 * najdłuższej linii.
 */
public final class MoneyFormat {

    private MoneyFormat() {}

    public static String kompaktowo(double kwota) {
        double abs = Math.abs(kwota);
        if (abs >= 1_000_000_000) return jednoMiejsce(kwota / 1_000_000_000) + "mld";
        if (abs >= 1_000_000) return jednoMiejsce(kwota / 1_000_000) + "mln";
        if (abs >= 1_000) return jednoMiejsce(kwota / 1_000) + "tys.";
        return String.format(Locale.US, "%,.0f", kwota);
    }

    private static String jednoMiejsce(double wartosc) {
        return String.format(Locale.US, "%.1f", wartosc);
    }
}
