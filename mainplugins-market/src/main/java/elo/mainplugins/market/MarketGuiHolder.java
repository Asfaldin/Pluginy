package elo.mainplugins.market;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/** Znacznik okien Targu + co leży na którym polu (bez zgadywania po tytule czy materiale). */
final class MarketGuiHolder implements InventoryHolder {

    enum Kind { MAIN, SEARCH, MAILBOX }

    private final Kind kind;
    private final int page;
    private final boolean fromMenu;
    /** slot -> id oferty */
    private final Map<Integer, String> offers = new HashMap<>();
    /** slot -> id przycisku (prev, next, ...) */
    private final Map<Integer, String> buttons = new HashMap<>();
    /** slot -> numer przedmiotu w skrzynce */
    private final Map<Integer, Integer> mail = new HashMap<>();
    private Inventory inventory;

    MarketGuiHolder(Kind kind, int page, boolean fromMenu) {
        this.kind = kind;
        this.page = page;
        this.fromMenu = fromMenu;
    }

    Inventory create(Component title) {
        inventory = Bukkit.createInventory(this, 54, title);
        return inventory;
    }

    Kind kind() { return kind; }
    int page() { return page; }
    boolean fromMenu() { return fromMenu; }
    Map<Integer, String> offers() { return offers; }
    Map<Integer, String> buttons() { return buttons; }
    Map<Integer, Integer> mail() { return mail; }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
