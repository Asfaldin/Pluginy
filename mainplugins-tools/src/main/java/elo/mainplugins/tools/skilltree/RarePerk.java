package elo.mainplugins.tools.skilltree;

import org.bukkit.Material;

/**
 * Rzadki perk losowany co RARE_ROLL_INTERVAL poziomów (patrz ToolSkillManager) -
 * generyczny odpowiednik RarePerks.RarePerk z pakietu kilofa, współdzielony przez
 * wszystkie narzędzia poza kilofem.
 *
 * minTier - patrz SkillCard#minTier, ta sama idea zastosowana do puli legendarnej.
 * Konstruktor 4-argumentowy (minTier=0) zachowany dla narzędzi bez progów tierowych.
 */
public record RarePerk(String id, String displayName, Material icon, String opis, int minTier) {
    public RarePerk(String id, String displayName, Material icon, String opis) {
        this(id, displayName, icon, opis, 0);
    }
}