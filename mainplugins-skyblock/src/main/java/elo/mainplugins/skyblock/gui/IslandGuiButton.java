package elo.mainplugins.skyblock.gui;

import org.bukkit.Material;


/**
 * Jeden przycisk w GUI systemu wysp - slot + ikona, powiązane z zachowaniem przez {@code akcja}
 * (patrz komentarz w wyspy-gui.yml). {@code materialWylaczone} ma sens tylko dla przycisków-przełączników
 * (ikona w stanie "wyłączone"). {@code tekst} = klucz w lang, pod nim name, lore-1, lore-2.
 */
public record IslandGuiButton(int slot, String akcja, Material material, Material materialWylaczone, String tekst, Integer strona) {

    /** Czy przycisk stoi na tej stronie okna (strona od 0); bez "strona" w pliku = na każdej. */
    public boolean naStronie(int page) {
        return strona == null || strona == page + 1;
    }
}
