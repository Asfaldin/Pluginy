package elo.mainplugins.dungeons;

import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.dungeons.config.DungeonConfigLoader;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsDungeons extends JavaPlugin {

    private DungeonManager dungeonManager;

    @Override
    public void onEnable() {
        dungeonManager = new DungeonManager(this, DungeonConfigLoader.load(this));
        getServer().getPluginManager().registerEvents(dungeonManager, this);

        if (getCommand("tpboss") != null) {
            getCommand("tpboss").setExecutor(dungeonManager::onTpBoss);
            getCommand("tpboss").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("tpdun") != null) {
            getCommand("tpdun").setExecutor(dungeonManager::onTpDun);
            getCommand("tpdun").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }

        if (getCommand("@reloaddungeons") != null) {
            getCommand("@reloaddungeons").setExecutor((sender, command, label, args) -> {
                dungeonManager.aktualizujKonfiguracje(DungeonConfigLoader.load(this));
                sender.sendMessage("§aDungeons-config.yml zostało przeładowane.");
                return true;
            });
        }
    }

    @Override
    public void onDisable() {
        if (dungeonManager != null) {
            dungeonManager.wyczyscWszystko();
        }
    }
}
