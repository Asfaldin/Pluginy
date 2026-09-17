package elo.mainplugins.shop;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.util.MenuBridge;
import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.shop.model.Category;
import elo.mainplugins.shop.model.ShopConfig;
import elo.mainplugins.shop.model.ShopItem;
import elo.mainplugins.shop.model.ShopSettings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Sklep serwerowy - działa z samym core. Treść w shop.yml + categories/, teksty w lang/. */
public final class MainpluginsShop extends JavaPlugin {

    /** Kategorie treści startowej (defaults/<język>/categories/<id>.yml). */
    private static final List<String> DEFAULT_CATEGORIES = List.of("blocks", "farming", "ores", "mob-drops", "food");
    /** Pliki starego sklepu - przy pierwszym starcie nowej wersji idą do old/. */
    private static final List<String> OLD_FILES = List.of("sklep.yml", "sklep-gui.yml", "pula-rotacyjna.yml", "categories",
            "ceny-dynamiczne.yml", "statystyki-sklepu.yml", "statystyki-sklepu.csv", "archiwum-statystyk", "rotacja.yml");

    private volatile ShopConfig config;
    private LangService lang;
    private ShopStats stats;
    private DynamicPriceManager prices;
    private RotationManager rotation;
    private ShopManager shop;
    private BukkitTask timer;
    private BukkitTask eventTimer;

    @Override
    public void onEnable() {
        // Plugin płatny - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("shop")) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-shop - plugin zostanie wyłączony.");
            getLogger().severe("Skonfiguruj klucz w license.yml (folder danych MainpluginsCore, sekcja 'keys: shop: ...') i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        prepareFiles();
        config = load();

        ShopItems items = new ShopItems(this, CoreAPI.getCustomItemService(), CoreAPI.getItemNameService());
        stats = new ShopStats(this, config.settings().statsEnabled());
        prices = new DynamicPriceManager(this, config.settings().dynamic(), stats, key -> nameOf(items, key),
                () -> Bukkit.getOnlinePlayers().forEach(p -> lang.send(p, this, "dynamic.reset-broadcast")));
        rotation = new RotationManager(this, lang, items, () -> config);
        rotation.check();
        shop = new ShopManager(this, lang, CoreAPI.getEconomyService(), items, () -> config, prices, rotation, stats);
        getServer().getPluginManager().registerEvents(shop, this);
        CoreAPI.getPlaceholderService().register(this, new ShopPlaceholders(this, lang, prices));

        // Co 10 minut: nowe rotacje i zapis statystyk.
        timer = getServer().getScheduler().runTaskTimer(this, () -> {
            rotation.check();
            stats.zapisz();
        }, 12_000L, 12_000L);

        // Co 10 sekund: eventy, którym minął czas (/@shop event <item> <procent> <czas>).
        eventTimer = getServer().getScheduler().runTaskTimer(this, () -> {
            for (String key : prices.zakonczWygasle()) {
                if (!config.settings().dynamic().announceEvents()) continue;
                Bukkit.getOnlinePlayers().forEach(p ->
                        lang.send(p, this, "event.broadcast-off", Map.of("item", nameOf(items, key))));
            }
        }, 200L, 200L);

        CommandExecutor player = (sender, command, label, args) -> {
            if (!(sender instanceof Player p)) {
                lang.send(sender, this, "admin.players-only");
                return true;
            }
            switch (command.getName().toLowerCase()) {
                case "sklep" -> shop.openMain(p, MenuBridge.isZMenu(args));
                case "sprzedaj" -> shop.sellHand(p);
                case "sprzedajwszystko" -> shop.sellAll(p);
                default -> { }
            }
            return true;
        };
        for (String name : List.of("sklep", "sprzedaj", "sprzedajwszystko")) {
            if (getCommand(name) == null) continue;
            getCommand(name).setExecutor(player);
            getCommand(name).setTabCompleter((sender, command, alias, args) -> TabCompleteUtils.PUSTA);
        }
        if (getCommand("@shop") != null) {
            ShopCommand admin = new ShopCommand(this, lang, () -> config, this::reload, prices, rotation, stats, items);
            getCommand("@shop").setExecutor(admin);
            getCommand("@shop").setTabCompleter(admin);
        }
    }

    @Override
    public void onDisable() {
        if (timer != null) timer.cancel();
        if (eventTimer != null) eventTimer.cancel();
        if (rotation != null) rotation.close();
        if (prices != null) prices.zamknij();
        if (stats != null) stats.zapisz();
    }

    /** /@shop reload - pliki od nowa, nowe ustawienia cen dynamicznych i statystyk. */
    private void reload() {
        config = load();
        prices.applySettings(config.settings().dynamic());
        stats.setEnabled(config.settings().statsEnabled());
        rotation.check();
    }

    private String nameOf(ShopItems items, String key) {
        for (Category c : config.categories().values()) {
            for (ShopItem it : c.items()) if (it.key().equals(key)) return items.plainName(it);
            if (c.rotation() != null) for (ShopItem it : c.rotation().pool()) if (it.key().equals(key)) return items.plainName(it);
        }
        return key;
    }

    // ---------- pliki ----------

    /** Pierwszy start nowej wersji: stare pliki do old/, potem treść startowa w języku serwera. */
    private void prepareFiles() {
        File dir = getDataFolder();
        if (new File(dir, "shop.yml").exists()) return;
        File old = new File(dir, "old");
        boolean moved = false;
        for (String name : OLD_FILES) {
            File f = new File(dir, name);
            if (!f.exists()) continue;
            old.mkdirs();
            try {
                Files.move(f.toPath(), new File(old, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (IOException e) {
                getLogger().warning("Could not move old " + name + " to old/: " + e.getMessage());
            }
        }
        if (moved) getLogger().info("Moved the old shop files to old/ - the new shop starts with the default content.");

        String language = lang.language();
        if (getResource("defaults/" + language + "/shop.yml") == null) language = "en";
        copy("defaults/" + language + "/shop.yml", new File(dir, "shop.yml"));
        for (String id : DEFAULT_CATEGORIES) {
            copy("defaults/" + language + "/categories/" + id + ".yml", new File(dir, "categories/" + id + ".yml"));
        }
    }

    private void copy(String resource, File target) {
        try (InputStream in = getResource(resource)) {
            if (in == null) {
                getLogger().warning("Missing bundled " + resource);
                return;
            }
            target.getParentFile().mkdirs();
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            getLogger().warning("Could not write " + target.getName() + ": " + e.getMessage());
        }
    }

    private ShopConfig load() {
        Consumer<String> warn = getLogger()::warning;
        Predicate<String> material = m -> Material.matchMaterial(m) != null;
        ShopSettings settings = ShopConfigParser.parseSettings(
                YamlConfiguration.loadConfiguration(new File(getDataFolder(), "shop.yml")), material, warn);
        Map<String, Category> cats = new LinkedHashMap<>();
        File[] files = new File(getDataFolder(), "categories").listFiles((d, n) -> n.endsWith(".yml"));
        Map<String, File> sorted = new TreeMap<>();
        if (files != null) for (File f : files) sorted.put(f.getName().substring(0, f.getName().length() - 4), f);
        for (Map.Entry<String, File> e : sorted.entrySet()) {
            cats.put(e.getKey(), ShopConfigParser.parseCategory(e.getKey(), YamlConfiguration.loadConfiguration(e.getValue()), material, warn));
        }
        return ShopConfigParser.combine(settings, cats, warn);
    }
}
