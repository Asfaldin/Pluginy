package elo.mainplugins.tools.sword;

import elo.mainplugins.tools.skilltree.RarePerk;
import org.bukkit.Material;

import java.util.List;

/**
 * Pula rzadkich perków losowanych co RARE_ROLL_INTERVAL poziomów miecza (3 z puli do
 * wyboru 1, bez powtórek na cały czas życia przedmiotu) - odpowiednik RarePerks kilofa.
 */
public final class SwordRarePerks {

    private SwordRarePerks() {}

    public static final List<RarePerk> WSZYSTKIE = List.of(
            new RarePerk("RARE_MIECZ_LOOT4", "Grabież IV", Material.NETHER_STAR, "Rzadka: Grabież ponad zwykły limit gałęzi Precyzji Walki."),
            new RarePerk("RARE_MIECZ_SHARPNESS", "Ostrość I", Material.NETHER_STAR, "Rzadka: nadaje mieczowi prawdziwą Ostrość I, niezależnie od bonusów z drzewka."),
            new RarePerk("RARE_MIECZ_MIDAS", "Dotyk Midasa", Material.GOLD_INGOT, "3% szansy na bonus $ przy DOWOLNYM trafieniu."),
            new RarePerk("RARE_MIECZ_MAGNET", "Magnes Wojownika", Material.MAGMA_CREAM, "Pobliskie łupy są delikatnie przyciągane do Ciebie."),
            new RarePerk("RARE_MIECZ_SECOND_CHANCE", "Druga Szansa", Material.ECHO_SHARD, "5% szansy na całkowite zdublowanie łupu."),
            new RarePerk("RARE_MIECZ_UNYIELDING_SPIRIT", "Nieugięty Duch", Material.EXPERIENCE_BOTTLE, "Szansa na spory wybuch orbów doświadczenia."),
            new RarePerk("RARE_MIECZ_CHAOS_CORE", "Rdzeń Chaosu", Material.FIRE_CHARGE, "Rzadka szansa na natychmiastowe obrażenia do 3 pobliskich wrogów."),
            new RarePerk("RARE_MIECZ_WAR_BLESSING", "Błogosławieństwo Wojny", Material.GLOW_BERRIES, "Drobna regeneracja i sytość podczas ciągłej walki.")
    );
}