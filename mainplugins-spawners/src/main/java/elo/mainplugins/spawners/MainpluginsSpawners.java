package elo.mainplugins.spawners;

import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsSpawners extends JavaPlugin {

    private SpawnerManager spawnerManager;

    @Override
    public void onEnable() {
        spawnerManager = new SpawnerManager(this);
        getServer().getPluginManager().registerEvents(spawnerManager, this);
    }

    @Override
    public void onDisable() {
        if (spawnerManager != null) spawnerManager.zapiszWszystkie();
    }
}