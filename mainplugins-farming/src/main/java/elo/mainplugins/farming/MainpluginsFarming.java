package elo.mainplugins.farming;

import elo.mainplugins.farming.config.FarmingConfigLoader;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsFarming extends JavaPlugin {

    private FarmingManager farmingManager;

    @Override
    public void onEnable() {
        farmingManager = new FarmingManager(this, FarmingConfigLoader.load(this));
        getServer().getPluginManager().registerEvents(farmingManager, this);

        if (getCommand("@reloadfarming") != null) {
            getCommand("@reloadfarming").setExecutor((sender, command, label, args) -> {
                farmingManager.aktualizujKonfiguracje(FarmingConfigLoader.load(this));
                sender.sendMessage("§aFarming-config.yml zostało przeładowane.");
                return true;
            });
        }
    }
}
