package elo.mainplugins.menu.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;

/** Jedna opcja na ekranie wyboru trybu gry (Classic+/MMO+) - patrz TrybGuiLoader/tryb-gui.yml. */
public record TrybOpcja(int slot, Material material, Component nazwa, List<Component> lore,
                         boolean zablokowany, Component komunikatZablokowany) {
}
