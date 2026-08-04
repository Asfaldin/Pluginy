package elo.mainplugins.teleport;

import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsTeleport extends JavaPlugin {

    @Override
    public void onEnable() {
        TeleportManager teleportManager = new TeleportManager();
        TeleportCommands executor = new TeleportCommands(teleportManager);

        if (getCommand("tp") != null) getCommand("tp").setExecutor(executor);
        if (getCommand("tpaccept") != null) getCommand("tpaccept").setExecutor(executor);
        if (getCommand("tpdeny") != null) getCommand("tpdeny").setExecutor(executor);
    }
}