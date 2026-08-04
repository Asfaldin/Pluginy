package elo.mainplugins.teleport;

import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsTeleport extends JavaPlugin {

    @Override
    public void onEnable() {
        TeleportManager teleportManager = new TeleportManager();
        SpawnManager spawnManager = new SpawnManager(this);
        TeleportCommands executor = new TeleportCommands(teleportManager, spawnManager);

        if (getCommand("tp") != null) getCommand("tp").setExecutor(executor);
        if (getCommand("tpaccept") != null) getCommand("tpaccept").setExecutor(executor);
        if (getCommand("tpdeny") != null) getCommand("tpdeny").setExecutor(executor);
        if (getCommand("spawn") != null) getCommand("spawn").setExecutor(executor);
        if (getCommand("spawnset") != null) getCommand("spawnset").setExecutor(executor);
    }
}