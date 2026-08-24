package elo.mainplugins.tools;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.ToolsService;
import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.tools.axe.AxeSkillManager;
import elo.mainplugins.tools.hoe.HoeSkillManager;
import elo.mainplugins.tools.pickaxe.BrukSurowceManager;
import elo.mainplugins.tools.pickaxe.GemType;
import elo.mainplugins.tools.pickaxe.PickaxeSkillManager;
import elo.mainplugins.tools.pickaxe.PickaxeType;
import elo.mainplugins.tools.special.NiszczycielManager;
import elo.mainplugins.tools.sword.SwordSkillManager;
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

        AxeSkillManager axeSkillManager = new AxeSkillManager(this, CoreAPI.getEconomyService());
        getServer().getPluginManager().registerEvents(axeSkillManager, this);
        levelableToolsManager.setAxeSkillManager(axeSkillManager);

        HoeSkillManager hoeSkillManager = new HoeSkillManager(this, CoreAPI.getEconomyService());
        getServer().getPluginManager().registerEvents(hoeSkillManager, this);
        levelableToolsManager.setHoeSkillManager(hoeSkillManager);

        SwordSkillManager swordSkillManager = new SwordSkillManager(this, CoreAPI.getEconomyService());
        getServer().getPluginManager().registerEvents(swordSkillManager, this);
        levelableToolsManager.setSwordSkillManager(swordSkillManager);

        NiszczycielManager niszczycielManager = new NiszczycielManager(this);
        getServer().getPluginManager().registerEvents(niszczycielManager, this);

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
                if (type == null) {
                    player.sendMessage("§cMusisz trzymać swoje przypisane narzędzie!");
                    return true;
                }

                if (type.equals("pickaxe")) {
                    pickaxeSkillManager.debugAddLevels(player, item, amount);
                } else if (type.equals("axe")) {
                    axeSkillManager.debugAddLevels(player, item, amount);
                } else if (type.equals("hoe")) {
                    hoeSkillManager.debugAddLevels(player, item, amount);
                } else if (type.equals("sword")) {
                    swordSkillManager.debugAddLevels(player, item, amount);
                } else {
                    levelableToolsManager.debugAddLevels(player, item, amount);
                }
                player.sendMessage("§a[DEBUG] Dodano " + amount + " poziomów trzymanemu narzędziu.");
                return true;
            });
            getCommand("@addlvl").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }

        if (getCommand("@addcustompickaxe") != null) {
            getCommand("@addcustompickaxe").setExecutor((sender, command, label, args) -> {
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
                    sender.sendMessage("§cPodaj nick gracza: /@addcustompickaxe <gracz>");
                    return true;
                }

                levelableToolsManager.dajEwoluujacyKilof(target);
                if (sender != target) {
                    sender.sendMessage("§aNadano custom kilof graczowi " + target.getName() + ".");
                }
                return true;
            });
            getCommand("@addcustompickaxe").setTabCompleter((sender, command, alias, args) ->
                    args.length == 1 ? TabCompleteUtils.dopasujGraczy(args[0]) : TabCompleteUtils.PUSTA);
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

                for (PickaxeType type : PickaxeType.values()) {
                    target.getInventory().addItem(pickaxeSkillManager.stworzKilof(target, type));
                }
                for (GemType gem : GemType.values()) {
                    target.getInventory().addItem(pickaxeSkillManager.stworzGem(gem));
                }
                target.sendMessage("§a[DEBUG] Otrzymałeś wszystkie typy kilofa i wszystkie gemy do testów.");
                if (sender != target) {
                    sender.sendMessage("§aNadano komplet testowy graczowi " + target.getName() + ".");
                }
                return true;
            });
            getCommand("@dajwszystko").setTabCompleter((sender, command, alias, args) ->
                    args.length == 1 ? TabCompleteUtils.dopasujGraczy(args[0]) : TabCompleteUtils.PUSTA);
        }

        if (getCommand("@dajkilofa") != null) {
            getCommand("@dajkilofa").setExecutor((sender, command, label, args) -> {
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
                    sender.sendMessage("§cPodaj nick gracza: /@dajkilofa <gracz>");
                    return true;
                }

                target.getInventory().addItem(pickaxeSkillManager.stworzKilof(target, PickaxeType.DOSWIADCZENIA));
                target.sendMessage("§a[DEBUG] Otrzymałeś przykładowy Kilof Doświadczenia (nowy silnik, max 30 lvl).");
                if (sender != target) {
                    sender.sendMessage("§aNadano Kilof Doświadczenia graczowi " + target.getName() + ".");
                }
                return true;
            });
            getCommand("@dajkilofa").setTabCompleter((sender, command, alias, args) ->
                    args.length == 1 ? TabCompleteUtils.dopasujGraczy(args[0]) : TabCompleteUtils.PUSTA);
        }

        if (getCommand("@dajsniezny") != null) {
            getCommand("@dajsniezny").setExecutor((sender, command, label, args) -> {
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
                    sender.sendMessage("§cPodaj nick gracza: /@dajsniezny <gracz>");
                    return true;
                }

                target.getInventory().addItem(pickaxeSkillManager.stworzKilof(target, PickaxeType.NIFLHEIM));
                target.sendMessage("§a[DEBUG] Otrzymałeś legendarny Kilof Niflheim (nowy silnik, max 30 lvl).");
                if (sender != target) {
                    sender.sendMessage("§aNadano Kilof Niflheim graczowi " + target.getName() + ".");
                }
                return true;
            });
            getCommand("@dajsniezny").setTabCompleter((sender, command, alias, args) ->
                    args.length == 1 ? TabCompleteUtils.dopasujGraczy(args[0]) : TabCompleteUtils.PUSTA);
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}