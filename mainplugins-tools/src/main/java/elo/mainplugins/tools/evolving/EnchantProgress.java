package elo.mainplugins.tools.evolving;

import org.bukkit.enchantments.Enchantment;

import java.util.NavigableMap;

/**
 * Prawdziwy wanilijski enchant rosnący z poziomem narzędzia (próg poziomu gracza -> poziom
 * enchantu) - dokładnie wzór Kilofa Niflheim (Wydajność II->III->IV, patrz
 * PickaxeSkillManager#niflEfficiencyLevel), tylko progi wyciągnięte do YAML zamiast
 * zaszyte jako if/else w Javie.
 */
public record EnchantProgress(Enchantment enchant, NavigableMap<Integer, Integer> progresja) {

    /** Poziom enchantu na dany poziom narzędzia - najwyższy próg <= poziom, albo 0 (brak enchantu) jeśli poziom poniżej pierwszego progu. */
    public int enchantNaPoziomie(int poziomNarzedzia) {
        var wpis = progresja.floorEntry(poziomNarzedzia);
        return wpis == null ? 0 : wpis.getValue();
    }
}
