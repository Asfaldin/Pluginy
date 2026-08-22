package elo.mainplugins.menu.gui;

import org.bukkit.Material;

import java.util.List;

/** Cały układ Głównego Menu Serwera wczytany z menu-gui.yml (patrz MenuGuiLoader). */
public record MenuGuiContent(int size, Material tlo, List<MenuButton> przyciski) {

    public MenuButton naSlocie(int slot) {
        for (MenuButton p : przyciski) {
            if (p.slot() == slot) return p;
        }
        return null;
    }
}
