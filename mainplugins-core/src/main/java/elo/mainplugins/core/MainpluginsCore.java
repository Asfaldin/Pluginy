package elo.mainplugins.core;

import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.command.AdminHelpCommand;
import elo.mainplugins.core.command.AdminPomocCommand;
import elo.mainplugins.core.command.CommandRemapper;
import elo.mainplugins.core.command.DajCustomCommand;
import elo.mainplugins.core.command.DiscordCommand;
import elo.mainplugins.core.command.MoneyAddCommand;
import elo.mainplugins.core.command.MoneyUndoCommand;
import elo.mainplugins.core.command.PayCommand;
import elo.mainplugins.core.command.PomocCommand;
import elo.mainplugins.core.command.PortfelCommand;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.LicenseService;
import elo.mainplugins.core.api.PlaceholderService;
import elo.mainplugins.core.api.RewardService;
import elo.mainplugins.core.api.UnlockService;
import elo.mainplugins.core.command.UnlockCommand;
import elo.mainplugins.core.customitem.CustomItemManager;
import elo.mainplugins.core.economy.EconomyManager;
import elo.mainplugins.core.economy.VaultBackedEconomy;
import elo.mainplugins.core.economy.VaultHook;
import elo.mainplugins.core.lang.LangManager;
import elo.mainplugins.core.placeholder.PlaceholderManager;
import elo.mainplugins.core.reward.RewardManager;
import elo.mainplugins.core.unlock.UnlockManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import elo.mainplugins.core.license.LicenseManager;
import elo.mainplugins.core.util.TabCompleteUtils;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Rdzeń całego ekosystemu Mainplugins. Nie zawiera żadnej logiki gry - tylko
 * usługi współdzielone (na razie: ekonomia) i narzędzia, z których korzystają
 * pozostałe, w pełni niezależne pluginy. Musi być włączony jako pierwszy
 * (każdy zależny plugin ma go w plugin.yml jako "depend").
 */
public final class MainpluginsCore extends JavaPlugin {

    private EconomyManager economyManager;
    private EconomyService economyService;
    private LangManager langManager;

