package elo.mainplugins.spawners;

import elo.mainplugins.spawners.config.SpawnerConfigLoader;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsSpawners extends JavaPlugin {

    private SpawnerManager spawnerManager;

    @Override
    public void onEnable() {
        spawnerManager = new SpawnerManager(this, SpawnerConfigLoader.load(this));
        getServer().getPluginManager().registerEvents(spawnerManager, this);

        if (getCommand("@reloadspawnery") != null) {
            getCommand("@reloadspawnery").setExecutor((sender, command, label, args) -> {
                spawnerManager.aktualizujKonfiguracje(SpawnerConfigLoader.load(this));
                sender.sendMessage("§aSpawnery-typy.yml zostało przeładowane.");
                return true;
            });
        }
    }

    @Override
    public void onDisable() {
        if (spawnerManager != null) spawnerManager.zamknij();
    }
}
