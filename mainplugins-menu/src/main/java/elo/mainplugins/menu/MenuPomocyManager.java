package elo.mainplugins.menu;

import elo.mainplugins.menu.gui.MenuButton;
import elo.mainplugins.menu.gui.MenuGuiContent;
import elo.mainplugins.menu.gui.MenuGuiLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Menu jest w pełni niezależne - nie zna żadnego innego pluginu z rodziny
 * Mainplugins. Każdy klik woła komendę Bukkita (player.performCommand),
 * a docelowy plugin sam decyduje, jak ją obsłużyć. Dopisanie "zmenu" jako
 * argumentu to konwencja z MenuBridge (mainplugins-core) - patrz tam po
 * pełny opis mechanizmu.
 *
 * Układ (rozmiar, tło, sloty, ikony, teksty, komendy) jest w pełni wczytywany
 * z menu-gui.yml (patrz MenuGuiLoader) - przeładowanie na żywo: /@reloadmenu.
 */
public class MenuPomocyManager implements Listener {

    private static final String TYTUL = "Główne Menu Serwera";

    private final Plugin plugin;
    private MenuGuiContent gui;

    public MenuPomocyManager(Plugin plugin) {
        this.plugin = plugin;
        this.gui = MenuGuiLoader.load(plugin);
    }

    /** Wywoływane przez /@reloadmenu - podmienia cały układ menu bez restartu serwera. */
    public void przeladujKonfiguracje() {
        this.gui = MenuGuiLoader.load(plugin);
    }

    public void otworzMenuPomocy(Player player) {
        Inventory inv = Bukkit.createInventory(null, gui.size(), Component.text(TYTUL, NamedTextColor.GOLD, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(gui.tlo());
        ItemMeta mTlo = tlo.getItemMeta();
        mTlo.displayName(Component.empty());
        tlo.setItemMeta(mTlo);
        for (int i = 0; i < gui.size(); i++) {
            inv.setItem(i, tlo);
        }

        for (MenuButton przycisk : gui.przyciski()) {
            inv.setItem(przycisk.slot(), stworzIkone(przycisk));
        }

        player.openInventory(inv);
    }

    private ItemStack stworzIkone(MenuButton przycisk) {
        ItemStack item = new ItemStack(przycisk.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(przycisk.nazwa(), NamedTextColor.YELLOW, TextDecoration.BOLD));
        meta.lore(przycisk.lore().stream()
                .map(linia -> (Component) Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                .toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().title().toString().contains(TYTUL)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null) return;

        MenuButton przycisk = gui.naSlocie(event.getRawSlot());
        if (przycisk == null) return; // tło albo pusty slot

        player.closeInventory();
        player.performCommand(przycisk.komenda());
    }
}
