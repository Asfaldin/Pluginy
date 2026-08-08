package elo.mainplugins.tools.axe;

import elo.mainplugins.tools.skilltree.RarePerk;
import org.bukkit.Material;

import java.util.List;

/**
 * Pula rzadkich perków losowanych co RARE_ROLL_INTERVAL poziomów siekiery (3 z puli do
 * wyboru 1, bez powtórek na cały czas życia przedmiotu) - odpowiednik RarePerks kilofa.
 */
public final class AxeRarePerks {

    private AxeRarePerks() {}

    public static final List<RarePerk> WSZYSTKIE = List.of(
            new RarePerk("RARE_SIEK_FORT4", "Obfitość Lasu IV", Material.NETHER_STAR, "Rzadka: Fortuna ponad zwykły limit gałęzi Leśnictwa."),
            new RarePerk("RARE_SIEK_EFFICIENCY", "Wydajność I", Material.NETHER_STAR, "Rzadka: nadaje siekierze prawdziwą Wydajność I, niezależnie od bonusów % z drzewka."),
            new RarePerk("RARE_SIEK_MIDAS", "Dotyk Midasa", Material.GOLD_INGOT, "3% szansy na bonus $ przy DOWOLNYM rąbanym bloku."),
            new RarePerk("RARE_SIEK_MAGNET", "Magnes Drwala", Material.MAGMA_CREAM, "Pobliskie plony są delikatnie przyciągane do Ciebie."),
            new RarePerk("RARE_SIEK_SECOND_CHANCE", "Druga Szansa", Material.ECHO_SHARD, "5% szansy na całkowite zdublowanie plonu."),
            new RarePerk("RARE_SIEK_UNYIELDING_SPIRIT", "Nieugięty Duch", Material.EXPERIENCE_BOTTLE, "Szansa na spory wybuch orbów doświadczenia."),
            new RarePerk("RARE_SIEK_CHAOS_CORE", "Rdzeń Chaosu", Material.FIRE_CHARGE, "Rzadka szansa na natychmiastowe ścięcie do 3 sąsiednich bloków drewna."),
            new RarePerk("RARE_SIEK_FOREST_BLESSING", "Błogosławieństwo Lasu", Material.GLOW_BERRIES, "Drobna regeneracja i sytość podczas ciągłego rąbania.")
    );
}