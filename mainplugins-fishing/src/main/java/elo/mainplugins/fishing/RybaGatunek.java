package elo.mainplugins.fishing;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/**
 * Jeden gatunek ryby - patrz FishingManager.GATUNKI. customId musi zgadzać się 1:1 z
 * sklep.yml (kategoria "ryby_wedkarskie") i z customId-ami wymogów questów kategorii
 * "Rybak" w mainplugins-quests - to jedyny "kontrakt" łączący te trzy niezależne moduły
 * (patrz CustomItemKeys w mainplugins-core).
 */
record RybaGatunek(String customId, String nazwa, Material material, NamedTextColor kolor, Rzadkosc rzadkosc, int waga) {

    enum Rzadkosc { ZWYKLA, NIEZWYKLA, RZADKA, EPICKA, LEGENDARNA }
}