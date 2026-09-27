package elo.mainplugins.shop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Krótka historia sprzedaży (history.yml): na każdy dzień ile sprzedano każdego przedmiotu i ile zarobił
 * każdy gracz. Do /@shop history <przedmiot> i /@shop top. Trzyma ostatnie N dni (shop.yml stats.history-days),
 * zbierana razem ze statystykami (stats.enabled). file = null - tylko w pamięci (testy).
 */
final class ShopHistory {

    /** Suma sprzedaży: sztuki i pieniądze. name = nick gracza (dla przedmiotów puste). */
    static final class Total {
        String name = "";
        long amount;
        double money;
    }

    record DayLine(LocalDate day, long amount, double money) {}

    record TopLine(String name, long amount, double money) {}

    private final File file;
    private final Consumer<String> warn;
    /** dzień -> (klucz przedmiotu -> suma), (uuid gracza -> suma). */
    private final TreeMap<LocalDate, Map<String, Total>> items = new TreeMap<>();
    private final TreeMap<LocalDate, Map<String, Total>> players = new TreeMap<>();
    private boolean dirty;

    ShopHistory(File file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
        load();
    }

    void record(LocalDate day, String itemKey, String playerId, String playerName, long amount, double money) {
        Total it = items.computeIfAbsent(day, d -> new HashMap<>()).computeIfAbsent(itemKey, k -> new Total());
        it.amount += amount;
        it.money += money;
        Total pl = players.computeIfAbsent(day, d -> new HashMap<>()).computeIfAbsent(playerId, k -> new Total());
        pl.name = playerName;
        pl.amount += amount;
        pl.money += money;
        dirty = true;
    }

    /** Usuwa dni starsze niż `keepDays` (licząc dzisiejszy). */
    void prune(LocalDate today, int keepDays) {
        LocalDate first = today.minusDays(keepDays - 1L);
        if (items.headMap(first).isEmpty() && players.headMap(first).isEmpty()) return;
        items.headMap(first).clear();
        players.headMap(first).clear();
        dirty = true;
    }

    /** Ostatnie `days` dni przedmiotu, od najnowszego; dni bez sprzedaży pominięte. */
    List<DayLine> itemHistory(String itemKey, LocalDate today, int days) {
        List<DayLine> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = today.minusDays(i);
            Total t = items.getOrDefault(d, Map.of()).get(itemKey);
            if (t != null && t.amount > 0) out.add(new DayLine(d, t.amount, t.money));
        }
        return out;
    }

    /** Gracze, którzy najwięcej zarobili na sprzedaży w ostatnich `days` dniach (1 = dziś). */
    List<TopLine> top(LocalDate today, int days, int limit) {
        Map<String, Total> sum = new HashMap<>();
        for (int i = 0; i < days; i++) {
            for (Map.Entry<String, Total> e : players.getOrDefault(today.minusDays(i), Map.of()).entrySet()) {
                Total s = sum.computeIfAbsent(e.getKey(), k -> new Total());
                if (s.name.isEmpty()) s.name = e.getValue().name;
                s.amount += e.getValue().amount;
                s.money += e.getValue().money;
            }
        }
        return sum.values().stream()
                .sorted(Comparator.comparingDouble((Total t) -> t.money).reversed())
                .limit(limit)
                .map(t -> new TopLine(t.name, t.amount, Math.round(t.money * 100) / 100.0))
                .toList();
    }

    // ---------- plik ----------

    private void load() {
        if (file == null || !file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String dayKey : y.getKeys(false)) {
            LocalDate day;
            try {
                day = LocalDate.parse(dayKey);
            } catch (Exception e) {
                continue;
            }
            readInto(y.getConfigurationSection(dayKey + ".items"), items.computeIfAbsent(day, d -> new HashMap<>()));
            readInto(y.getConfigurationSection(dayKey + ".players"), players.computeIfAbsent(day, d -> new HashMap<>()));
        }
    }

    private static void readInto(ConfigurationSection s, Map<String, Total> into) {
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            Total t = new Total();
            t.name = s.getString(k + ".name", "");
            t.amount = s.getLong(k + ".amount");
            t.money = s.getDouble(k + ".money");
            into.put(k, t);
        }
    }

    /** Zapis, gdy coś się zmieniło (co 10 minut i przy wyłączeniu serwera). */
    void save() {
        if (file == null || !dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        write(y, "items", items);
        write(y, "players", players);
        try {
            y.save(file);
            dirty = false;
        } catch (IOException e) {
            warn.accept("Could not save history.yml: " + e.getMessage());
        }
    }

    private static void write(YamlConfiguration y, String part, TreeMap<LocalDate, Map<String, Total>> data) {
        data.forEach((day, map) -> map.forEach((k, t) -> {
            String base = day + "." + part + "." + k.replace('.', '_');
            if (!t.name.isEmpty()) y.set(base + ".name", t.name);
            y.set(base + ".amount", t.amount);
            y.set(base + ".money", Math.round(t.money * 100) / 100.0);
        }));
    }
}
