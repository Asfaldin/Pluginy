package elo.mainplugins.skyblock.gui;

import org.bukkit.Material;

import java.util.List;

/**
 * Jeden ekran GUI systemu wysp - rozmiar okna, material tła, pola z tłem i lista przycisków.
 * {@code tloPola} = null oznacza tło na wszystkich polach (domyślnie), lista = tło tylko na tych polach.
 * {@code strony} = ile stron ma okno (1 = bez strzałek); dokłada je właściciel przyciskiem "Dodaj stronę" w aplikacji.
 */
public record IslandScreen(int size, Material tlo, List<Integer> tloPola, List<IslandGuiButton> przyciski, int strony) {

    /** Pierwszy przycisk o danej akcji, albo null - patrz komentarz o wariantach w wyspy-gui.yml. */
    public IslandGuiButton przycisk(String akcja) {
        for (IslandGuiButton b : przyciski) {
            if (b.akcja().equals(akcja)) return b;
        }
        return null;
    }
}
