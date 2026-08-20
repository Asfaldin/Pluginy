package elo.mainplugins.teleport;

import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsTeleport extends JavaPlugin {

    @Override
    public void onEnable() {
        TeleportManager teleportManager = new TeleportManager();
        TeleportCommands executor = new TeleportCommands(teleportManager);

        if (getCommand("teleportuj") != null) getCommand("teleportuj").setExecutor(executor);
        if (getCommand("tpakceptuj") != null) getCommand("tpakceptuj").setExecutor(executor);
        if (getCommand("tpodrzuc") != null) getCommand("tpodrzuc").setExecutor(executor);
    }
}