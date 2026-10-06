package elo.mainplugins.spawners.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ceny takie same jak dawniej w Skyblocku: cena poziomu × mnożnik spawnera, do pełnych setek. */
class SpawnerSettingsTest {

    private static final List<Integer> ILOSC = List.of(2500, 5000, 8500, 17000);
    private static final List<Integer> SZYBKOSC = List.of(3500, 7000, 12000, 24000);

    @Test
    void mnoznikJedenToCenaZTabelki() {
        assertEquals(2500, SpawnerSettings.kosztUlepszenia(ILOSC, 1.0, 1));
        assertEquals(24000, SpawnerSettings.kosztUlepszenia(SZYBKOSC, 1.0, 4));
    }

    @Test
    void mnoznikSpawneraIZaokraglenieDoSetek() {
        assertEquals(3500, SpawnerSettings.kosztUlepszenia(ILOSC, 1.4, 1));   // krowy
        assertEquals(4900, SpawnerSettings.kosztUlepszenia(SZYBKOSC, 1.4, 1));
        assertEquals(2800, SpawnerSettings.kosztUlepszenia(ILOSC, 1.1, 1));   // świnie: 2750 -> 2800
        assertEquals(5500, SpawnerSettings.kosztUlepszenia(ILOSC, 2.2, 1));   // breeze
    }

    @Test
    void poziomDalejNizListaBierzeOstatniaCene() {
        assertEquals(17000, SpawnerSettings.kosztUlepszenia(ILOSC, 1.0, 9));
    }
}
