package elo.mainplugins.shop;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.MenuBridge;
import elo.mainplugins.core.util.TabCompleteUtils;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class MainpluginsShop extends JavaPlugin {

    private ShopManager shopManager;
    private RotacjaManager rotacjaManager;

    @Override
    public void onEnable() {
        EconomyService economyService = CoreAPI.getEconomyService();
        shopManager = new ShopManager(this, economyService);
        getServer().getPluginManager().registerEvents(shopManager, this);
        rotacjaManager = new RotacjaManager(this, shopManager);

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

        // sklep/sprzedaj/sprzedajwszystko nie biorą argumentów - pusty completer,
        // żeby Bukkit nie podpowiadał domyślnie listy graczy online.
        if (getCommand("sklep") != null) {
            getCommand("sklep").setExecutor(executor);
            getCommand("sklep").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("sprzedaj") != null) {
            getCommand("sprzedaj").setExecutor(executor);
            getCommand("sprzedaj").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("sprzedajwszystko") != null) {
            getCommand("sprzedajwszystko").setExecutor(executor);
            getCommand("sprzedajwszystko").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@statsklep") != null) {
            getCommand("@statsklep").setExecutor(new StatSklepCommand(shopManager));
            getCommand("@statsklep").setTabCompleter((sender, command, alias, args) ->
                    args.length == 1 ? TabCompleteUtils.dopasuj(args[0], List.of("snapshot")) : TabCompleteUtils.PUSTA);
        }

        // Osobny executor: /@reloadsklep ma sens też z konsoli, nie tylko od gracza.
        // Uprawnienie (mainplugins.shop.reload, domyślnie op) pilnuje tego plugin.yml.
        if (getCommand("@reloadsklep") != null) {
            getCommand("@reloadsklep").setExecutor((sender, command, label, args) -> {
                shopManager.przeladujKonfiguracje();
                sender.sendMessage("§aSklep.yml został przeładowany.");
                return true;
            });
            getCommand("@reloadsklep").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }

        if (getCommand("@sklep") != null) {
            SklepAdminCommand adminCmd = new SklepAdminCommand(shopManager, rotacjaManager);
            getCommand("@sklep").setExecutor(adminCmd);
            getCommand("@sklep").setTabCompleter(adminCmd);
        }
    }

    public RotacjaManager getRotacjaManager() { return rotacjaManager; }

    @Override
    public void onDisable() {
        if (rotacjaManager != null) rotacjaManager.zamknij();
        if (shopManager != null && shopManager.getCeny() != null) {
            shopManager.getCeny().zamknij();
        }
    }
}