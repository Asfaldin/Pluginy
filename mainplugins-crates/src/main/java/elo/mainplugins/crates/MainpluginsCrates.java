package elo.mainplugins.crates;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.Reward;
import elo.mainplugins.core.api.RewardService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class MainpluginsCrates extends JavaPlugin {

    private CrateManager crateManager;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("crates")) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-crates - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: crates: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        RewardService rewards = CoreAPI.getRewardService();

        crateManager = new CrateManager(this, lang, rewards, CoreAPI.getCustomItemService());
        getServer().getPluginManager().registerEvents(crateManager, this);
        getServer().getPluginManager().registerEvents(crateManager.placed(), this);
        getServer().getServicesManager().register(CrateService.class, crateManager, this, ServicePriority.Normal);

        // Nagrody "crate: id" i "key: id" działają teraz w nagrodach WSZYSTKICH pluginów.
        rewards.registerType(this, "crate", (player, r) -> daj(player, r, true));
        rewards.registerType(this, "key", (player, r) -> daj(player, r, false));

        if (getCommand("@crate") != null) {
            CrateCommand cmd = new CrateCommand(this, crateManager, lang);
            getCommand("@crate").setExecutor(cmd);
            getCommand("@crate").setTabCompleter(cmd);
        }
    }

    /** false = nieznane id -> RewardService użyje nagrody zastępczej (fallback). */
    private boolean daj(Player player, Reward r, boolean crate) {
        String id = String.valueOf(r.value());
        ItemStack item = crate ? crateManager.createCrate(id, r.amount()) : crateManager.createKey(id, r.amount());
        if (item == null) return false;
        player.getInventory().addItem(item).values().forEach(l -> player.getWorld().dropItemNaturally(player.getLocation(), l));
        if (!r.silent()) {
            String nazwa = crate ? crateManager.config().crates().get(id).name() : crateManager.config().keys().get(id).name();
            CoreAPI.getLangService().send(player, this, crate ? "reward.crate" : "reward.key",
                    Map.of("amount", String.valueOf(r.amount()), crate ? "crate" : "key", nazwa));
        }
        return true;
    }

    @Override
    public void onDisable() {
        if (crateManager != null) {
            crateManager.wyplacOczekujace();
            crateManager.placed().removeHolograms();
        }
        getServer().getServicesManager().unregisterAll(this);
    }
}
