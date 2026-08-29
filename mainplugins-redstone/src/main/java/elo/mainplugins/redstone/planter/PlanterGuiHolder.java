package elo.mainplugins.redstone.planter;

import elo.mainplugins.redstone.block.BlockKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** Odróżnia GUI sadzarki od innych otwartych ekwipunków w onInventoryClick/onInventoryClose. Trzyma pozycję bloku sadzarki. */
public final class PlanterGuiHolder implements InventoryHolder {

    public final BlockKey key;
    private Inventory inventory;

    public PlanterGuiHolder(BlockKey key) {
        this.key = key;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
