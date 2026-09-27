package elo.mainplugins.menu;

import elo.mainplugins.core.api.LangService;
import elo.mainplugins.menu.model.MenuButton;
import elo.mainplugins.menu.model.MenuConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;

/**
 * Główne menu serwera (/menu). Każdy przycisk woła komendę gracza (player.performCommand); dopisane
 * "zmenu" to konwencja z MenuBridge (core) - plugin docelowy wie wtedy, że gracz przyszedł z menu.
 * Przycisk z "requires" pokazuje się tylko wtedy, gdy ten plugin jest na serwerze.
 */
public final class MenuManager implements Listener {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final LangService lang;
    private MenuConfig config = MenuConfig.empty();

    public MenuManager(Plugin plugin, LangService lang) {
        this.plugin = plugin;
        this.lang = lang;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "menu.yml");
        if (!file.exists()) copyDefaults(file);
        config = MenuConfigParser.parse(YamlConfiguration.loadConfiguration(file),
                name -> { Material m = Material.matchMaterial(name); return m != null && m.isItem(); },
                plugin.getLogger()::warning);
        if (new File(plugin.getDataFolder(), "menu-gui.yml").exists()) {
            plugin.getLogger().warning("menu-gui.yml is no longer read - the menu now lives in menu.yml.");
        }
    }

    /** Pierwszy start: domyślne menu w języku serwera (defaults/<język>/menu.yml), brak = angielskie. */
    private void copyDefaults(File file) {
        String resource = "defaults/" + lang.language() + "/menu.yml";
        if (plugin.getResource(resource) == null) resource = "defaults/en/menu.yml";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return;
            file.getParentFile().mkdirs();
            Files.copy(in, file.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write menu.yml: " + e.getMessage());
        }
    }

    public int buttonCount() {
        return config.buttons().size();
    }

    private static boolean available(MenuButton b) {
        return b.requires().isEmpty() || Bukkit.getPluginManager().isPluginEnabled(b.requires());
    }

    public void open(Player player) {
        MenuGuiHolder holder = new MenuGuiHolder();
        Inventory inv = holder.create(config.size(), lang.msg(plugin, "menu.title", Map.of()));
        Material bg = Material.matchMaterial(config.background());
        if (bg != null && bg.isItem()) {
            ItemStack filler = new ItemStack(bg);
            ItemMeta meta = filler.getItemMeta();
            meta.displayName(Component.empty());
            filler.setItemMeta(meta);
            for (int i = 0; i < config.size(); i++) inv.setItem(i, filler);
        }
        for (MenuButton b : config.buttons()) {
            if (available(b)) inv.setItem(b.slot(), icon(b));
        }
        player.openInventory(inv);
    }

    private static ItemStack icon(MenuButton b) {
        Material m = Material.matchMaterial(b.material());
        ItemStack item = new ItemStack(m != null ? m : Material.STONE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(b.name()));
        meta.lore(b.lore().stream().map(MenuManager::text).toList());
        item.setItemMeta(meta);
        return item;
    }

    /** Tekst z kodami & - bez domyślnej kursywy Minecrafta w nazwach i opisach. */
    private static Component text(String legacy) {
        return SER.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuGuiHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getInventory()) return;
        for (MenuButton b : config.buttons()) {
            if (b.slot() != event.getRawSlot() || !available(b)) continue;
            player.closeInventory();
            if (!b.command().isEmpty()) player.performCommand(b.command());
            return;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MenuGuiHolder) event.setCancelled(true);
    }
}
