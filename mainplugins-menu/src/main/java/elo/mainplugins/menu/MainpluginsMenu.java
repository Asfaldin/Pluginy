package elo.mainplugins.menu;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/** Główne menu serwera: /menu (gracz) i /@reloadmenu (admin, także z konsoli). */
public final class MainpluginsMenu extends JavaPlugin {

    @Override
    public void onEnable() {
        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        MenuManager menu = new MenuManager(this, lang);
        getServer().getPluginManager().registerEvents(menu, this);

        if (getCommand("menu") != null) {
            getCommand("menu").setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player player) menu.open(player);
                else lang.send(sender, this, "admin.players-only");
                return true;
            });
            getCommand("menu").setTabCompleter((sender, command, alias, args) -> List.of());
        }
        if (getCommand("@reloadmenu") != null) {
            getCommand("@reloadmenu").setExecutor((sender, command, label, args) -> {
                menu.reload();
                lang.send(sender, this, "admin.reloaded", Map.of("buttons", String.valueOf(menu.buttonCount())));
                return true;
            });
        }
    }
}
