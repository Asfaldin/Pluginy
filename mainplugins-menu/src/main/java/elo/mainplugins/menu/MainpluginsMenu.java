package elo.mainplugins.menu;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsMenu extends JavaPlugin {

    @Override
    public void onEnable() {
        MenuPomocyManager menuPomocyManager = new MenuPomocyManager(this);
        getServer().getPluginManager().registerEvents(menuPomocyManager, this);

        if (getCommand("menu") != null) {
            getCommand("menu").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                menuPomocyManager.otworzMenuPomocy(player);
                return true;
            });
            getCommand("menu").setTabCompleter((sender, command, alias, args) -> java.util.List.of());
        }

        // Osobny executor: /@reloadmenu ma sens też z konsoli, nie tylko od gracza.
        // Uprawnienie (mainplugins.menu.reload, domyślnie op) pilnuje tego plugin.yml.
        if (getCommand("@reloadmenu") != null) {
            getCommand("@reloadmenu").setExecutor((sender, command, label, args) -> {
                menuPomocyManager.przeladujKonfiguracje();
                sender.sendMessage("§aMenu-gui.yml zostało przeładowane.");
                return true;
            });
        }
    }
}
