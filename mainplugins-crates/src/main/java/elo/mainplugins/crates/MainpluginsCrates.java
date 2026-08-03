package elo.mainplugins.crates;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsCrates extends JavaPlugin {

    private CrateManager crateManager;

    @Override
    public void onEnable() {
        crateManager = new CrateManager(this);
        getServer().getPluginManager().registerEvents(crateManager, this);

        if (getCommand("dajklucz") != null) {
            getCommand("dajklucz").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                player.getInventory().addItem(crateManager.stworzKlucz());
                player.sendMessage("§aOtrzymałeś klucz do skrzynki.");
                return true;
            });
        }

        if (getCommand("dajskrzynia") != null) {
            getCommand("dajskrzynia").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                player.getInventory().addItem(crateManager.stworzSkrzynke());
                player.sendMessage("§aOtrzymałeś Tajemniczą Skrzynkę.");
                return true;
            });
        }

        if (getCommand("reloadcrates") != null) {
            getCommand("reloadcrates").setExecutor((sender, command, label, args) -> {
                crateManager.przeladujNagrody();
                sender.sendMessage("§aCrate-rewards.yml zostały przeładowane.");
                return true;
            });
        }
    }
}