package elo.mainplugins.tools.skilltree;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Rzadkość karty ulepszenia oferowanej przy levelowaniu (patrz ToolSkillManager#rollCardOffer).
 * "waga" to względna szansa pojawienia się w ofercie 4 kart - im wyższa, tym częściej
 * karta danej rzadkości wypada. Legendarny = dzisiejsza pula RarePerks (unikalne,
 * max 1 sztuka na całe życie przedmiotu) - reszta rzadkości to stackowalne karty.
 */
public enum Rarity {
    COMMON("Zwykły", NamedTextColor.GRAY, 55),
    RARE("Rzadki", NamedTextColor.AQUA, 28),
    EPIC("Epicki", NamedTextColor.LIGHT_PURPLE, 13),
    LEGENDARY("Legendarny", NamedTextColor.GOLD, 4);

    public final String label;
    public final NamedTextColor color;
    public final int waga;

    Rarity(String label, NamedTextColor color, int waga) {
        this.label = label;
        this.color = color;
        this.waga = waga;
    }
}