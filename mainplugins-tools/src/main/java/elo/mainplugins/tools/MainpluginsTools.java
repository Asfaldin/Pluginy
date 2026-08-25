package elo.mainplugins.tools;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.ToolsService;
import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.tools.evolving.EvolvingToolManager;
import elo.mainplugins.tools.pickaxe.BrukSurowceManager;
import elo.mainplugins.tools.pickaxe.GemType;
import elo.mainplugins.tools.pickaxe.PickaxeSkillManager;
import elo.mainplugins.tools.pickaxe.PickaxeType;
import org.bukkit.Bukkit;
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

        BrukSurowceManager brukSurowceManager = new BrukSurowceManager(this);

        PickaxeSkillManager pickaxeSkillManager = new PickaxeSkillManager(this, CoreAPI.getEconomyService(), brukSurowceManager);
        getServer().getPluginManager().registerEvents(pickaxeSkillManager, this);
        levelableToolsManager.setPickaxeSkillManager(pickaxeSkillManager);

        EvolvingToolManager evolvingToolManager = new EvolvingToolManager(this, CoreAPI.getEconomyService());
        getServer().getPluginManager().registerEvents(evolvingToolManager, this);
        levelableToolsManager.setEvolvingToolManager(evolvingToolManager);

        if (getCommand("@addlvl") != null) {
            getCommand("@addlvl").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }

                int amount = 1;
                if (args.length > 0) {
                    try {
                        amount = Integer.parseInt(args[0]);
                    } catch (NumberFormatException e) {
                        player.sendMessage("§cPodaj liczbę, np. /@addlvl 5");
                        return true;
                    }
                }
                amount = Math.max(1, Math.min(amount, 200));

                ItemStack item = player.getInventory().getItemInMainHand();
                String type = levelableToolsManager.getToolType(item);
                if ("pickaxe".equals(type)) {
                    pickaxeSkillManager.debugAddLevels(player, item, amount);
                } else if (evolvingToolManager.jestNarzedziem(item)) {
                    evolvingToolManager.debugAddLevels(item, amount);
                } else {
                    player.sendMessage("§cMusisz trzymać swoje przypisane narzędzie!");
                    return true;
                }
                player.sendMessage("§a[DEBUG] Dodano " + amount + " poziomów trzymanemu narzędziu.");
                return true;
            });
            getCommand("@addlvl").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }

        if (getCommand("@dajwszystko") != null) {
            getCommand("@dajwszystko").setExecutor((sender, command, label, args) -> {
                Player target;
                if (args.length > 0) {
                    target = Bukkit.getPlayer(args[0]);
                    if (target == null) {
                        sender.sendMessage("§cNie znaleziono online gracza o nicku: " + args[0]);
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage("§cPodaj nick gracza: /@dajwszystko <gracz>");
                    return true;
                }

                target.getInventory().addItem(pickaxeSkillManager.stworzKilof(target, PickaxeType.NIFLHEIM));
                for (String id : evolvingToolManager.ids()) {
                    target.getInventory().addItem(evolvingToolManager.stworz(id));
                }
                for (GemType gem : GemType.values()) {
                    target.getInventory().addItem(pickaxeSkillManager.stworzGem(gem));
                }
                target.sendMessage("§a[DEBUG] Otrzymałeś Kilof Niflheim, wszystkie ewoluujące narzędzia i wszystkie gemy do testów.");
                if (sender != target) {
                    sender.sendMessage("§aNadano komplet testowy graczowi " + target.getName() + ".");
                }
                return true;
            });
            getCommand("@dajwszystko").setTabCompleter((sender, command, alias, args) ->
                    args.length == 1 ? TabCompleteUtils.dopasujGraczy(args[0]) : TabCompleteUtils.PUSTA);
        }

        if (getCommand("@reloadnarzedzia") != null) {
            getCommand("@reloadnarzedzia").setExecutor((sender, command, label, args) -> {
                evolvingToolManager.reload();
                sender.sendMessage("§aWczytano ewoluujace-narzedzia.yml na nowo.");
                return true;
            });
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}