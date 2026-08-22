package elo.mainplugins.dungeons;

import elo.mainplugins.core.util.TabCompleteUtils;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsDungeons extends JavaPlugin {

    private DungeonManager dungeonManager;

    @Override
    public void onEnable() {
        dungeonManager = new DungeonManager(this);
        getServer().getPluginManager().registerEvents(dungeonManager, this);

        if (getCommand("tpboss") != null) {
            getCommand("tpboss").setExecutor(dungeonManager::onTpBoss);
            getCommand("tpboss").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("tpdun") != null) {
            getCommand("tpdun").setExecutor(dungeonManager::onTpDun);
            getCommand("tpdun").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
    }

    @Override
    public void onDisable() {
        if (dungeonManager != null) {
            dungeonManager.wyczyscWszystko();
        }
    }
}
