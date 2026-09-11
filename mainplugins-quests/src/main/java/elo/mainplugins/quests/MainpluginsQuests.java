package elo.mainplugins.quests;

import elo.mainplugins.core.api.QuestService;
import elo.mainplugins.core.api.TytulService;
import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.quests.generator.GeneratorManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class MainpluginsQuests extends JavaPlugin {

    private QuestManager questManager;
    private GeneratorManager tierowyGeneratorManager;

    @Override
    public void onEnable() {
        questManager = new QuestManager(this);
        getServer().getPluginManager().registerEvents(questManager, this);
        getServer().getServicesManager().register(TytulService.class, questManager, this, ServicePriority.Normal);
        getServer().getServicesManager().register(QuestService.class, questManager, this, ServicePriority.Normal);

        GeneratorKruchychManager generatorManager = new GeneratorKruchychManager(this);
        getServer().getPluginManager().registerEvents(generatorManager, this);
        zarejestrujReceptureGeneratora();

        GeneratorBrukuManager generatorBrukuManager = new GeneratorBrukuManager(this);
        getServer().getPluginManager().registerEvents(generatorBrukuManager, this);

        // Silnik NOWYCH generatorów tier 2-4 (generatory.yml) - dodatkowy, obok obu powyzszych
        // (T1), patrz javadoc GeneratorManager.
        tierowyGeneratorManager = new GeneratorManager(this);
        getServer().getPluginManager().registerEvents(tierowyGeneratorManager, this);

        if (getCommand("zadania") != null) {
            getCommand("zadania").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
                    return true;
                }
                // Naprawiony bug z oryginału: /zadania (dawniej /quest) wcześniej nie było w ogóle
                // podpięte do żadnej logiki (case w switchu był zakomentowany).
                boolean zMenu = args.length > 0 && args[args.length - 1].equalsIgnoreCase("zmenu");
                questManager.otworzMenuQuestow(player, zMenu);
                return true;
            });
            getCommand("zadania").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }

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

        if (getCommand("@reloadquesty") != null) {
            getCommand("@reloadquesty").setExecutor((sender, command, label, args) -> {
                questManager.przeladujTresc();
                sender.sendMessage("Przeladowano quests-content.yml.");
                return true;
            });
            getCommand("@reloadquesty").setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
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

    /**
     * ShapelessRecipe (nie Shaped) - składniki bez ustalonych pozycji w siatce 3x3.
     *
     * NAPRAWIONY BUG: addIngredient(count, Material) NIE oznacza "wymagaj count sztuk" -
     * KAŻDA sztuka zajmuje osobny slot siatki rzemieślniczej, a cały ShapelessRecipe może
     * mieć MAKSYMALNIE 9 składników łącznie (twardy limit Minecrafta, siatka 3x3). Oryginał
     * próbował wymagać 64+64+10+5+10=153 sztuk naraz - fizycznie niemożliwe, crashowało
     * MainpluginsQuests już przy starcie. Przeskalowane proporcjonalnie do 9 slotów
     * (4 Kamień + 2 Węgiel + 1 Miedź + 2 Ziemia), zachowując względne proporcje oryginału
     * (kamień dominujący, miedź najrzadsza) - jeśli chcesz inny balans, to jedyne miejsce do zmiany.
     */
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
        if (questManager != null) questManager.zamknij();
        getServer().getServicesManager().unregisterAll(this);
    }
}