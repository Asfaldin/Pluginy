package elo.mainplugins.spawn;

import elo.mainplugins.core.api.SpawnService;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsSpawn extends JavaPlugin {

    private SpawnManager spawnManager;
    private ObszarManager obszarManager;

    @Override
    public void onEnable() {
        spawnManager = new SpawnManager(this);
        obszarManager = new ObszarManager(this);
        getServer().getPluginManager().registerEvents(spawnManager, this);
        getServer().getPluginManager().registerEvents(obszarManager, this);
        getServer().getPluginManager().registerEvents(new ObszarProtectionManager(obszarManager), this);
        getServer().getServicesManager().register(SpawnService.class, spawnManager, this, ServicePriority.Normal);

        SpawnCommands executor = new SpawnCommands(spawnManager, obszarManager);
        if (getCommand("spawn") != null) getCommand("spawn").setExecutor(executor);
        if (getCommand("@setspawn") != null) {
            getCommand("@setspawn").setExecutor(executor);
            getCommand("@setspawn").setTabCompleter(executor);
        }
        if (getCommand("@obszar") != null) {
            getCommand("@obszar").setExecutor(executor);
            getCommand("@obszar").setTabCompleter(executor);
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
