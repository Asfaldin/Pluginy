package elo.mainplugins.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** Okno menu rozpoznawane po właścicielu, nie po tytule - tytuł można zmieniać do woli. */
public final class MenuGuiHolder implements InventoryHolder {

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
