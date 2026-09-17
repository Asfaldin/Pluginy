package elo.mainplugins.core.lang;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Nazwa zapasowa, gdy słownik names/<język>.yml nie zna przedmiotu (np. nowy w Minecrafcie). */
class ItemNameManagerTest {

    @Test
    void materialNameBecomesReadableEnglish() {
        assertEquals("Cobblestone", ItemNameManager.zNazwyMaterialu(Material.COBBLESTONE));
        assertEquals("Iron Ingot", ItemNameManager.zNazwyMaterialu(Material.IRON_INGOT));
        assertEquals("Oak Log", ItemNameManager.zNazwyMaterialu(Material.OAK_LOG));
    }
}
