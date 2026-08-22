package elo.mainplugins.skyblock.gui;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Jeden przycisk w GUI systemu wysp - slot + ikona + tekst, powiązane z zachowaniem
 * przez {@code akcja} (patrz komentarz w wyspy-gui.yml). {@code materialWylaczone} ma
 * sens tylko dla przycisków-przełączników (ikona w stanie "wyłączone").
 */
public record IslandGuiButton(int slot, String akcja, Material material, Material materialWylaczone,
                               String nazwa, NamedTextColor kolor, List<String> lore) {
}
