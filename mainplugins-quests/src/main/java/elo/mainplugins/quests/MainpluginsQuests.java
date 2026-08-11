package elo.mainplugins.quests;

import elo.mainplugins.core.api.TytulService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsQuests extends JavaPlugin {

    @Override
    public void onEnable() {
        QuestManager questManager = new QuestManager(this);
        getServer().getPluginManager().registerEvents(questManager, this);
        getServer().getServicesManager().register(TytulService.class, questManager, this, ServicePriority.Normal);

        GeneratorKruchychManager generatorManager = new GeneratorKruchychManager(this);
        getServer().getPluginManager().registerEvents(generatorManager, this);
        zarejestrujReceptureGeneratora();

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

        if (getCommand("addfale") != null) {
            getCommand("addfale").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                questManager.wywolajTestoweFale(player);
                return true;
            });
        }

        if (getCommand("addkruchy") != null) {
            getCommand("addkruchy").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                player.getInventory().addItem(GeneratorKruchychManager.stworzGenerator(), GeneratorKruchychManager.stworzKsiazkaPrzewodnik());
                player.sendMessage("Otrzymales testowy generator kruchych surowcow + przewodnik.");
                return true;
            });
        }
    }

    /**
     * ShapelessRecipe (nie Shaped) - opisany układ składników (patrz
     * GeneratorKruchychManager.stworzKsiazkaPrzewodnik) ma więcej "warstw" niż mieści
     * 3x3 siatka rzemieślnicza, więc liczą się tylko ILOŚCI, nie pozycje w stole.
     */
    private void zarejestrujReceptureGeneratora() {
        NamespacedKey klucz = new NamespacedKey(this, "generator_kruchy_t1");
        ShapelessRecipe receptura = new ShapelessRecipe(klucz, GeneratorKruchychManager.stworzGenerator());
        receptura.addIngredient(64, Material.STONE);
        receptura.addIngredient(64, Material.STONE);
        receptura.addIngredient(10, Material.COAL);
        receptura.addIngredient(5, Material.COPPER_INGOT);
        receptura.addIngredient(10, Material.DIRT);
        Bukkit.addRecipe(receptura);
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}