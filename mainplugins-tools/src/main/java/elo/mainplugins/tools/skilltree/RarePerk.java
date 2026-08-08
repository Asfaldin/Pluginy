package elo.mainplugins.tools.skilltree;

import org.bukkit.Material;

/**
 * Rzadki perk losowany co RARE_ROLL_INTERVAL poziomów (patrz ToolSkillManager) -
 * generyczny odpowiednik RarePerks.RarePerk z pakietu kilofa, współdzielony przez
 * wszystkie narzędzia poza kilofem.
 */
public record RarePerk(String id, String displayName, Material icon, String opis) {}