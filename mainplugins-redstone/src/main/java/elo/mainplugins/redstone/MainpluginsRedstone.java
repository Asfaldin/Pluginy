package elo.mainplugins.redstone;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.redstone.block.DeviceListeners;
import elo.mainplugins.redstone.block.DeviceStore;
import elo.mainplugins.redstone.chestlink.ChestLinkerListener;
import elo.mainplugins.redstone.chestlink.GolemManager;
import elo.mainplugins.redstone.command.DajAllCommand;
import elo.mainplugins.redstone.command.DajRedstoneCommand;
import elo.mainplugins.redstone.item.RedstoneItemManager;
import elo.mainplugins.redstone.network.StationManager;
import elo.mainplugins.redstone.planter.PlanterManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Redstone-narzędzia do farmy. Od przebudowy urządzenia (Sadzarka / Stacja Drona /
 * Stacja Zbierania / Stacja Zbiorcza) i Kabel Przesyłowy to PRAWDZIWE postawione bloki
 * (pełna kolizja, łamanie jak blok), a nie encje - ich tożsamość i stan trzyma
 * {@link DeviceStore} w redstone-urzadzenia.yml. Logika dronów/golema bez zmian.
 */
public final class MainpluginsRedstone extends JavaPlugin {

    private DeviceStore store;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("redstone")) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-redstone - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: redstone: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        RedstoneItemManager items = new RedstoneItemManager(this);
        store = new DeviceStore(this);

        PlanterManager planterManager = new PlanterManager(this, store);
        StationManager stationManager = new StationManager(store, planterManager);
        GolemManager golemManager = new GolemManager(store);

        getServer().getPluginManager().registerEvents(planterManager, this);
        getServer().getPluginManager().registerEvents(new DeviceListeners(items, store, planterManager, stationManager, golemManager), this);
        getServer().getPluginManager().registerEvents(new ChestLinkerListener(items), this);

        if (getCommand("@dajredstone") != null) {
            getCommand("@dajredstone").setExecutor(new DajRedstoneCommand(items));
        }
        if (getCommand("@dajall") != null) {
            getCommand("@dajall").setExecutor(new DajAllCommand(items));
        }
        if (getCommand("@reloadredstone") != null) {
            getCommand("@reloadredstone").setExecutor((sender, command, label, args) -> {
                items.reload();
                sender.sendMessage("§aredstone-items.yml zostało przeładowane.");
                return true;
            });
        }

        // Co ~1.5s: każda Stacja Drona/Zbierania zasilona redstonem i podłączona kablem
        // przesuwa swojego drona o jeden kafelek siatki 5x5 - patrz StationManager.
        getServer().getScheduler().runTaskTimer(this, stationManager::tick, 20L, 30L);
        // Co ~1.5s: każda Stacja Zbiorcza obok połączonej skrzynki przesuwa golema - patrz GolemManager.
        getServer().getScheduler().runTaskTimer(this, golemManager::tick, 20L, 30L);
    }

    @Override
    public void onDisable() {
        if (store != null) store.zamknij();
    }
}
