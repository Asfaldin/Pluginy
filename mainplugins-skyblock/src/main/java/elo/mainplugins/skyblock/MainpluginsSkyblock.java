package elo.mainplugins.skyblock;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.core.world.VoidGenerator;
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
        EconomyService economyService = CoreAPI.getEconomyService();

        islandManager = new IslandManager(this, economyService);
        borderManager = new BorderManager(this, islandManager);
        IslandProtectionManager islandProtectionManager = new IslandProtectionManager(this, islandManager);
        PoradnikManager poradnikManager = new PoradnikManager();
        MobRestrictionManager mobRestrictionManager = new MobRestrictionManager();

        getServer().getPluginManager().registerEvents(islandManager, this);
        getServer().getPluginManager().registerEvents(borderManager, this);
        getServer().getPluginManager().registerEvents(islandProtectionManager, this);
        getServer().getPluginManager().registerEvents(poradnikManager, this);
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
    }

    /** Podkomendy /is (i aliasu /dom, ten sam handler) - patrz IslandManager#handleCommand. */
    private static final class IsCommandHandler implements CommandExecutor, TabCompleter {

        // "zmenu" celowo pominięte - to wewnętrzny znacznik z GUI (patrz MenuPomocyManager),
        // nikt nie wpisuje go ręcznie. Angielskie aliasy (border/guests/build/mobs/upgrade/
        // members/add/invite/accept/deny/leave/promote/demote/remove/home/sethome/deposit/
        // withdraw - patrz handleCommand) celowo pominięte tu, w podpowiedziach Tab liczy się
        // tylko polska forma główna, żeby nie dublować listy.
        private static final List<String> PODKOMENDY = List.of(
                "menu", "ustawdom", "ustawspawn", "usun", "granica", "budowanie", "pvp", "potwory", "ulepszenia",
                "czlonkowie", "zapros", "akceptuj", "odrzuc", "opusc", "awansuj", "degraduj",
                "wyrzuc", "dom", "wplac", "wyplac"
        );
        private static final Set<String> PODKOMENDY_Z_GRACZEM = Set.of("zapros", "awansuj", "degraduj", "wyrzuc");

        private final IslandManager islandManager;

        private IsCommandHandler(IslandManager islandManager) {
            this.islandManager = islandManager;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
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