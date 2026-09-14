package elo.mainplugins.market;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.MenuBridge;
import elo.mainplugins.core.util.TabCompleteUtils;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class MainpluginsMarket extends JavaPlugin {

    private MarketManager marketManager;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("market")) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-market - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: market: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        EconomyService economyService = CoreAPI.getEconomyService();
        marketManager = new MarketManager(this, economyService);
        getServer().getPluginManager().registerEvents(marketManager, this);

        if (getCommand("targ") != null) {
            getCommand("targ").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                if (args.length > 0 && args[0].equalsIgnoreCase("wystaw")) {
                    marketManager.wystawPrzedmiot(player, args);
                } else {
                    marketManager.otworzTarg(player, 0, MenuBridge.isZMenu(args));
                }
                return true;
            });
            getCommand("targ").setTabCompleter((sender, command, alias, args) ->
                    args.length == 1 ? TabCompleteUtils.dopasuj(args[0], List.of("wystaw")) : TabCompleteUtils.PUSTA);
        }
    }

    @Override
    public void onDisable() {
        if (marketManager != null) marketManager.zamknij();
        getServer().getServicesManager().unregisterAll(this);
    }
}