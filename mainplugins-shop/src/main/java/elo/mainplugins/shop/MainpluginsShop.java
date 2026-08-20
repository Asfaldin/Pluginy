package elo.mainplugins.shop;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.MenuBridge;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class MainpluginsShop extends JavaPlugin {

    private ShopManager shopManager;

    @Override
    public void onEnable() {
        EconomyService economyService = CoreAPI.getEconomyService();
        shopManager = new ShopManager(this, economyService);
        getServer().getPluginManager().registerEvents(shopManager, this);

        CommandExecutor executor = new CommandExecutor() {
            @Override
            public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                switch (command.getName().toLowerCase()) {
                    case "sklep" -> shopManager.otworzSklep(player, MenuBridge.isZMenu(args));
                    case "sprzedaj" -> shopManager.handleSellCommand(player);
                    case "sprzedajwszystko" -> shopManager.handleSellAllCommand(player);
                }
                return true;
            }
        };

        if (getCommand("sklep") != null) getCommand("sklep").setExecutor(executor);
        if (getCommand("sprzedaj") != null) getCommand("sprzedaj").setExecutor(executor);
        if (getCommand("sprzedajwszystko") != null) getCommand("sprzedajwszystko").setExecutor(executor);
        if (getCommand("@statsklep") != null) getCommand("@statsklep").setExecutor(new StatSklepCommand(shopManager));

        // Osobny executor: /@reloadsklep ma sens też z konsoli, nie tylko od gracza.
        // Uprawnienie (mainplugins.shop.reload, domyślnie op) pilnuje tego plugin.yml.
        if (getCommand("@reloadsklep") != null) {
            getCommand("@reloadsklep").setExecutor((sender, command, label, args) -> {
                shopManager.przeladujKonfiguracje();
                sender.sendMessage("§aSklep.yml został przeładowany.");
                return true;
            });
        }

        if (getCommand("@sklep") != null) {
            SklepAdminCommand adminCmd = new SklepAdminCommand(shopManager);
            getCommand("@sklep").setExecutor(adminCmd);
            getCommand("@sklep").setTabCompleter(adminCmd);
        }
    }

    @Override
    public void onDisable() {
        if (shopManager != null && shopManager.getCeny() != null) {
            shopManager.getCeny().zamknij();
        }
    }
}