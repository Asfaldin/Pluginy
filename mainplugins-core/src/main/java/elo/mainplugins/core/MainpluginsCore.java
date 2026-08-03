package elo.mainplugins.core;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.command.AdminHelpCommand;
import elo.mainplugins.core.command.MoneyAddCommand;
import elo.mainplugins.core.command.MoneyUndoCommand;
import elo.mainplugins.core.command.PomocCommand;
import elo.mainplugins.core.economy.EconomyManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Rdzeń całego ekosystemu Mainplugins. Nie zawiera żadnej logiki gry - tylko
 * usługi współdzielone (na razie: ekonomia) i narzędzia, z których korzystają
 * pozostałe, w pełni niezależne pluginy. Musi być włączony jako pierwszy
 * (każdy zależny plugin ma go w plugin.yml jako "depend").
 */
public final class MainpluginsCore extends JavaPlugin {

    private EconomyManager economyManager;

    @Override
    public void onEnable() {
        getLogger().info("Uruchamianie MainpluginsCore...");

        economyManager = new EconomyManager(this);
        getServer().getServicesManager().register(EconomyService.class, economyManager, this, ServicePriority.Normal);

        if (getCommand("adminhelp") != null) {
            getCommand("adminhelp").setExecutor(new AdminHelpCommand());
        }
        if (getCommand("komendy") != null) {
            getCommand("komendy").setExecutor(new PomocCommand());
        }
        if (getCommand("moneyadd") != null) {
            getCommand("moneyadd").setExecutor(new MoneyAddCommand(economyManager));
        }
        if (getCommand("moneyundo") != null) {
            getCommand("moneyundo").setExecutor(new MoneyUndoCommand(economyManager));
        }

        getLogger().info("MainpluginsCore włączony - EconomyService dostępny dla innych pluginów.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        getLogger().info("Wyłączanie MainpluginsCore...");
    }
}