package elo.mainplugins;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StorageManager implements Listener {

    private final Plugin plugin;
    private final File plikSchowka;
    private final FileConfiguration configSchowka;
    private MenuPomocyManager menuPomocyManager;
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    public StorageManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikSchowka = new File(plugin.getDataFolder(), "schowki.yml");
        if (!plikSchowka.exists()) {
            plikSchowka.getParentFile().mkdirs();
            try { plikSchowka.createNewFile(); } catch (IOException ignored) {}
        }
        this.configSchowka = YamlConfiguration.loadConfiguration(plikSchowka);
    }

    public void setMenuPomocyManager(MenuPomocyManager menuPomocyManager) {
        this.menuPomocyManager = menuPomocyManager;
    }

    public void otworzSchowekZMenu(Player player) {
        otwartoZMenu.put(player.getUniqueId(), true);
        otworzSchowek(player);
    }

    public void otworzSchowekZKomendy(Player player) {
        otwartoZMenu.put(player.getUniqueId(), false);
        otworzSchowek(player);
    }

    private void otworzSchowek(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Twój Schowek", NamedTextColor.DARK_AQUA, TextDecoration.BOLD));

        // Wczytywanie itemów z konfiguracji do slotów 0-44
        if (configSchowka.contains(player.getUniqueId().toString())) {
            for (int i = 0; i < 45; i++) {
                ItemStack item = configSchowka.getItemStack(player.getUniqueId() + "." + i);
                if (item != null) gui.setItem(i, item);
            }
        }

        // Tło paska dolnego
        ItemStack tlo = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta mTlo = tlo.getItemMeta();
        mTlo.displayName(Component.empty());
        tlo.setItemMeta(mTlo);
        for (int i = 45; i < 54; i++) {
            gui.setItem(i, tlo);
        }

        // Przycisk powrotu/wyjścia
        boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
        ItemStack powrot = new ItemStack(zMenu ? Material.NETHER_STAR : Material.BARRIER);
        ItemMeta mPowrot = powrot.getItemMeta();
        mPowrot.displayName(Component.text(zMenu ? "« Wróć do Menu głównego" : "Zamknij Schowek", NamedTextColor.RED, TextDecoration.BOLD));
        powrot.setItemMeta(mPowrot);
        gui.setItem(49, powrot);

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getView().title().toString().contains("Twój Schowek")) return;
        Player player = (Player) event.getPlayer();
        Inventory gui = event.getInventory();

        // Zapisywanie tylko slotów 0-44
        configSchowka.set(player.getUniqueId().toString(), null); // czyszczenie starych
        for (int i = 0; i < 45; i++) {
            ItemStack item = gui.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                configSchowka.set(player.getUniqueId() + "." + i, item);
            }
        }
        try { configSchowka.save(plikSchowka); } catch (IOException ignored) {}
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().title().toString().contains("Twój Schowek")) return;
        Player player = (Player) event.getWhoClicked();

        // Blokada interakcji z dolnym paskiem schowka (sloty 45-53)
        if (event.getRawSlot() >= 45 && event.getRawSlot() <= 53) {
            event.setCancelled(true);
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null) {
                if (clicked.getType() == Material.NETHER_STAR) {
                    player.closeInventory();
                    Bukkit.getScheduler().runTaskLater(plugin, () -> menuPomocyManager.otworzMenuPomocy(player), 1L);
                } else if (clicked.getType() == Material.BARRIER) {
                    player.closeInventory();
                }
            }
        }
    }
}