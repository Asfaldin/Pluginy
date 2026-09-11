package elo.mainplugins.announcer;

import elo.mainplugins.announcer.command.AnnounceCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class MainpluginsAnnouncer extends JavaPlugin {

    private AnnouncerManager announcerManager;

    @Override
    public void onEnable() {
        announcerManager = new AnnouncerManager(this);

        PluginCommand reload = getCommand("@reloadannouncer");
        if (reload != null) {
            reload.setExecutor((sender, command, label, args) -> {
                announcerManager.przeladuj();
                sender.sendMessage("§aOgloszenia.yml zostały przeładowane.");
                return true;
            });
            reload.setTabCompleter((sender, command, alias, args) -> List.of());
        }

        PluginCommand announce = getCommand("announce");
        if (announce != null) {
            AnnounceCommand exec = new AnnounceCommand(
                    announcerManager.dispatcher(),
                    announcerManager::fireGroupNow,
                    announcerManager::groupNames);
            announce.setExecutor(exec);
            announce.setTabCompleter(exec);
        }

        PluginCommand claim = getCommand("odbierzogloszenie");
        if (claim != null) {
            claim.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§cTylko gracz może odebrać nagrodę.");
                    return true;
                }
                if (args.length < 1) return true;
                announcerManager.claims().handleClaim(p, args[0]);
                return true;
            });
            claim.setTabCompleter((sender, command, alias, args) -> List.of());
        }
    }

    @Override
    public void onDisable() {
        if (announcerManager != null) announcerManager.zatrzymaj();
    }
}
