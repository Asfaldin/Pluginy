package elo.mainplugins.core;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.command.AdminHelpCommand;
import elo.mainplugins.core.command.AdminPomocCommand;
import elo.mainplugins.core.command.DiscordCommand;
import elo.mainplugins.core.command.MoneyAddCommand;
import elo.mainplugins.core.command.MoneyUndoCommand;
import elo.mainplugins.core.command.PayCommand;
import elo.mainplugins.core.command.PomocCommand;
import elo.mainplugins.core.command.PortfelCommand;
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

        getServer().getPluginManager().registerEvents(new ResourcePackManager(this), this);

        if (getCommand("wszystkiekomendy") != null) {
            getCommand("wszystkiekomendy").setExecutor(new AdminHelpCommand());
        }
        PomocCommand pomocCommand = new PomocCommand();
        if (getCommand("komendy") != null) {
            getCommand("komendy").setExecutor(pomocCommand);
        }
        if (getCommand("komendy2") != null) {
            getCommand("komendy2").setExecutor(pomocCommand);
        }
        if (getCommand("komendy3") != null) {
            getCommand("komendy3").setExecutor(pomocCommand);
        }
        AdminPomocCommand adminPomocCommand = new AdminPomocCommand();
        if (getCommand("@komendy") != null) {
            getCommand("@komendy").setExecutor(adminPomocCommand);
        }
        if (getCommand("@komendy2") != null) {
            getCommand("@komendy2").setExecutor(adminPomocCommand);
        }
        if (getCommand("@komendy3") != null) {
            getCommand("@komendy3").setExecutor(adminPomocCommand);
        }
        if (getCommand("@moneyadd") != null) {
            getCommand("@moneyadd").setExecutor(new MoneyAddCommand(economyManager));
        }
        if (getCommand("@moneyundo") != null) {
            getCommand("@moneyundo").setExecutor(new MoneyUndoCommand(economyManager));
        }
        if (getCommand("pay") != null) {
            getCommand("pay").setExecutor(new PayCommand(economyManager));
        }
        if (getCommand("portfel") != null) {
            getCommand("portfel").setExecutor(new PortfelCommand(economyManager));
        }
        if (getCommand("discord") != null) {
            getCommand("discord").setExecutor(new DiscordCommand());
        }

        getLogger().info("MainpluginsCore włączony - EconomyService dostępny dla innych pluginów.");
    }

    @Override
    public void onDisable() {
        if (economyManager != null) economyManager.zamknij();   // <-- NOWE
        getServer().getServicesManager().unregisterAll(this);
        getLogger().info("Wyłączanie MainpluginsCore...");
    }
}