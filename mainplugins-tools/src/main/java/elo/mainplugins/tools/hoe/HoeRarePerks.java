package elo.mainplugins.tools.hoe;

import elo.mainplugins.tools.skilltree.RarePerk;
import org.bukkit.Material;

import java.util.List;

/**
 * Pula rzadkich perków losowanych co RARE_ROLL_INTERVAL poziomów motyki (3 z puli do
 * wyboru 1, bez powtórek na cały czas życia przedmiotu) - odpowiednik RarePerks kilofa.
 */
public final class HoeRarePerks {

    private HoeRarePerks() {}

    public static final List<RarePerk> WSZYSTKIE = List.of(
            new RarePerk("RARE_MOT_FORT4", "Fortuna Plonu IV", Material.NETHER_STAR, "Rzadka: Fortuna ponad zwykły limit gałęzi Agronomii."),
            new RarePerk("RARE_MOT_EFFICIENCY", "Wydajność I", Material.NETHER_STAR, "Rzadka: nadaje motyce prawdziwą Wydajność I, niezależnie od bonusów % z drzewka."),
            new RarePerk("RARE_MOT_MIDAS", "Dotyk Midasa", Material.GOLD_INGOT, "3% szansy na bonus $ przy DOWOLNEJ zbieranej uprawie."),
            new RarePerk("RARE_MOT_MAGNET", "Magnes Rolnika", Material.MAGMA_CREAM, "Pobliskie plony są delikatnie przyciągane do Ciebie."),
            new RarePerk("RARE_MOT_SECOND_CHANCE", "Druga Szansa", Material.ECHO_SHARD, "5% szansy na całkowite zdublowanie plonu."),
            new RarePerk("RARE_MOT_UNYIELDING_SPIRIT", "Nieugięty Duch", Material.EXPERIENCE_BOTTLE, "Szansa na spory wybuch orbów doświadczenia."),
            new RarePerk("RARE_MOT_CHAOS_CORE", "Rdzeń Chaosu", Material.FIRE_CHARGE, "Rzadka szansa na natychmiastowy zbiór do 3 sąsiednich upraw."),
            new RarePerk("RARE_MOT_FIELD_BLESSING", "Błogosławieństwo Pól", Material.GLOW_BERRIES, "Drobna regeneracja i sytość podczas ciągłej pracy motyką.")
    );
}