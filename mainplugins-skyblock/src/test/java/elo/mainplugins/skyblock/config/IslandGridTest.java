package elo.mainplugins.skyblock.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandGridTest {

    @Test
    void pierwszePierscienieWokolPustegoSrodka() {
        assertArrayEquals(new int[]{1, 0}, IslandGrid.pole(0));
        assertArrayEquals(new int[]{1, 1}, IslandGrid.pole(1));
        assertArrayEquals(new int[]{0, 1}, IslandGrid.pole(2));
        assertArrayEquals(new int[]{-1, 1}, IslandGrid.pole(3));
        assertArrayEquals(new int[]{-1, 0}, IslandGrid.pole(4));
        assertArrayEquals(new int[]{-1, -1}, IslandGrid.pole(5));
        assertArrayEquals(new int[]{0, -1}, IslandGrid.pole(6));
        assertArrayEquals(new int[]{1, -1}, IslandGrid.pole(7));
        assertArrayEquals(new int[]{2, -1}, IslandGrid.pole(8));
    }

    @Test
    void kazdaWyspaNaInnymPoluISrodekPusty() {
        Set<String> zajete = new HashSet<>();
        for (int n = 0; n < 2000; n++) {
            int[] p = IslandGrid.pole(n);
            assertTrue(p[0] != 0 || p[1] != 0, "środek ma zostać pusty");
            assertTrue(zajete.add(p[0] + "," + p[1]), "pole zajęte dwa razy: " + n);
        }
    }

    @Test
    void osiemPierwszychWysp_toPierwszyPierscien() {
        for (int n = 0; n < 8; n++) {
            int[] p = IslandGrid.pole(n);
            assertTrue(Math.max(Math.abs(p[0]), Math.abs(p[1])) == 1);
        }
    }
}
