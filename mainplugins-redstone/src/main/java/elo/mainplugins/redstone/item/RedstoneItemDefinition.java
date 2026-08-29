package elo.mainplugins.redstone.item;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;

/** Jeden sparsowany wpis z redstone-items.yml - patrz RedstoneItemLoader. */
public record RedstoneItemDefinition(String id, RedstoneItemKind kind, Material material, Component name,
                                      List<Component> lore, Key model, boolean glint) {
}
