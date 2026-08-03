package elo.mainplugins.menu;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsMenu extends JavaPlugin {

    @Override
    public void onEnable() {
        MenuPomocyManager menuPomocyManager = new MenuPomocyManager();
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
        }
    }
}