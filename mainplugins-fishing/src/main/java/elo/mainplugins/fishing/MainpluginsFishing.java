package elo.mainplugins.fishing;

import elo.mainplugins.fishing.config.FishingConfigLoader;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsFishing extends JavaPlugin {

    private FishingManager fishingManager;

    @Override
    public void onEnable() {
        fishingManager = new FishingManager(this, FishingConfigLoader.load(this));
        getServer().getPluginManager().registerEvents(fishingManager, this);

        if (getCommand("wedka") != null) {
            getCommand("wedka").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                player.getInventory().addItem(fishingManager.stworzWedke());
                player.sendMessage("§aOtrzymałeś wędkę.");
                return true;
            });
        }

        if (getCommand("@reloadfishing") != null) {
            getCommand("@reloadfishing").setExecutor((sender, command, label, args) -> {
                fishingManager.aktualizujKonfiguracje(FishingConfigLoader.load(this));
                sender.sendMessage("§aKonfiguracja lowienia (fishing-config.yml + ryby.yml) zostala przeladowana.");
                return true;
            });
        }

        // Wędki testowe /wedka1../wedka3 - patrz FishingManager.stworzWedkeTestowa. Za
        // permisją mainplugins.fishing.admin (patrz plugin.yml) - NIE dla zwykłych graczy,
        // bo wymuszają gatunek i prawie natychmiastowe branie.
        for (int i = 1; i <= 3; i++) {
            int indeks = i;
            if (getCommand("wedka" + i) != null) {
                getCommand("wedka" + i).setExecutor((sender, command, label, args) -> {
                    if (!(sender instanceof Player player)) {
                        sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                        return true;
                    }
                    player.getInventory().addItem(fishingManager.stworzWedkeTestowa(indeks));
                    player.sendMessage("§aOtrzymałeś wędkę testową #" + indeks + ".");
                    return true;
                });
            }
        }
    }
}
