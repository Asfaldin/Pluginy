package elo.mainplugins.crates;

import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.util.TabCompleteUtils;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsCrates extends JavaPlugin {

    private CrateManager crateManager;

    @Override
    public void onEnable() {
        crateManager = new CrateManager(this);
        getServer().getPluginManager().registerEvents(crateManager, this);
        getServer().getServicesManager().register(CrateService.class, crateManager, this, ServicePriority.Normal);

        // Żadna komenda w tym pluginie nie bierze argumentów - jeden wspólny pusty
        // completer, żeby Bukkit nie podpowiadał domyślnie listy graczy online.
        TabCompleter pustyCompleter = (sender, command, alias, args) -> TabCompleteUtils.PUSTA;

        if (getCommand("@dajklucz") != null) {
            getCommand("@dajklucz").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                player.getInventory().addItem(crateManager.stworzKlucz());
                player.sendMessage("§aOtrzymałeś klucz do skrzynki.");
                return true;
            });
            getCommand("@dajklucz").setTabCompleter(pustyCompleter);
        }

        if (getCommand("@dajskrzynia") != null) {
            getCommand("@dajskrzynia").setExecutor((sender, command, label, args) -> dajSkrzynke(sender, 1));
            getCommand("@dajskrzynia").setTabCompleter(pustyCompleter);
        }
        if (getCommand("@dajskrzynie1") != null) {
            getCommand("@dajskrzynie1").setExecutor((sender, command, label, args) -> dajSkrzynke(sender, 1));
            getCommand("@dajskrzynie1").setTabCompleter(pustyCompleter);
        }
        if (getCommand("@dajskrzynie2") != null) {
            getCommand("@dajskrzynie2").setExecutor((sender, command, label, args) -> dajSkrzynke(sender, 2));
            getCommand("@dajskrzynie2").setTabCompleter(pustyCompleter);
        }
        if (getCommand("@dajskrzynie3") != null) {
            getCommand("@dajskrzynie3").setExecutor((sender, command, label, args) -> dajSkrzynke(sender, 3));
            getCommand("@dajskrzynie3").setTabCompleter(pustyCompleter);
        }

        if (getCommand("@reloadcrates") != null) {
            getCommand("@reloadcrates").setExecutor((sender, command, label, args) -> {
                crateManager.przeladujNagrody();
                sender.sendMessage("§aPule nagród wszystkich tierów zostały przeładowane.");
                return true;
            });
            getCommand("@reloadcrates").setTabCompleter(pustyCompleter);
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    private boolean dajSkrzynke(org.bukkit.command.CommandSender sender, int tier) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
            return true;
        }
        player.getInventory().addItem(crateManager.stworzSkrzynke(tier));
        player.sendMessage("§aOtrzymałeś skrzynkę (tier " + tier + ").");
        return true;
    }
}