package elo.mainplugins.fishing;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsFishing extends JavaPlugin {

    private FishingManager fishingManager;

    @Override
    public void onEnable() {
        fishingManager = new FishingManager(this);
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
    }
}
