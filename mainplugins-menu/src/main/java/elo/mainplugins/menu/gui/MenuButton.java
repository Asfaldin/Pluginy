package elo.mainplugins.menu.gui;

import org.bukkit.Material;

import java.util.List;

/** Jeden przycisk Głównego Menu Serwera - ikona + tekst + komenda wołana po kliknięciu. */
public record MenuButton(int slot, Material material, String nazwa, List<String> lore, String komenda) {
}
