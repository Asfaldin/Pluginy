package elo.mainplugins.core.util;

import java.util.Locale;

/**
 * Kompaktowy zapis kwot (1,5tys / 2,3mln / 1,0mld) - bez tego duże kwoty jako
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
        // Bez kropki (jak przy mln/mld) - "12.1tys.$" wyglądało źle z dwoma
        // znakami interpunkcyjnymi zlepionymi obok siebie.
        if (abs >= 1_000) return jednoMiejsce(kwota / 1_000) + "tys";
        return String.format(Locale.US, "%,.0f", kwota);
    }

    /** Pełna kwota do czatu: bez groszy, gdy ich nie ma ("100"), inaczej dwa miejsca ("100.50"). */
    public static String pelna(double kwota) {
        long grosze = Math.round(kwota * 100);
        return grosze % 100 == 0
                ? String.format(Locale.US, "%,d", grosze / 100)
                : String.format(Locale.US, "%,.2f", grosze / 100.0);
    }

    private static String jednoMiejsce(double wartosc) {
        return String.format(Locale.US, "%.1f", wartosc);
    }
}
