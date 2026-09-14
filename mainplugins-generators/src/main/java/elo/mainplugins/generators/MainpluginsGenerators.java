package elo.mainplugins.generators;

import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.generators.generator.GeneratorManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;

/** Generatory (bruk, piasek/żwir, tiery 2-4) - wydzielone z Questów 1:1. Na razie bez licencji. */
public final class MainpluginsGenerators extends JavaPlugin {

    private GeneratorManager tierowyGeneratorManager;

    @Override
    public void onEnable() {
        GeneratorKruchychManager generatorManager = new GeneratorKruchychManager(this);
        getServer().getPluginManager().registerEvents(generatorManager, this);
        zarejestrujReceptureGeneratora();

        GeneratorBrukuManager generatorBrukuManager = new GeneratorBrukuManager(this);
        getServer().getPluginManager().registerEvents(generatorBrukuManager, this);

        tierowyGeneratorManager = new GeneratorManager(this);
        getServer().getPluginManager().registerEvents(tierowyGeneratorManager, this);

        if (getCommand("@addkruchy") != null) {
            getCommand("@addkruchy").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                player.getInventory().addItem(GeneratorKruchychManager.stworzGenerator(), GeneratorKruchychManager.stworzKsiazkaPrzewodnik());
                player.sendMessage("Otrzymales testowy generator kruchych surowcow + przewodnik.");
                return true;
            });
            getCommand("@addkruchy").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }

        if (getCommand("@dajbrukgen") != null) {
            getCommand("@dajbrukgen").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                player.getInventory().addItem(GeneratorBrukuManager.stworzGenerator());
                player.sendMessage("Otrzymales Generator Bruku.");
                return true;
            });
            getCommand("@dajbrukgen").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }

        if (getCommand("@dajgenerator") != null) {
            getCommand("@dajgenerator").setExecutor((sender, command, label, args) -> {
                if (args.length == 0) {
                    sender.sendMessage("Podaj id: /@dajgenerator <id> [gracz]");
                    return true;
                }
                ItemStack item = tierowyGeneratorManager.stworz(args[0]);
                if (item == null) {
                    sender.sendMessage("Nieznane id generatora: " + args[0]);
                    return true;
                }
                Player target;
                if (args.length > 1) {
                    target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage("Nie znaleziono online gracza o nicku: " + args[1]);
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage("Podaj nick gracza: /@dajgenerator " + args[0] + " <gracz>");
                    return true;
                }
                target.getInventory().addItem(item);
                target.sendMessage("Otrzymales generator: " + args[0]);
                return true;
            });
            getCommand("@dajgenerator").setTabCompleter((sender, command, alias, args) -> {
                if (args.length == 1) return TabCompleteUtils.dopasuj(args[0], tierowyGeneratorManager.ids().stream().toList());
                if (args.length == 2) return TabCompleteUtils.dopasujGraczy(args[1]);
                return TabCompleteUtils.PUSTA;
            });
        }

        if (getCommand("@reloadgeneratory") != null) {
            getCommand("@reloadgeneratory").setExecutor((sender, command, label, args) -> {
                tierowyGeneratorManager.reload();
                sender.sendMessage("Przeladowano generatory.yml.");
                return true;
            });
        }
    }

    /** Receptura bez kształtu, maks. 9 składników (siatka 3x3) - patrz historia w mainplugins-quests. */
    private void zarejestrujReceptureGeneratora() {
        NamespacedKey klucz = new NamespacedKey(this, "generator_kruchy_t1");
        ShapelessRecipe receptura = new ShapelessRecipe(klucz, GeneratorKruchychManager.stworzGenerator());
        receptura.addIngredient(4, Material.STONE);
        receptura.addIngredient(2, Material.COAL);
        receptura.addIngredient(1, Material.COPPER_INGOT);
        receptura.addIngredient(2, Material.DIRT);
        Bukkit.addRecipe(receptura);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
