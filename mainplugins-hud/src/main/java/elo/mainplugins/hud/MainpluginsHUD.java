package elo.mainplugins.hud;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsHUD extends JavaPlugin {

    @Override
    public void onEnable() {
        EconomyService economyService = CoreAPI.getEconomyService();

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new MainpluginsPlaceholders(economyService).register();
        } else {
            getLogger().warning("PlaceholderAPI nie jest wgrany - placeholdery %mainplugins_...% "
                    + "(top gracze/wyspy, Twoja kasa/wyspa) nie beda dzialac. Zainstaluj "
                    + "PlaceholderAPI i plugin TAB, zeby dzialal Tab graczy.");
        }
    }
}
