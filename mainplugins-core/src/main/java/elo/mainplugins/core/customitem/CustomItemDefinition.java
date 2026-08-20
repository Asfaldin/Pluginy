package elo.mainplugins.core.customitem;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;

/** Jeden sparsowany wpis z custom-items.yml - patrz CustomItemManager#wczytajWpis. */
record CustomItemDefinition(String id, Material material, Component name, List<Component> lore, Key model, boolean glint) {
}
