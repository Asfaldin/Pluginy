package elo.mainplugins.skyblock;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.core.world.VoidGenerator;
import elo.mainplugins.skyblock.template.IslandTemplateCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

public final class MainpluginsSkyblock extends JavaPlugin {

    private IslandManager islandManager;
    private BorderManager borderManager;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("skyblock")) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-skyblock - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: skyblock: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        EconomyService economyService = CoreAPI.getEconomyService();
        CoreAPI.getLangService().registerDefaults(this);

        islandManager = new IslandManager(this, economyService);
        borderManager = new BorderManager(this, islandManager);
        IslandProtectionManager islandProtectionManager = new IslandProtectionManager(this, islandManager);
        MobRestrictionManager mobRestrictionManager = new MobRestrictionManager();

        getServer().getPluginManager().registerEvents(islandManager, this);
        getServer().getPluginManager().registerEvents(borderManager, this);
        getServer().getPluginManager().registerEvents(islandProtectionManager, this);
        getServer().getPluginManager().registerEvents(mobRestrictionManager, this);

        // Opcjonalny serwis dla innych pluginów (np. HUD-a) - w przeciwieństwie do
        // EconomyService w Core, nikt nie jest zobowiązany z niego korzystać.
        getServer().getServicesManager().register(IslandService.class, islandManager, this, ServicePriority.Normal);

        var executor = new IsCommandHandler(islandManager);

        if (getCommand("is") != null) {
            getCommand("is").setExecutor(executor);
            getCommand("is").setTabCompleter(executor);
        }
        if (getCommand("dom") != null) {
            getCommand("dom").setExecutor(executor);
            getCommand("dom").setTabCompleter(executor);
        }

        if (getCommand("@islandtemplate") != null) {
            var templateCommand = new IslandTemplateCommand(this, islandManager.getTemplate(), CoreAPI.getLangService());
            getCommand("@islandtemplate").setExecutor(templateCommand);
            getCommand("@islandtemplate").setTabCompleter(templateCommand);
            getServer().getPluginManager().registerEvents(templateCommand, this);
        }

        // /@is - komendy admina (tp/usun/rozmiar/bank), dzialaja tez z konsoli.
        if (getCommand("@is") != null) {
            getCommand("@is").setExecutor((sender, command, label, args) -> {
                islandManager.komendaAdmina(sender, args);
                return true;
            });
            getCommand("@is").setTabCompleter((sender, command, alias, args) -> {
                if (args.length == 1) return TabCompleteUtils.dopasuj(args[0], List.of("tp", "usun", "rozmiar", "bank"));
                if (args.length == 2) return TabCompleteUtils.dopasujGraczy(args[1]);
                if (args.length == 3 && args[0].equalsIgnoreCase("bank")) return TabCompleteUtils.dopasuj(args[2], List.of("ustaw", "dodaj"));
                return TabCompleteUtils.PUSTA;
            });
        }

        // Osobny executor: /@reloadwyspy ma sens tez z konsoli, nie tylko od gracza.
        // Uprawnienie (mainplugins.skyblock.reload, domyslnie op) pilnuje tego plugin.yml.
        if (getCommand("@reloadwyspy") != null) {
            getCommand("@reloadwyspy").setExecutor((sender, command, label, args) -> {
                islandManager.przeladujKonfiguracje();
                CoreAPI.getLangService().send(sender, MainpluginsSkyblock.this, "common.reloaded");
                return true;
            });
        }
    }

    /** Podkomendy /is (i aliasu /dom, ten sam handler) - patrz IslandManager#handleCommand. */
    private static final class IsCommandHandler implements CommandExecutor, TabCompleter {

        // "zmenu" celowo pominięte - to wewnętrzny znacznik z GUI (patrz MenuPomocyManager),
        // nikt nie wpisuje go ręcznie. Angielskie aliasy (border/guests/build/mobs/upgrade/
        // members/add/invite/accept/deny/leave/promote/demote/remove/home/sethome/deposit/
        // withdraw - patrz handleCommand) celowo pominięte tu, w podpowiedziach Tab liczy się
        // tylko polska forma główna, żeby nie dublować listy.
        private static final List<String> PODKOMENDY = List.of(
                "menu", "ustawdom", "ustawspawn", "usun", "granica", "budowanie", "pvp", "ulepszenia",
                "czlonkowie", "ustawienia", "permisje", "zapros", "akceptuj", "odrzuc", "opusc", "awansuj", "degraduj",
                "wyrzuc", "dom", "wplac", "wyplac", "odwiedz", "przekaz", "wypros", "zbanuj", "odbanuj"
        );
        private static final Set<String> PODKOMENDY_Z_GRACZEM = Set.of("zapros", "awansuj", "degraduj", "wyrzuc", "odwiedz", "przekaz", "wypros", "zbanuj", "odbanuj");

        private final IslandManager islandManager;

        private IsCommandHandler(IslandManager islandManager) {
            this.islandManager = islandManager;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
            if (!(sender instanceof Player player)) {
                CoreAPI.getLangService().send(sender, islandManager.plugin(), "common.players-only");
                return true;
            }
            // Nazwa komendy (nie alias) - odróżnia "/is" od "/dom"/"/home" przy pustych
            // argumentach, patrz handleCommand.
            islandManager.handleCommand(player, args, command.getName());
            return true;
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
            if (args.length == 1) return TabCompleteUtils.dopasuj(args[0], PODKOMENDY);
            if (args.length == 2 && PODKOMENDY_Z_GRACZEM.contains(args[0].toLowerCase())) {
                return TabCompleteUtils.dopasujGraczy(args[1]);
            }
            return TabCompleteUtils.PUSTA;
        }
    }

    @Override
    public void onDisable() {
        if (islandManager != null) islandManager.zamknij();
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        return new VoidGenerator();
    }
}