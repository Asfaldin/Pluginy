package elo.mainplugins.crates;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** Znacznik naszych okien (animacja, podgląd) - po nim blokujemy klikanie zamiast po tytule. */
final class CrateGuiHolder implements InventoryHolder {

    private Inventory inventory;

    Inventory create(int size, Component title) {
        inventory = Bukkit.createInventory(this, size, title);
        return inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
