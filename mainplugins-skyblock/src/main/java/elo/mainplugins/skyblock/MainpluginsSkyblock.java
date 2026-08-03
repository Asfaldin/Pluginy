package elo.mainplugins.skyblock;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.world.VoidGenerator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MainpluginsSkyblock extends JavaPlugin {

    private IslandManager islandManager;
    private BorderManager borderManager;

    @Override
    public void onEnable() {
        EconomyService economyService = CoreAPI.getEconomyService();

        islandManager = new IslandManager(this, economyService);
        borderManager = new BorderManager(this);
        IslandProtectionManager islandProtectionManager = new IslandProtectionManager(islandManager);

        getServer().getPluginManager().registerEvents(islandManager, this);
        getServer().getPluginManager().registerEvents(borderManager, this);
        getServer().getPluginManager().registerEvents(islandProtectionManager, this);

        // Opcjonalny serwis dla innych pluginów (np. HUD-a) - w przeciwieństwie do
        // EconomyService w Core, nikt nie jest zobowiązany z niego korzystać.
        getServer().getServicesManager().register(IslandService.class, islandManager, this, ServicePriority.Normal);

        var executor = new org.bukkit.command.CommandExecutor() {
            @Override
            public boolean onCommand(@NotNull org.bukkit.command.CommandSender sender, @NotNull org.bukkit.command.Command command, @NotNull String label, @NotNull String[] args) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                // /home to osobna komenda (nie subkomenda /is), więc handleCommand()
                // widziałby ją jako "brak argumentów" i otwierał panel GUI zamiast
                // teleportować - tutaj rozpoznajemy ją po nazwie i idziemy prosto do teleportu.
                if (command.getName().equalsIgnoreCase("home")) {
                    islandManager.teleportDoWyspy(player);
                } else {
                    islandManager.handleCommand(player, args);
                }
                borderManager.wyczyscCzerwonyEkranBorderu(player);
                return true;
            }
        };

        if (getCommand("is") != null) getCommand("is").setExecutor(executor);
        if (getCommand("home") != null) getCommand("home").setExecutor(executor);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (islandManager != null) islandManager.zapiszWszystkieWyspy();
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        return new VoidGenerator();
    }
}