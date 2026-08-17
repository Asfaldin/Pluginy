package elo.mainplugins.ranks;

import elo.mainplugins.core.api.RankService;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsRanks extends JavaPlugin {

    private RankManager rankManager;

    @Override
    public void onEnable() {
        rankManager = new RankManager(this);
        getServer().getPluginManager().registerEvents(rankManager, this);
        getServer().getServicesManager().register(RankService.class, rankManager, this, ServicePriority.Normal);

        RankCommands executor = new RankCommands(rankManager);
        if (getCommand("@setranga") != null) getCommand("@setranga").setExecutor(executor);
        if (getCommand("@setranga") != null) getCommand("@setranga").setTabCompleter(executor);
        if (getCommand("@ranga") != null) getCommand("@ranga").setExecutor(executor);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
