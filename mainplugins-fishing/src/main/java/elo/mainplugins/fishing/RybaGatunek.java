package elo.mainplugins.fishing;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * Jeden gatunek ryby - patrz FishingManager.wczytajGatunki. customId musi zgadzać się 1:1 z
 * mainplugins-shop (kategoria "ryby_wedkarskie", patrz categories/ryby_wedkarskie.yml) i z
 * customId-ami wymogów questów kategorii "Rybak" w mainplugins-quests - to jedyny "kontrakt"
 * łączący te trzy niezależne moduły (patrz CustomItemKeys w mainplugins-core).
 *
 * kgTypowyMin/kgTypowyMax - typowy zakres wagi tego gatunku w kg (patrz
 * FishingManager.losujWageDziesieteKg: rozkład normalny wyśrodkowany na środku tego
 * zakresu). kgMin/kgMax - twardy limit (skrajnie rzadkie, "jackpotowe" odchyły w dół/górę) -
 * wynik spoza tego przedziału jest odrzucany i losowany ponownie, więc rozkład realnie
 * zanika płynnie do zera na krańcach zamiast "piętrzyć się" dokładnie na granicy.
 */
public record RybaGatunek(String customId, String nazwa, Material material, NamedTextColor kolor, Rzadkosc rzadkosc, int waga,
                           double kgTypowyMin, double kgTypowyMax, double kgMin, double kgMax) {

    /** Drabinka rzadkości, "Wariant A" ustalony z userem 2026-08-29 - 6 szczebli, żaden gatunek na razie nie używa MITYCZNA (brak modeli/tekstur pod ten poziom, patrz rozmowa). */
    public enum Rzadkosc { ZWYKLA, NIEZWYKLA, RZADKA, EPICKA, LEGENDARNA, MITYCZNA }
}
