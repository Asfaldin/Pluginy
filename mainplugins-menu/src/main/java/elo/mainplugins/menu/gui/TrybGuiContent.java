package elo.mainplugins.menu.gui;

import org.bukkit.Material;

/** Cały ekran wyboru trybu gry wczytany z tryb-gui.yml (patrz TrybGuiLoader). */
public record TrybGuiContent(int size, Material tlo, TrybOpcja classic, TrybOpcja mmo) {

    public TrybOpcja naSlocie(int slot) {
        if (classic.slot() == slot) return classic;
        if (mmo.slot() == slot) return mmo;
        return null;
    }
}
