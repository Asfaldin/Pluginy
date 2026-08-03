package elo.mainplugins.market;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.MenuBridge;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsMarket extends JavaPlugin {

    @Override
    public void onEnable() {
        EconomyService economyService = CoreAPI.getEconomyService();
        MarketManager marketManager = new MarketManager(this, economyService);
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
        }
    }
}