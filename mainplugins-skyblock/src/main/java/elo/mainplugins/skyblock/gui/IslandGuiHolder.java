package elo.mainplugins.skyblock.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Znacznik okien systemu wysp - kliknięcia rozpoznajemy po nim (który ekran), a nie po tytule.
 * Tytuły są w lang i admin może je zmienić, więc porównywanie tekstu tytułu psułoby obsługę kliknięć.
 */
public final class IslandGuiHolder implements InventoryHolder {

    private final String ekran;
    private Inventory inventory;

    private IslandGuiHolder(String ekran) {
        this.ekran = ekran;
    }

    /** Nowe okno danego ekranu (np. "panel", "ustawienia") z tytułem z lang. */
    public static Inventory create(String ekran, int size, Component title) {
        IslandGuiHolder holder = new IslandGuiHolder(ekran);
        holder.inventory = Bukkit.createInventory(holder, size, title);
        return holder.inventory;
    }

    public String ekran() {
        return ekran;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
