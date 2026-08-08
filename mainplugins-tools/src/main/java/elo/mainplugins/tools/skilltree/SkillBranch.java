package elo.mainplugins.tools.skilltree;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Jedna z 3 gałęzi drzewka danego narzędzia. Odpowiednik enuma Branch z
 * PickaxeSkillTrees, ale jako zwykła lista (nie enum) - każde narzędzie dostarcza
 * swoją własną listę 3 gałęzi do konstruktora ToolSkillManager, więc silnik nie
 * musi znać z góry, ile/jakie narzędzia będą go używać.
 */
public record SkillBranch(String id, String displayName, NamedTextColor color, Material icon, List<SkillNode> nodes) {}