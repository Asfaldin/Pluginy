package elo.mainplugins;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class MenuPomocyManager implements Listener {

    public void otworzMenuPomocy(Player player) {
        // Powiększone menu (45 slotów), żeby pomieścić wszystkie funkcje
        Inventory gui = Bukkit.createInventory(null, 45, Component.text("Główne Menu Serwera", NamedTextColor.GOLD, TextDecoration.BOLD));

        // Wypełnienie tła szarym szkłem
        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta mTlo = tlo.getItemMeta();
        mTlo.displayName(Component.empty());
        tlo.setItemMeta(mTlo);
        for (int i = 0; i < 45; i++) {
            gui.setItem(i, tlo);
        }

        // Górny rząd GUI - Główne systemy z powrotem do menu
        gui.setItem(11, stworzIkone(Material.EMERALD, "Sklep Serwerowy", "Kupuj i sprzedawaj u serwera (/sklep)"));
        gui.setItem(12, stworzIkone(Material.GOLD_INGOT, "Rynek Graczy", "Handluj z innymi graczami (/targ)"));
        gui.setItem(13, stworzIkone(Material.GRASS_BLOCK, "Twoja Wyspa", "Zarządzaj swoją wyspą (/is)"));
        gui.setItem(14, stworzIkone(Material.BOOK, "Zadania (Questy)", "Odbierz nagrody za zadania (/quest)"));
        gui.setItem(15, stworzIkone(Material.CHEST, "Schowek", "Bezpieczne miejsce na przedmioty (/itemy)"));

        // Dolny rząd GUI - Szybkie akcje (bez własnych, zaawansowanych okien z powrotem)
        gui.setItem(29, stworzIkone(Material.DIAMOND_PICKAXE, "Startowe Narzędzia", "Odbierz ulepszalne narzędzia (/narzedzia)"));
        gui.setItem(33, stworzIkone(Material.HOPPER, "Szybka Sprzedaż", "Sprzedaj masowo przedmioty z eq (/sellall)"));

        player.openInventory(gui);
    }

    private ItemStack stworzIkone(Material mat, String nazwa, String opis) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, NamedTextColor.YELLOW, TextDecoration.BOLD));
        meta.lore(List.of(Component.text(opis, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().title().toString().contains("Główne Menu Serwera")) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null) return;

        Material typ = event.getCurrentItem().getType();

        // Zabezpieczenie przed klikaniem w szare szkło
        if (typ == Material.GRAY_STAINED_GLASS_PANE) return;

        // Zamknięcie głównego menu przed wymuszeniem komendy
        player.closeInventory();

        // Magiczne wywoływanie komend w tle.
        // Dopisanie "zmenu" informuje dany system, że ma wyświetlić przycisk powrotu.
        switch (typ) {
            case EMERALD -> player.performCommand("sklep zmenu");
            case GOLD_INGOT -> player.performCommand("targ zmenu");
            case GRASS_BLOCK -> player.performCommand("is zmenu");
            case BOOK -> player.performCommand("quest zmenu");
            case CHEST -> player.performCommand("itemy zmenu");
            case DIAMOND_PICKAXE -> player.performCommand("narzedzia");
            case HOPPER -> player.performCommand("sellall");
        }
    }
}