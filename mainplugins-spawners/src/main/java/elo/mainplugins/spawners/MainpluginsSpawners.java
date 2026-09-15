package elo.mainplugins.spawners;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomItemProvider;
import elo.mainplugins.spawners.config.SpawnerConfigLoader;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;

public final class MainpluginsSpawners extends JavaPlugin {

    private SpawnerManager spawnerManager;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("spawners")) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-spawners - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: spawners: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        spawnerManager = new SpawnerManager(this, SpawnerConfigLoader.load(this));
        getServer().getPluginManager().registerEvents(spawnerManager, this);

        // Spawnery w katalogu itemów core: "custom: spawner_zombie" działa w Sklepie, nagrodach itd.
        CoreAPI.getCustomItemService().registerProvider(this, new CustomItemProvider() {
            @Override
            public Set<String> ids() {
                return spawnerManager.catalogIds();
            }

            @Override
            public ItemStack create(String id, int amount, Player player) {
                return spawnerManager.createItem(id, amount);
            }
        });

        if (getCommand("@reloadspawnery") != null) {
            getCommand("@reloadspawnery").setExecutor((sender, command, label, args) -> {
                spawnerManager.aktualizujKonfiguracje(SpawnerConfigLoader.load(this));
                sender.sendMessage("§aSpawnery-typy.yml zostało przeładowane.");
                return true;
            });
        }
    }

    @Override
    public void onDisable() {
        if (spawnerManager != null) spawnerManager.zamknij();
    }
}
