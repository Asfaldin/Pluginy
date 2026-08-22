package elo.mainplugins.announcer;

import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsAnnouncer extends JavaPlugin {

    private AnnouncerManager announcerManager;

    @Override
    public void onEnable() {
        announcerManager = new AnnouncerManager(this);

        if (getCommand("@reloadannouncer") != null) {
            getCommand("@reloadannouncer").setExecutor((sender, command, label, args) -> {
                announcerManager.przeladuj();
                sender.sendMessage("§aOgloszenia.yml zostały przeładowane.");
                return true;
            });
            getCommand("@reloadannouncer").setTabCompleter((sender, command, alias, args) -> java.util.List.of());
        }
    }

    @Override
    public void onDisable() {
        if (announcerManager != null) announcerManager.zatrzymaj();
    }
}