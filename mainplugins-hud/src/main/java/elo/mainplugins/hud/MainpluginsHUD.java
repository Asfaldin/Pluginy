package elo.mainplugins.hud;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.hud.config.HudConfig;
import elo.mainplugins.hud.config.HudConfigLoader;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsHUD extends JavaPlugin {

    private MainpluginsPlaceholders placeholders;

    @Override
    public void onEnable() {
        EconomyService economyService = CoreAPI.getEconomyService();
        HudConfig config = HudConfigLoader.load(this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholders = new MainpluginsPlaceholders(economyService, config);
            placeholders.register();
        } else {
            getLogger().warning("PlaceholderAPI nie jest wgrany - placeholdery %mainplugins_...% "
                    + "(top gracze/wyspy, Twoja kasa/wyspa) nie beda dzialac. Zainstaluj "
                    + "PlaceholderAPI i plugin TAB, zeby dzialal Tab graczy.");
        }

        if (getCommand("@reloadhud") != null) {
            getCommand("@reloadhud").setExecutor((sender, command, label, args) -> {
                HudConfig nowy = HudConfigLoader.load(this);
                if (placeholders != null) {
                    placeholders.aktualizujKonfiguracje(nowy);
                    sender.sendMessage("§aHud-config.yml zostało przeładowane.");
                } else {
                    sender.sendMessage("§chud-config.yml wczytany, ale PlaceholderAPI nie jest wgrany - nic nie uzywa tej konfiguracji.");
                }
                return true;
            });
        }
    }
}
