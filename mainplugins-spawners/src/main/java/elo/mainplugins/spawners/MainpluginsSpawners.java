package elo.mainplugins.spawners;

import elo.mainplugins.license.LicenseGuard;
import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomItemProvider;
import elo.mainplugins.core.api.LangService;
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
        if (!CoreAPI.getLicenseService().isLicensed("spawners")
                || !LicenseGuard.enable(this, "spawners", () -> CoreAPI.getLicenseService().licenseProof("spawners"))) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-spawners - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: spawners: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        spawnerManager = new SpawnerManager(this, SpawnerConfigLoader.load(this), lang);
        getServer().getPluginManager().registerEvents(spawnerManager, this);

        SpawnerUpgradeMenu menu = new SpawnerUpgradeMenu(this, spawnerManager, lang, CoreAPI.getEconomyService());
        spawnerManager.ustawMenu(menu);
        getServer().getPluginManager().registerEvents(menu, this);

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

        // Okno ulepszeń - też z przycisku "Spawnery" w panelu wyspy (Skyblock woła tę komendę).
        if (getCommand("spawnery") != null) {
            getCommand("spawnery").setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player player) menu.otworz(player, 0);
                return true;
            });
        }

        if (getCommand("@reloadspawnery") != null) {
            getCommand("@reloadspawnery").setExecutor((sender, command, label, args) -> {
                spawnerManager.aktualizujKonfiguracje(SpawnerConfigLoader.load(this));
                lang.send(sender, this, "reload.done");
                return true;
            });
        }
    }

    @Override
    public void onDisable() {
        if (spawnerManager != null) spawnerManager.zamknij();
    }
}
