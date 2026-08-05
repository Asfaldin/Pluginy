package elo.mainplugins.tools;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.ToolsService;
import elo.mainplugins.tools.pickaxe.PickaxeSkillManager;
import elo.mainplugins.tools.special.NiszczycielManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsTools extends JavaPlugin {

    @Override
    public void onEnable() {
        LevelableToolsManager levelableToolsManager = new LevelableToolsManager(this);
        getServer().getPluginManager().registerEvents(levelableToolsManager, this);

        // Opcjonalny serwis dla innych pluginów (np. mainplugins-quests, nagroda za pierwszego questa).
        getServer().getServicesManager().register(ToolsService.class, levelableToolsManager, this, ServicePriority.Normal);

        PickaxeSkillManager pickaxeSkillManager = new PickaxeSkillManager(this, CoreAPI.getEconomyService());
        getServer().getPluginManager().registerEvents(pickaxeSkillManager, this);

        NiszczycielManager niszczycielManager = new NiszczycielManager(this);
        getServer().getPluginManager().registerEvents(niszczycielManager, this);

        if (getCommand("narzedzia") != null) {
            getCommand("narzedzia").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                levelableToolsManager.dajStartoweNarzedzia(player);
                return true;
            });
        }

        if (getCommand("addlvl") != null) {
            getCommand("addlvl").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }

                int amount = 1;
                if (args.length > 0) {
                    try {
                        amount = Integer.parseInt(args[0]);
                    } catch (NumberFormatException e) {
                        player.sendMessage("§cPodaj liczbę, np. /addlvl 5");
                        return true;
                    }
                }
                amount = Math.max(1, Math.min(amount, 200));

                ItemStack item = player.getInventory().getItemInMainHand();
                String type = levelableToolsManager.getToolType(item);
                if (type == null) {
                    player.sendMessage("§cMusisz trzymać swoje przypisane narzędzie!");
                    return true;
                }

                if (type.equals("pickaxe")) {
                    pickaxeSkillManager.debugAddLevels(player, item, amount);
                } else {
                    levelableToolsManager.debugAddLevels(player, item, amount);
                }
                player.sendMessage("§a[DEBUG] Dodano " + amount + " poziomów trzymanemu narzędziu.");
                return true;
            });
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}