package elo.mainplugins.advancements.gui;

import elo.mainplugins.advancements.model.AchievementDef;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Odróżnia panel osiągnięć od każdego innego otwartego ekwipunku w
 * onInventoryClick - wzorzec z PlanterGuiHolder (mainplugins-redstone).
 * Trzyma też stan nawigacji: która zakładka i strona są otwarte oraz mapę
 * slot -> osiągnięcie dla bieżącego widoku (przebudowywaną przy każdym renderze).
 */
public final class AchievementsGuiHolder implements InventoryHolder {

    private final boolean zMenu;
    int kategoriaIndex;
    int strona;
    final Map<Integer, AchievementDef> sloty = new HashMap<>();
    private Inventory inventory;

    public AchievementsGuiHolder(boolean zMenu) {
        this.zMenu = zMenu;
    }

    public boolean zMenu() {
        return zMenu;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