    @Override
    public void onEnable() {
        getLogger().info("Uruchamianie MainpluginsCore...");

        saveDefaultConfig();
        PlaceholderManager placeholderManager = new PlaceholderManager(this, () -> economyService);
        getServer().getServicesManager().register(PlaceholderService.class, placeholderManager, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(placeholderManager, this);

        langManager = new LangManager(this, placeholderManager);
        getServer().getServicesManager().register(LangService.class, langManager, this, ServicePriority.Normal);
        langManager.registerDefaults(this);

        boolean vault = getServer().getPluginManager().isPluginEnabled("Vault");
        String mode = getConfig().getString("economy", "own");
        if ("vault".equalsIgnoreCase(mode) && vault) {
            economyService = new VaultBackedEconomy(VaultHook.backend(this));
            getLogger().info("Economy mode: vault (money of another plugin).");
        } else {
            if ("vault".equalsIgnoreCase(mode)) {
                getLogger().severe("economy: vault is set, but Vault is not installed - using our own economy instead.");
            }
            economyManager = new EconomyManager(this);
            economyService = economyManager;
            if (vault) VaultHook.registerProvider(this, economyManager);
        }
        getServer().getServicesManager().register(EconomyService.class, economyService, this, ServicePriority.Normal);

        CustomItemManager customItemManager = new CustomItemManager(this);
        getServer().getServicesManager().register(CustomItemService.class, customItemManager, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(customItemManager, this);

        LicenseManager licenseManager = new LicenseManager(this);
        getServer().getServicesManager().register(LicenseService.class, licenseManager, this, ServicePriority.Normal);

        RewardManager rewardManager = new RewardManager(this, economyService, customItemManager, langManager);
        getServer().getServicesManager().register(RewardService.class, rewardManager, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(rewardManager, this);

        UnlockManager unlockManager = new UnlockManager(new File(getDataFolder(), "unlocks.yml"), getLogger()::warning);
        getServer().getServicesManager().register(UnlockService.class, unlockManager, this, ServicePriority.Normal);
        // Nagroda "unlock: nazwa" działa w nagrodach WSZYSTKICH pluginów.
        rewardManager.registerType(this, RewardService.UNLOCK, (player, r) -> {
            String name = String.valueOf(r.value()).trim().toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty()) return false;
            unlockManager.give(player.getUniqueId(), name);
            if (!r.silent()) langManager.send(player, this, "reward.unlock", java.util.Map.of("name", name));
            return true;
        });

        getServer().getPluginManager().registerEvents(new ResourcePackManager(this), this);

        if (getCommand("wszystkiekomendy") != null) {
            getCommand("wszystkiekomendy").setExecutor(new AdminHelpCommand());
            getCommand("wszystkiekomendy").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        PomocCommand pomocCommand = new PomocCommand();
        if (getCommand("komendy") != null) {
            getCommand("komendy").setExecutor(pomocCommand);
            getCommand("komendy").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("komendy2") != null) {
            getCommand("komendy2").setExecutor(pomocCommand);
            getCommand("komendy2").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("komendy3") != null) {
            getCommand("komendy3").setExecutor(pomocCommand);
            getCommand("komendy3").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("komendy4") != null) {
            getCommand("komendy4").setExecutor(pomocCommand);
            getCommand("komendy4").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        AdminPomocCommand adminPomocCommand = new AdminPomocCommand();
        if (getCommand("@komendy") != null) {
            getCommand("@komendy").setExecutor(adminPomocCommand);
            getCommand("@komendy").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@komendy2") != null) {
            getCommand("@komendy2").setExecutor(adminPomocCommand);
            getCommand("@komendy2").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@komendy3") != null) {
            getCommand("@komendy3").setExecutor(adminPomocCommand);
            getCommand("@komendy3").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@moneyadd") != null) {
            MoneyAddCommand moneyAddCommand = new MoneyAddCommand(economyService);
            getCommand("@moneyadd").setExecutor(moneyAddCommand);
            getCommand("@moneyadd").setTabCompleter(moneyAddCommand);
        }
        if (getCommand("@moneyundo") != null) {
            MoneyUndoCommand moneyUndoCommand = new MoneyUndoCommand(economyService);
            getCommand("@moneyundo").setExecutor(moneyUndoCommand);
            getCommand("@moneyundo").setTabCompleter(moneyUndoCommand);
        }
        if (getCommand("przelej") != null) {
            PayCommand payCommand = new PayCommand(economyService);
            getCommand("przelej").setExecutor(payCommand);
            getCommand("przelej").setTabCompleter(payCommand);
        }
        if (getCommand("portfel") != null) {
            getCommand("portfel").setExecutor(new PortfelCommand(economyService));
            getCommand("portfel").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("discord") != null) {
            getCommand("discord").setExecutor(new DiscordCommand());
            getCommand("discord").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@dajcustom") != null) {
            DajCustomCommand dajCustomCommand = new DajCustomCommand(customItemManager);
            getCommand("@dajcustom").setExecutor(dajCustomCommand);
            getCommand("@dajcustom").setTabCompleter(dajCustomCommand);
        }
        // Osobny executor: /@reloadcustomitems ma sens też z konsoli, nie tylko od gracza
        // (ten sam wzorzec co /@reloadsklep w mainplugins-shop).
        if (getCommand("@reloadcustomitems") != null) {
            getCommand("@reloadcustomitems").setExecutor((sender, command, label, args) -> {
                customItemManager.reload();
                langManager.send(sender, this, "items.reloaded");
                return true;
            });
            getCommand("@reloadcustomitems").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@rewardtest") != null) {
            getCommand("@rewardtest").setExecutor((sender, command, label, args) -> {
                if (args.length < 1) {
                    langManager.send(sender, this, "admin.rewardtest.usage");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    langManager.send(sender, this, "admin.player-not-found", java.util.Map.of("player", args[0]));
                    return true;
                }
                reloadConfig();
                rewardManager.give(target, rewardManager.parse(getConfig().getList("test-rewards"), "config.yml test-rewards"));
                langManager.send(sender, this, "admin.rewardtest.done", java.util.Map.of("player", target.getName()));
                return true;
            });
        }
        if (getCommand("@reloadlang") != null) {
            getCommand("@reloadlang").setExecutor((sender, command, label, args) -> {
                langManager.reload();
                langManager.send(sender, this, "lang.reloaded", java.util.Map.of("language", langManager.language()));
                return true;
            });
            getCommand("@reloadlang").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@unlock") != null) {
            UnlockCommand unlockCommand = new UnlockCommand(this, unlockManager, langManager);
            getCommand("@unlock").setExecutor(unlockCommand);
            getCommand("@unlock").setTabCompleter(unlockCommand);
        }

        getServer().getPluginManager().registerEvents(new CommandRemapper(this), this);

        getLogger().info("MainpluginsCore włączony - EconomyService dostępny dla innych pluginów.");
    }

    @Override
    public void onDisable() {
        if (economyManager != null) economyManager.zamknij();   // <-- NOWE
        getServer().getServicesManager().unregisterAll(this);
        getLogger().info("Wyłączanie MainpluginsCore...");
    }
}