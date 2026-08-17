package elo.mainplugins.tools.pickaxe;

import org.bukkit.Material;

/**
 * Gem - uniwersalny modyfikator kilofa (działa identycznie niezależnie od PickaxeType,
 * żeby uniknąć 4×3 wariantów w pierwszej wersji - patrz PickaxeSkillManager#pkGems).
 * Dropi w grze (mainplugins-quests/dropy - do podłączenia osobno), osadzany w kowadle
 * (patrz PickaxeSkillManager#onPrepareAnvil, wzorem FishingManager#onPrepareAnvil),
 * max {@link PickaxeSkillManager#MAX_GEMY} sztuk na kilof naraz.
 */
public enum GemType {

    /** +10% do głównej auto-staty typu (haste/monety/szczęście/zasięg - cokolwiek dany typ liczy jako "auto"). */
    MOCY("Gem Mocy", Material.AMETHYST_SHARD, "Wzmacnia główną automatyczną statystykę kilofa o 10%.", 0.10),

    /** Uniwersalny bonus do szansy Bonusu z Bruku (ten sam mechanizm co SZCZ_SZCZESCIE/auto-luck, ale działa na KAŻDY typ). */
    SZCZESCIA("Gem Szczęścia", Material.EMERALD, "Zwiększa szansę na Bonus z Bruku o 2 punkty procentowe.", 2.0),

    /** Szansa na dodatkowy punkt exp przy kopaniu - szybsze levelowanie. */
    DOSWIADCZENIA("Gem Doświadczenia", Material.LAPIS_LAZULI, "Szansa na dodatkowy punkt doświadczenia przy kopaniu.", 0.15);

    private final String displayName;
    private final Material icon;
    private final String opis;
    private final double wartosc;

    GemType(String displayName, Material icon, String opis, double wartosc) {
        this.displayName = displayName;
        this.icon = icon;
        this.opis = opis;
        this.wartosc = wartosc;
    }

    public String displayName() { return displayName; }
    public Material icon() { return icon; }
    public String opis() { return opis; }

    /** Znaczenie zależy od gemu: MOCY = mnożnik (0.10 = +10%), SZCZESCIA = punkty procentowe, DOSWIADCZENIA = szansa 0-1. */
    public double wartosc() { return wartosc; }
}
