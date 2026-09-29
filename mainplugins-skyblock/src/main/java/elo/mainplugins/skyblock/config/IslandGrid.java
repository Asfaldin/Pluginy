package elo.mainplugins.skyblock.config;

/**
 * Gdzie stoi kolejna wyspa: kwadratowa spirala wokół środka świata wysp. Środek (0, 0) zostaje pusty,
 * pierwsze wyspy stoją w odległości jednego odstępu od niego, kolejne coraz dalej, pierścień po pierścieniu.
 * Dzięki temu wyspy trzymają się blisko środka zamiast rozciągać się w jeden długi rząd.
 */
public final class IslandGrid {

    private IslandGrid() {}

    /** Pole siatki (w odstępach, nie w blokach) dla wyspy o numerze {@code numer} (0, 1, 2...). */
    public static int[] pole(int numer) {
        // Spirala: (0,0) -> (1,0) -> (1,1) -> (0,1) -> (-1,1) -> (-1,0) -> (-1,-1) -> (0,-1) -> (1,-1) -> (2,-1) ...
        // Numer 0 dostaje drugie pole spirali, bo pierwsze to pusty środek.
        int x = 0, z = 0, dx = 1, dz = 0, dlugosc = 1, krok = 0, zakrety = 0;
        for (int i = 0; i < numer + 1; i++) {
            x += dx;
            z += dz;
            if (++krok == dlugosc) {
                krok = 0;
                int t = dx;
                dx = -dz;
                dz = t;
                if (++zakrety % 2 == 0) dlugosc++;
            }
        }
        return new int[]{x, z};
    }
}
