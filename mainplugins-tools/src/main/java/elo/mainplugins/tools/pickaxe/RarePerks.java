package elo.mainplugins.tools.pickaxe;

import org.bukkit.Material;

import java.util.List;

/**
 * Pula rzadkich perków losowanych co 10 poziomów kilofa (3 z puli do wyboru, bez
 * powtórek na cały czas życia przedmiotu). Jak w PickaxeSkillTrees - dane osobno,
 * efekt działania po id() przełącza PickaxeSkillManager.
 */
public final class RarePerks {

    private RarePerks() {}

    public record RarePerk(String id, String displayName, Material icon, String opis) {}

    public static final List<RarePerk> WSZYSTKIE = List.of(
            new RarePerk("RARE_FORT4", "Fortuna IV", Material.NETHER_STAR, "Rzadka: Fortuna ponad zwykły limit drzewa Precyzji."),
            new RarePerk("RARE_EFFICIENCY", "Wydajność I", Material.NETHER_STAR, "Rzadka: nadaje kilofowi prawdziwą Wydajność I, niezależnie od bonusów % z drzewka."),
            new RarePerk("RARE_MIDAS", "Dotyk Midasa", Material.GOLD_INGOT, "3% szansy na bonus $ przy DOWOLNYM kopanym bloku."),
            new RarePerk("RARE_MAGNET", "Magnes Górnika", Material.MAGMA_CREAM, "Pobliskie plony są delikatnie przyciągane do Ciebie."),
            new RarePerk("RARE_SECOND_CHANCE", "Druga Szansa", Material.ECHO_SHARD, "5% szansy na całkowite zdublowanie plonu."),
            new RarePerk("RARE_UNYIELDING_SPIRIT", "Nieugięty Duch", Material.EXPERIENCE_BOTTLE, "Szansa na spory wybuch orbów doświadczenia."),
            new RarePerk("RARE_CHAOS_CORE", "Rdzeń Chaosu", Material.FIRE_CHARGE, "Rzadka szansa na natychmiastowe skopanie do 3 sąsiednich bloków."),
            new RarePerk("RARE_DEPTHS_BLESSING", "Błogosławieństwo Głębi", Material.GLOW_BERRIES, "Drobna regeneracja i sytość podczas ciągłego kopania.")
    );
}