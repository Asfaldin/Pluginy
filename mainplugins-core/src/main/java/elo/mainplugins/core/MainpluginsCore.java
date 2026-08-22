package elo.mainplugins.core;

import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.command.AdminHelpCommand;
import elo.mainplugins.core.command.AdminPomocCommand;
import elo.mainplugins.core.command.DajCustomCommand;
import elo.mainplugins.core.command.DiscordCommand;
import elo.mainplugins.core.command.MoneyAddCommand;
import elo.mainplugins.core.command.MoneyUndoCommand;
import elo.mainplugins.core.command.PayCommand;
import elo.mainplugins.core.command.PomocCommand;
import elo.mainplugins.core.command.PortfelCommand;
import elo.mainplugins.core.customitem.CustomItemManager;
import elo.mainplugins.core.economy.EconomyManager;
import elo.mainplugins.core.util.TabCompleteUtils;
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

        CustomItemManager customItemManager = new CustomItemManager(this);
        getServer().getServicesManager().register(CustomItemService.class, customItemManager, this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(new ResourcePackManager(this), this);

        if (getCommand("wszystkiekomendy") != null) {
            getCommand("wszystkiekomendy").setExecutor(new AdminHelpCommand());
            getCommand("wszystkiekomendy").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        PomocCommand pomocCommand = new PomocCommand();
        if (getCommand("komendy") != null) {
            getCommand("komendy").setExecutor(pomocCommand);
            getCommand("komendy").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("komendy2") != null) {
            getCommand("komendy2").setExecutor(pomocCommand);
            getCommand("komendy2").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("komendy3") != null) {
            getCommand("komendy3").setExecutor(pomocCommand);
            getCommand("komendy3").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        AdminPomocCommand adminPomocCommand = new AdminPomocCommand();
        if (getCommand("@komendy") != null) {
            getCommand("@komendy").setExecutor(adminPomocCommand);
            getCommand("@komendy").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@komendy2") != null) {
            getCommand("@komendy2").setExecutor(adminPomocCommand);
            getCommand("@komendy2").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@komendy3") != null) {
            getCommand("@komendy3").setExecutor(adminPomocCommand);
            getCommand("@komendy3").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@moneyadd") != null) {
            MoneyAddCommand moneyAddCommand = new MoneyAddCommand(economyManager);
            getCommand("@moneyadd").setExecutor(moneyAddCommand);
            getCommand("@moneyadd").setTabCompleter(moneyAddCommand);
        }
        if (getCommand("@moneyundo") != null) {
            MoneyUndoCommand moneyUndoCommand = new MoneyUndoCommand(economyManager);
            getCommand("@moneyundo").setExecutor(moneyUndoCommand);
            getCommand("@moneyundo").setTabCompleter(moneyUndoCommand);
        }
        if (getCommand("przelej") != null) {
            PayCommand payCommand = new PayCommand(economyManager);
            getCommand("przelej").setExecutor(payCommand);
            getCommand("przelej").setTabCompleter(payCommand);
        }
        if (getCommand("portfel") != null) {
            getCommand("portfel").setExecutor(new PortfelCommand(economyManager));
            getCommand("portfel").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("discord") != null) {
            getCommand("discord").setExecutor(new DiscordCommand());
            getCommand("discord").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@dajcustom") != null) {
            DajCustomCommand dajCustomCommand = new DajCustomCommand(customItemManager);
            getCommand("@dajcustom").setExecutor(dajCustomCommand);
            getCommand("@dajcustom").setTabCompleter(dajCustomCommand);
        }
        // Osobny executor: /@reloadcustomitems ma sens też z konsoli, nie tylko od gracza
        // (ten sam wzorzec co /@reloadsklep w mainplugins-shop).
        if (getCommand("@reloadcustomitems") != null) {
            getCommand("@reloadcustomitems").setExecutor((sender, command, label, args) -> {
                customItemManager.reload();
                sender.sendMessage("§aCustom-items.yml został przeładowany.");
                return true;
            });
            getCommand("@reloadcustomitems").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
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