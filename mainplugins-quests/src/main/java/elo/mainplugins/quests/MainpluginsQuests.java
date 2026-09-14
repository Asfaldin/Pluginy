package elo.mainplugins.quests;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.QuestService;
import elo.mainplugins.core.api.TytulService;
import elo.mainplugins.core.util.TabCompleteUtils;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsQuests extends JavaPlugin {

    private QuestManager questManager;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("quests")) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-quests - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: quests: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        questManager = new QuestManager(this);
        getServer().getPluginManager().registerEvents(questManager, this);
        getServer().getServicesManager().register(TytulService.class, questManager, this, ServicePriority.Normal);
        getServer().getServicesManager().register(QuestService.class, questManager, this, ServicePriority.Normal);

        if (getCommand("zadania") != null) {
            getCommand("zadania").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                // Naprawiony bug z oryginału: /zadania (dawniej /quest) wcześniej nie było w ogóle
                // podpięte do żadnej logiki (case w switchu był zakomentowany).
                boolean zMenu = args.length > 0 && args[args.length - 1].equalsIgnoreCase("zmenu");
                questManager.otworzMenuQuestow(player, zMenu);
                return true;
            });
            getCommand("zadania").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }

        if (getCommand("@reloadquesty") != null) {
            getCommand("@reloadquesty").setExecutor((sender, command, label, args) -> {
                questManager.przeladujTresc();
                sender.sendMessage("Przeladowano quests-content.yml.");
                return true;
            });
            getCommand("@reloadquesty").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
    }

    @Override
    public void onDisable() {
        if (questManager != null) questManager.zamknij();
        getServer().getServicesManager().unregisterAll(this);
    }
}