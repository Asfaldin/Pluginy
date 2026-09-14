package elo.mainplugins.quests;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.RewardService;
import elo.mainplugins.core.api.TytulService;
import elo.mainplugins.core.util.TabCompleteUtils;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** Questy - działają z samym core; nie wołają żadnego innego pluginu. */
public final class MainpluginsQuests extends JavaPlugin {

    private QuestManager quests;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("quests")) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-quests - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: quests: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        RewardService rewards = CoreAPI.getRewardService();

        quests = new QuestManager(this, lang, rewards, CoreAPI.getEconomyService(), CoreAPI.getCustomItemService());
        getServer().getPluginManager().registerEvents(quests, this);
        getServer().getServicesManager().register(TytulService.class, quests, this, ServicePriority.Normal);

        // Nagroda "title: id" działa w nagrodach WSZYSTKICH pluginów (tytuły są zdefiniowane w quests.yml).
        rewards.registerType(this, "title", (player, r) -> quests.giveTitle(player, String.valueOf(r.value()), r.silent()));

        if (getCommand("zadania") != null) {
            getCommand("zadania").setExecutor((sender, command, label, args) -> {
                if (sender instanceof Player player) quests.openMain(player);
                else lang.send(sender, this, "admin.players-only");
                return true;
            });
            getCommand("zadania").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@quests") != null) {
            QuestCommand cmd = new QuestCommand(this, quests, lang);
            getCommand("@quests").setExecutor(cmd);
            getCommand("@quests").setTabCompleter(cmd);
        }
    }

    @Override
    public void onDisable() {
        if (quests != null) quests.close();
        getServer().getServicesManager().unregisterAll(this);
    }
}
