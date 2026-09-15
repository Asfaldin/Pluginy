package elo.mainplugins.shop;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/** Znacznik okien sklepu + co leży na którym polu (bez zgadywania po tytule czy materiale). */
final class ShopGuiHolder implements InventoryHolder {

    enum Kind { MAIN, CATEGORY, PICKER, SEARCH }

    /** Pozycja w sklepie: kategoria, czy z rotacji, numer, klucz (sprawdzany po reloadzie). */
    record Ref(String category, boolean rotating, int index, String key) {}

    private final Kind kind;
    private final String category;
    private final int page;
    private final boolean fromMenu;
    private final Ref picked;
    private final Map<Integer, Ref> items = new HashMap<>();
    private final Map<Integer, String> categories = new HashMap<>();
    private final Map<Integer, Integer> amounts = new HashMap<>();
    /** slot -> rola przycisku (SEARCH, EXIT, NAV_BACK, ...) */
    private final Map<Integer, String> buttons = new HashMap<>();
    private Inventory inventory;

    ShopGuiHolder(Kind kind, String category, int page, boolean fromMenu, Ref picked) {
        this.kind = kind;
        this.category = category;
        this.page = page;
        this.fromMenu = fromMenu;
        this.picked = picked;
    }

    Inventory create(int size, Component title) {
        inventory = Bukkit.createInventory(this, size, title);
        return inventory;
    }

    Kind kind() { return kind; }
    String category() { return category; }
    int page() { return page; }
    boolean fromMenu() { return fromMenu; }
    Ref picked() { return picked; }
    Map<Integer, Ref> items() { return items; }
    Map<Integer, String> categories() { return categories; }
    Map<Integer, Integer> amounts() { return amounts; }
    Map<Integer, String> buttons() { return buttons; }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
