package elo.mainplugins.tools.skilltree;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Jedna z 3 tematycznych kategorii kart danego narzędzia - czysto porządkowa/wizualna
 * (kolor, ikonka), NIE jest już osobną pulą losowania ani menu do przeglądania jak
 * dawniej. Oferta 4 kart przy levelowaniu losuje z POŁĄCZONEJ puli wszystkich 3 kategorii
 * - podział na kategorie służy tylko czytelności (np. w liście "Aktualne Ulepszenia").
 */
public record SkillBranch(String id, String displayName, NamedTextColor color, Material icon, List<SkillCard> cards) {}