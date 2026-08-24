package elo.mainplugins.skyblock.gui;

import org.bukkit.Material;

import java.util.List;

/** Jeden ekran GUI systemu wysp - rozmiar okna, material tła i lista przycisków. */
public record IslandScreen(int size, Material tlo, List<IslandGuiButton> przyciski) {

    /** Pierwszy przycisk o danej akcji, albo null - patrz komentarz o wariantach w wyspy-gui.yml. */
    public IslandGuiButton przycisk(String akcja) {
        for (IslandGuiButton b : przyciski) {
            if (b.akcja().equals(akcja)) return b;
        }
        return null;
    }
}
