package elo.mainplugins.redstone.planter;

import elo.mainplugins.core.util.GuiUtils;
import elo.mainplugins.redstone.block.DeviceStore;
import elo.mainplugins.redstone.block.PlacedDevice;
import elo.mainplugins.redstone.item.RedstoneItemKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * GUI wkładania nasion + logika "zasadź na sygnał od stacji". Sadzarka NIGDY nie sadzi
 * sama z siebie ani na timerze - wyłącznie gdy StationManager wywoła
 * {@link #trigger(PlacedDevice, Block)} po tym, jak dron znajdzie puste pole. Stan nasion
 * (materiał + licznik) siedzi wprost w {@link PlacedDevice} i jest zapisywany przez
 * {@link DeviceStore}.
 */
public final class PlanterManager implements Listener {

    private static final int SLOT_NASION = 4;

    private final Plugin plugin;
    private final DeviceStore store;

    public PlanterManager(Plugin plugin, DeviceStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void openGui(Player player, PlacedDevice device) {
        PlanterGuiHolder holder = new PlanterGuiHolder(device.key);
        Inventory gui = plugin.getServer().createInventory(holder, 9, Component.text("Sadzarka Automatyczna", NamedTextColor.GOLD));
        holder.setInventory(gui);
        GuiUtils.fillBackground(gui);

        if (device.seed != null && device.seedCount > 0) {
            gui.setItem(SLOT_NASION, new ItemStack(device.seed, device.seedCount));
        }
        player.openInventory(gui);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PlanterGuiHolder)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return; // klik we własny ekwipunek gracza - OK

        if (slot != SLOT_NASION) {
            event.setCancelled(true);
            return;
        }

        ItemStack kursor = event.getCursor();
        if (kursor != null && kursor.getType() != Material.AIR && !CropMapping.SEED_TO_CROP.containsKey(kursor.getType())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendActionBar(Component.text("Sadzarka umie zasadzić tylko: pszenicę/marchew/ziemniak/burak.", NamedTextColor.RED));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof PlanterGuiHolder holder)) return;
        PlacedDevice device = store.getDevice(holder.key);
        if (device == null) return;

        ItemStack slot = event.getInventory().getItem(SLOT_NASION);
        if (slot == null || slot.getType() == Material.AIR || !CropMapping.SEED_TO_CROP.containsKey(slot.getType())) {
            device.seed = null;
            device.seedCount = 0;
        } else {
            device.seed = slot.getType();
            device.seedCount = slot.getAmount();
        }
        store.oznaczZmiane();
    }

    /** Woła DeviceListeners przy zniszczeniu sadzarki - oddaje niezużyte nasiona pod nogi. */
    public void dropStoredSeeds(PlacedDevice device) {
        if (device.seed == null || device.seedCount <= 0) return;
        Block b = device.key.toBlock();
        if (b != null) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), new ItemStack(device.seed, device.seedCount));
        }
        device.seed = null;
        device.seedCount = 0;
        store.oznaczZmiane();
    }

    /** Woła StationManager, gdy dron znajdzie puste pole gdzieś w siatce 5x5 wokół pola sadzarki. */
    public void trigger(PlacedDevice device, Block targetFarmland) {
        if (device.kind != RedstoneItemKind.PLANTER) return;
        if (device.seed == null || device.seedCount <= 0) return;
        if (targetFarmland == null || targetFarmland.getType() != Material.FARMLAND) return;
        if (targetFarmland.getRelative(BlockFace.UP).getType() != Material.AIR) return;

        Material crop = CropMapping.SEED_TO_CROP.get(device.seed);
        if (crop == null) return;

        targetFarmland.getRelative(BlockFace.UP).setType(crop);
        device.seedCount--;
        if (device.seedCount <= 0) device.seed = null;
        store.oznaczZmiane();
    }
}
