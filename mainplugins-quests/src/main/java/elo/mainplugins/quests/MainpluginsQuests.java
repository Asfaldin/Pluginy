package elo.mainplugins.quests;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsQuests extends JavaPlugin {

    @Override
    public void onEnable() {
        QuestManager questManager = new QuestManager();
        getServer().getPluginManager().registerEvents(questManager, this);

        if (getCommand("quest") != null) {
            getCommand("quest").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                // Naprawiony bug z oryginału: /quest wcześniej nie było w ogóle
                // podpięte do żadnej logiki (case w switchu był zakomentowany).
                boolean zMenu = args.length > 0 && args[args.length - 1].equalsIgnoreCase("zmenu");
                questManager.otworzMenuQuestow(player, zMenu);
                return true;
            });
        }
    }
}