package elo.mainplugins.spawners.config;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerTypeDefTest {

    private static SpawnerTypeDef typ(int interwal, int naPoziom, int ilosc, int iloscNaPoziom, SpawnerTypeDef.Pora pora) {
        return new SpawnerTypeDef("GRYF", null, "gryf", "Gryfów", "Gryf", Material.SPAWNER, 1.0,
                interwal, naPoziom, ilosc, iloscNaPoziom, 50, false, 4, 3, 16, true, 2.0, 10, pora, true, null, 2, true, true);
    }

    @Test
    void wlasneTempoTypu() {
        SpawnerTypeDef t = typ(60, -10, 1, 1, SpawnerTypeDef.Pora.ZAWSZE);
        assertEquals(50, t.interwalSekund(1));
        assertEquals(2, t.iloscNaCykl(1));
        assertEquals(1, typ(5, -10, 1, 1, SpawnerTypeDef.Pora.ZAWSZE).interwalSekund(3)); // nigdy poniżej 1 s
        assertEquals(0, typ(10, 0, 0, -1, SpawnerTypeDef.Pora.ZAWSZE).iloscNaCykl(2));    // nigdy poniżej 0
        assertTrue(t.custom());
    }

    @Test
    void poraDnia() {
        SpawnerTypeDef noc = typ(10, 0, 1, 0, SpawnerTypeDef.Pora.NOC);
        SpawnerTypeDef dzien = typ(10, 0, 1, 0, SpawnerTypeDef.Pora.DZIEN);
        assertFalse(noc.dzialaO(6000));   // południe
        assertTrue(noc.dzialaO(18000));   // północ
        assertTrue(dzien.dzialaO(1000));
        assertFalse(dzien.dzialaO(13000));
        assertTrue(dzien.dzialaO(24000 + 500)); // kolejny dzień
    }

    @Test
    void prostyBierzeUstawieniaGlobalne() {
        SpawnerSettings u = new SpawnerSettings(5, 50, 36, -4, 4, 1, 10, 16, Material.STICK, true,
                List.of(1), List.of(1), Map.of(), SpawnerSettings.KtoZbiera.WYSPA);
        SpawnerTypeDef t = SpawnerTypeDef.prosty("COW", org.bukkit.entity.EntityType.COW, "Krów", "Krowa", Material.LEATHER, 1.4, u);
        assertEquals(32, t.interwalSekund(1));
        assertEquals(5, t.iloscNaCykl(1));
        assertTrue(t.stackowanie());
        assertFalse(t.custom());
    }
}
