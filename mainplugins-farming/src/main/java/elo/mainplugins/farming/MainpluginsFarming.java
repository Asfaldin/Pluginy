package elo.mainplugins.farming;

import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsFarming extends JavaPlugin {

    @Override
    public void onEnable() {
        FarmingManager farmingManager = new FarmingManager(this);
        getServer().getPluginManager().registerEvents(farmingManager, this);
    }
}