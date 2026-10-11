package elo.mainplugins.coreminer;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeinFinderTest {

    private static Set<VeinFinder.Pos> linia(int n) {
        Set<VeinFinder.Pos> s = new HashSet<>();
        for (int i = 0; i <= n; i++) s.add(new VeinFinder.Pos(i, 0, 0));
        return s;
    }

    @Test
    void cala_zyla_bez_bloku_startowego() {
        Set<VeinFinder.Pos> zyla = linia(5);
        List<VeinFinder.Pos> wynik = VeinFinder.znajdz(new VeinFinder.Pos(0, 0, 0), 64, 16, false, zyla::contains);
        assertEquals(5, wynik.size());
        assertFalse(wynik.contains(new VeinFinder.Pos(0, 0, 0)));
    }

    @Test
    void limit_i_kolejnosc_od_najblizszych() {
        List<VeinFinder.Pos> wynik = VeinFinder.znajdz(new VeinFinder.Pos(0, 0, 0), 2, 16, false, linia(10)::contains);
        assertEquals(List.of(new VeinFinder.Pos(1, 0, 0), new VeinFinder.Pos(2, 0, 0)), wynik);
    }

    @Test
    void przekatne_tylko_gdy_wlaczone() {
        Set<VeinFinder.Pos> ukos = Set.of(new VeinFinder.Pos(0, 0, 0), new VeinFinder.Pos(1, 1, 0), new VeinFinder.Pos(2, 2, 0));
        assertTrue(VeinFinder.znajdz(new VeinFinder.Pos(0, 0, 0), 64, 16, false, ukos::contains).isEmpty());
        assertEquals(2, VeinFinder.znajdz(new VeinFinder.Pos(0, 0, 0), 64, 16, true, ukos::contains).size());
    }

    @Test
    void nie_dalej_niz_max_odleglosc() {
        assertEquals(3, VeinFinder.znajdz(new VeinFinder.Pos(0, 0, 0), 64, 3, false, linia(10)::contains).size());
    }
}
