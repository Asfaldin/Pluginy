package elo.mainplugins.skyblock.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IslandCostTest {

    @Test
    void pierwszePowiekszenieKosztujeKwotePierwszego() {
        assertEquals(1000, IslandTuning.kosztPowiekszenia(1000, 35, 15, 10, 15));
    }

    @Test
    void kazdeKolejneJestDrozszeOProcent() {
        assertEquals(1350, IslandTuning.kosztPowiekszenia(1000, 35, 15, 10, 25));
        assertEquals(1820, IslandTuning.kosztPowiekszenia(1000, 35, 15, 10, 35));
        assertEquals(2460, IslandTuning.kosztPowiekszenia(1000, 35, 15, 10, 45));
    }

    @Test
    void zeroProcentToStalaCena() {
        assertEquals(500, IslandTuning.kosztPowiekszenia(500, 0, 15, 10, 95));
    }

    @Test
    void wyspaMniejszaNizStartPlaciJakZaPierwsze() {
        assertEquals(1000, IslandTuning.kosztPowiekszenia(1000, 35, 50, 10, 20));
    }
}
