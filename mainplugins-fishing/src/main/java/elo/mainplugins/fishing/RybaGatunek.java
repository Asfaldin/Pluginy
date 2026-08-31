package elo.mainplugins.fishing;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * Jeden gatunek ryby - patrz FishingManager.wczytajGatunki. Sklep i questy NIE odwołują się
 * już do konkretnych customId ryb (user 2026-08-31c: usunięta kategoria sklepu
 * "ryby_wedkarskie" - referowała nieaktualne, dawno porzucone gatunki z systemu łowienia w
 * powietrzu; questy kategorii "Rybak" w mainplugins-quests przełączone na zwykłe wanilijskie
 * ryby zamiast custom-id) - jedyny konsument customId poza samym mainplugins-fishing to
 * na razie Bezdenne Wiaderko (patrz WiaderkoManager).
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
