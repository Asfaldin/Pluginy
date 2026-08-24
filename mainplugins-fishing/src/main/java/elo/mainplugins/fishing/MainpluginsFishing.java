package elo.mainplugins.fishing;

import elo.mainplugins.core.util.TabCompleteUtils;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class MainpluginsFishing extends JavaPlugin {

    private FishingManager fishingManager;

    @Override
    public void onEnable() {
        fishingManager = new FishingManager(this);
        getServer().getPluginManager().registerEvents(fishingManager, this);

        if (getCommand("@dajwedke") != null) {
            getCommand("@dajwedke").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                int tier = args.length > 0 ? parseTier(args[0]) : 1;
                player.getInventory().addItem(fishingManager.stworzWedke(tier));
                player.sendMessage("§aOtrzymałeś wędkę (tier " + tier + ").");
                return true;
            });
            getCommand("@dajwedke").setTabCompleter((sender, command, alias, args) ->
                    args.length == 1 ? TabCompleteUtils.dopasuj(args[0], List.of("1", "2", "3")) : TabCompleteUtils.PUSTA);
        }

        if (getCommand("@dajrecepture") != null) {
            getCommand("@dajrecepture").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                int docelowyTier = args.length > 0 && args[0].equalsIgnoreCase("kosmiczna") ? 3 : 2;
                player.getInventory().addItem(fishingManager.stworzRecepture(docelowyTier));
                player.sendMessage("§aOtrzymałeś recepturę.");
                return true;
            });
            getCommand("@dajrecepture").setTabCompleter((sender, command, alias, args) ->
                    args.length == 1 ? TabCompleteUtils.dopasuj(args[0], List.of("niebianska", "kosmiczna")) : TabCompleteUtils.PUSTA);
        }
    }

    private int parseTier(String arg) {
        try {
            int tier = Integer.parseInt(arg);
            return (tier >= 1 && tier <= 3) ? tier : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}