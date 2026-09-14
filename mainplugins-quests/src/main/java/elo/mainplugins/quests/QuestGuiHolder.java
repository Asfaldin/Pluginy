package elo.mainplugins.quests;

import elo.mainplugins.quests.model.SlotRole;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/** Znacznik naszych okien + co leży na którym polu (kliknięcia bez zgadywania po tytule okna). */
final class QuestGuiHolder implements InventoryHolder {

    enum Kind { MAIN, CATEGORY }

    private final Kind kind;
    private final String categoryId;
    private final int page;
    private final Map<Integer, String> categorySlots = new HashMap<>();
    private final Map<Integer, Integer> questSlots = new HashMap<>();
    private final Map<Integer, SlotRole> navSlots = new HashMap<>();
    private Inventory inventory;

    QuestGuiHolder(Kind kind, String categoryId, int page) {
        this.kind = kind;
        this.categoryId = categoryId;
        this.page = page;
    }

    Inventory create(int size, Component title) {
        inventory = Bukkit.createInventory(this, size, title);
        return inventory;
    }

    Kind kind() { return kind; }
    String categoryId() { return categoryId; }
    int page() { return page; }
    Map<Integer, String> categorySlots() { return categorySlots; }
    Map<Integer, Integer> questSlots() { return questSlots; }
    Map<Integer, SlotRole> navSlots() { return navSlots; }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
