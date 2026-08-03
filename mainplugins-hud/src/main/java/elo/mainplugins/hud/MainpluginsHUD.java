package elo.mainplugins.hud;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsHUD extends JavaPlugin {

    private TablistManager tablistManager;
    private ScoreboardManager scoreboardManager;

    @Override
    public void onEnable() {
        EconomyService economyService = CoreAPI.getEconomyService();

        tablistManager = new TablistManager(this, economyService);
        scoreboardManager = new ScoreboardManager(this, economyService);

        getServer().getPluginManager().registerEvents(tablistManager, this);
        getServer().getPluginManager().registerEvents(scoreboardManager, this);
    }

    @Override
    public void onDisable() {
        if (tablistManager != null) tablistManager.wyczyscZadania();
        if (scoreboardManager != null) scoreboardManager.wyczysc();
    }
}