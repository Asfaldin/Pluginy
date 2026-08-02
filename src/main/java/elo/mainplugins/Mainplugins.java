package elo.mainplugins;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class Mainplugins extends JavaPlugin {

    private TablistManager tablistManager;
    private ScoreboardManager scoreboardManager;

    @Override
    public void onEnable() {
        getLogger().info("Uruchamianie Mainplugins...");

        EconomyManager economyManager = new EconomyManager(this);

        QuestManager questManager = new QuestManager();
        StorageManager storageManager = new StorageManager(this);
        ShopManager shopManager = new ShopManager(this, economyManager);
        MarketManager marketManager = new MarketManager(this, economyManager);
        IslandManager islandManager = new IslandManager(this, economyManager);

        tablistManager = new TablistManager(this, economyManager);
        LevelableToolsManager levelableToolsManager = new LevelableToolsManager(this);
        scoreboardManager = new ScoreboardManager(this, economyManager);
        BorderManager borderManager = new BorderManager(this);

        // Menu jest teraz w pełni niezależne, nie musimy mu niczego przekazywać!
        MenuPomocyManager menuPomocyManager = new MenuPomocyManager();

        getServer().getPluginManager().registerEvents(questManager, this);
        getServer().getPluginManager().registerEvents(menuPomocyManager, this);
        getServer().getPluginManager().registerEvents(tablistManager, this);
        getServer().getPluginManager().registerEvents(levelableToolsManager, this);
        getServer().getPluginManager().registerEvents(storageManager, this);
        getServer().getPluginManager().registerEvents(shopManager, this);
        getServer().getPluginManager().registerEvents(scoreboardManager, this);
        getServer().getPluginManager().registerEvents(islandManager, this);
        getServer().getPluginManager().registerEvents(marketManager, this);
        getServer().getPluginManager().registerEvents(borderManager, this);

        CommandExecutor komendy = new CommandExecutor() {
            @Override
            public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }

                // Magiczna zmienna sprawdzająca czy komendę wywołało w tle nasze MenuPomocyManager
                boolean zMenu = (args.length > 0 && args[0].equalsIgnoreCase("zmenu"));

                switch (command.getName().toLowerCase()) {
                    case "menu" -> menuPomocyManager.otworzMenuPomocy(player);
                    case "sklep", "buy" -> shopManager.otworzSklep(player, zMenu);

                    // Pozostałe komendy z GUI (o ile w przyszłości dostosujesz ich metody do przyjmowania zMenu)
                    // case "quest" -> questManager.otworzMenuQuestow(player, zMenu);
                    // case "itemy" -> storageManager.otworzSchowek(player, zMenu);

                    case "narzedzia" -> levelableToolsManager.dajStartoweNarzedzia(player);
                    case "sell" -> shopManager.handleSellCommand(player);
                    case "sellall" -> shopManager.handleSellAllCommand(player);

                    case "targ" -> {
                        if (args.length > 0 && args[0].equalsIgnoreCase("wystaw")) {
                            marketManager.wystawPrzedmiot(player, args);
                        } else {
                            // Jeśli chcesz dodać powrót też w targu, to np.: marketManager.otworzTarg(player, 0, zMenu);
                            marketManager.otworzTarg(player, 0);
                        }
                    }

                    case "is", "home" -> {
                        islandManager.handleCommand(player, args);
                        borderManager.wyczyscCzerwonyEkranBorderu(player);
                    }
                }
                return true;
            }
        };

        if (getCommand("menu") != null) getCommand("menu").setExecutor(komendy);
        if (getCommand("quest") != null) getCommand("quest").setExecutor(komendy);
        if (getCommand("itemy") != null) getCommand("itemy").setExecutor(komendy);
        if (getCommand("sklep") != null) getCommand("sklep").setExecutor(komendy);
        if (getCommand("targ") != null) getCommand("targ").setExecutor(komendy);
        if (getCommand("narzedzia") != null) getCommand("narzedzia").setExecutor(komendy);
        if (getCommand("sell") != null) getCommand("sell").setExecutor(komendy);
        if (getCommand("sellall") != null) getCommand("sellall").setExecutor(komendy);
        if (getCommand("is") != null) getCommand("is").setExecutor(komendy);
        if (getCommand("home") != null) getCommand("home").setExecutor(komendy);

        getLogger().info("Mainplugins został pomyślnie włączony!");
    }

    @Override
    public void onDisable() {
        if (tablistManager != null) tablistManager.wyczyscZadania();
        if (scoreboardManager != null) scoreboardManager.wyczysc();
        getLogger().info("Wyłączanie Mainplugins...");
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        return new VoidGenerator();
    }
}