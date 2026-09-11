package elo.mainplugins.core.customitem;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;

import java.util.List;
import java.util.Map;

/** Wpis katalogu przetłumaczony na typy Bukkita - patrz CustomItemManager#toDefinition. */
record CustomItemDefinition(String id, Material material, Component name, List<Component> lore, Key model,
                            boolean glint, Map<Enchantment, Integer> enchants, boolean unbreakable) {
}
