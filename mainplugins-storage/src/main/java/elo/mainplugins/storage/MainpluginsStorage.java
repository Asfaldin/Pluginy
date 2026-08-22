package elo.mainplugins.storage;

import elo.mainplugins.core.util.MenuBridge;
import elo.mainplugins.core.util.TabCompleteUtils;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsStorage extends JavaPlugin {

    @Override
    public void onEnable() {
        StorageManager storageManager = new StorageManager(this);
        getServer().getPluginManager().registerEvents(storageManager, this);

        if (getCommand("itemy") != null) {
            getCommand("itemy").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                // Naprawiony bug z oryginału: /itemy wcześniej nie było w ogóle
                // podpięte do żadnej logiki (case w switchu był zakomentowany).
                storageManager.otworzSchowek(player, MenuBridge.isZMenu(args));
                return true;
            });
            getCommand("itemy").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
    }
}