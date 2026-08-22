package elo.mainplugins.spawn;

import elo.mainplugins.core.api.SpawnService;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsSpawn extends JavaPlugin {

    private SpawnManager spawnManager;
    private ObszarManager obszarManager;
    private WarpManager warpManager;

    @Override
    public void onEnable() {
        spawnManager = new SpawnManager(this);
        obszarManager = new ObszarManager(this);
        warpManager = new WarpManager(this);
        getServer().getPluginManager().registerEvents(spawnManager, this);
        getServer().getPluginManager().registerEvents(obszarManager, this);
        getServer().getPluginManager().registerEvents(new ObszarProtectionManager(obszarManager), this);
        getServer().getServicesManager().register(SpawnService.class, spawnManager, this, ServicePriority.Normal);

        SpawnCommands executor = new SpawnCommands(spawnManager, obszarManager, warpManager);
        if (getCommand("spawn") != null) {
            getCommand("spawn").setExecutor(executor);
            getCommand("spawn").setTabCompleter(executor);
        }
        if (getCommand("@setspawn") != null) {
            getCommand("@setspawn").setExecutor(executor);
            getCommand("@setspawn").setTabCompleter(executor);
        }
        if (getCommand("@obszar") != null) {
            getCommand("@obszar").setExecutor(executor);
            getCommand("@obszar").setTabCompleter(executor);
        }
        if (getCommand("warp") != null) {
            getCommand("warp").setExecutor(executor);
            getCommand("warp").setTabCompleter(executor);
        }
        if (getCommand("@setwarp") != null) {
            getCommand("@setwarp").setExecutor(executor);
            getCommand("@setwarp").setTabCompleter(executor);
        }
        if (getCommand("@delwarp") != null) {
            getCommand("@delwarp").setExecutor(executor);
            getCommand("@delwarp").setTabCompleter(executor);
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
