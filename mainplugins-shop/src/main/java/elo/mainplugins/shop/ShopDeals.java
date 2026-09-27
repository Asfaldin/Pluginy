package elo.mainplugins.shop;

import elo.mainplugins.shop.model.ShopConfig;
import elo.mainplugins.shop.model.ShopExtras;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Cena dla konkretnego gracza: promocje na kupno (/@shop sale, zapisane w sales.yml - przeżywają restart)
 * i premie rang z shop.yml (rank-bonuses). Rangę daje uprawnienie mainplugins.shop.rank.<nazwa> - dowolny
 * plugin rang/uprawnień, sklep nie zależy od żadnego. Promocja i rabat rangi się sumują.
 */
final class ShopDeals {

    static final String RANK_PERMISSION = "mainplugins.shop.rank.";

    private final Plugin plugin;
    private final Supplier<ShopConfig> config;
    private final File file;
    private final Map<String, ShopRules.Sale> sales = new LinkedHashMap<>();

    ShopDeals(Plugin plugin, Supplier<ShopConfig> config) {
        this.plugin = plugin;
        this.config = config;
        this.file = new File(plugin.getDataFolder(), "sales.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String target : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(target);
            if (s == null) continue;
            double pct = s.getDouble("percent", 0);
            if (pct <= 0 || pct >= 100) continue;
            sales.put(target, new ShopRules.Sale(pct, s.getLong("until", 0)));
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        sales.forEach((target, s) -> {
            y.set(target + ".percent", s.percent());
            y.set(target + ".until", s.until());
        });
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save sales.yml: " + e.getMessage());
        }
    }

    // ---------- promocje ----------

    void start(String target, double percent, long until) {
        sales.put(target, new ShopRules.Sale(percent, until));
        save();
    }

    boolean stop(String target) {
        boolean had = sales.remove(target) != null;
        if (had) save();
        return had;
    }

    /** Kończy wszystkie promocje; zwraca, ile ich było. */
    int stopAll() {
        int n = sales.size();
        sales.clear();
        if (n > 0) save();
        return n;
    }

    /** Promocje, którym minął czas - usunięte; zwraca ich cele (do ogłoszenia końca). */
    List<String> expired() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        sales.entrySet().removeIf(e -> {
            boolean gone = e.getValue().until() > 0 && e.getValue().until() <= now;
            if (gone) out.add(e.getKey());
            return gone;
        });
        if (!out.isEmpty()) save();
        return out;
    }

    /** Trwające promocje (cel -> promocja), w kolejności dodania. */
    Map<String, ShopRules.Sale> active() {
        return Map.copyOf(sales);
    }

    double salePercent(String itemKey, String categoryId) {
        return ShopRules.salePercent(sales, itemKey, categoryId, System.currentTimeMillis());
    }

    /** Ile zostało promocji na przedmiot (ta, która go teraz obejmuje); null = bez końca albo brak. */
    Long saleTimeLeft(String itemKey, String categoryId) {
        long now = System.currentTimeMillis();
        for (String k : new String[]{ShopRules.saleKeyItem(itemKey), categoryId == null ? null : ShopRules.saleKeyCategory(categoryId), ShopRules.SALE_ALL}) {
            if (k == null) continue;
            ShopRules.Sale s = sales.get(k);
            if (s != null && (s.until() == 0 || s.until() > now)) return s.until() == 0 ? null : s.until() - now;
        }
        return null;
    }

    // ---------- rangi ----------

    private Map<String, ShopExtras.RankBonus> ranks() {
        return config.get().settings().extras().rankBonuses();
    }

    /** Rangi gracza - tylko nadane wprost (op nie dostaje wszystkich premii naraz). */
    private boolean has(Player p, String rank) {
        String node = RANK_PERMISSION + rank;
        return p.isPermissionSet(node) && p.hasPermission(node);
    }

    double rankDiscount(Player p) {
        if (p == null) return 0;
        List<Double> v = new ArrayList<>();
        ranks().forEach((rank, b) -> {
            if (has(p, rank)) v.add(b.buyDiscount());
        });
        return ShopRules.bestBonus(v);
    }

    double rankSellBonus(Player p) {
        if (p == null) return 0;
        List<Double> v = new ArrayList<>();
        ranks().forEach((rank, b) -> {
            if (has(p, rank)) v.add(b.sellBonus());
        });
        return ShopRules.bestBonus(v);
    }

    /** Mnożnik ceny kupna dla gracza: promocja na przedmiot/kategorię/sklep i rabat rangi. */
    double buyFactor(Player p, String itemKey, String categoryId) {
        return ShopRules.buyFactor(salePercent(itemKey, categoryId), rankDiscount(p));
    }

    double sellFactor(Player p) {
        return ShopRules.sellFactor(rankSellBonus(p));
    }
}
