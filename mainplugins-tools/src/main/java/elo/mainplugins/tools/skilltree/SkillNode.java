package elo.mainplugins.tools.skilltree;

import org.bukkit.Material;

/**
 * Pojedynczy węzeł drzewka umiejętności - identyczny kształt dla każdego narzędzia
 * (kilof miał swoją nested wersję w PickaxeSkillTrees; to jest jej generyczny
 * odpowiednik, współdzielony przez ToolSkillManager i wszystkie narzędzia poza
 * kilofem, który zostaje na swoim już przetestowanym, osobnym systemie).
 */
public record SkillNode(String id, String displayName, Material icon, String opis) {}