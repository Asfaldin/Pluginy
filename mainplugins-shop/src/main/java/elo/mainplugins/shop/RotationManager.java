package elo.mainplugins.shop;

import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.shop.model.Category;
import elo.mainplugins.shop.model.Rotation;
import elo.mainplugins.shop.model.ShopConfig;
import elo.mainplugins.shop.model.ShopItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Rotacja kategorii z "rotation:" - co every-days losuje show pozycji z pool.
 * Stan w rotation.yml: <kategoria>.{picked: [numery z puli], next-at: ms, cooldown: {numer: ile rotacji}}.
 */
public final class RotationManager {

    /** Ile rotacji pozycja "odpoczywa" po byciu w ofercie. */
    private static final int REST = 5;
    private static final long DAY_MS = 86_400_000L;

    private record State(List<Integer> picked, long nextAt, Map<Integer, Integer> cooldown) {}

    private final Plugin plugin;
    private final LangService lang;
    private final ShopItems items;
    private final Supplier<ShopConfig> config;
    private final YamlConfiguration yml;
    private final AsyncConfigSaver saver;
    private final Map<String, State> states = new HashMap<>();
    private final Random random = new Random();

    public RotationManager(Plugin plugin, LangService lang, ShopItems items, Supplier<ShopConfig> config) {
        this.plugin = plugin;
        this.lang = lang;
        this.items = items;
        this.config = config;
        File file = new File(plugin.getDataFolder(), "rotation.yml");
        this.yml = YamlConfiguration.loadConfiguration(file);
        this.saver = new AsyncConfigSaver(plugin, yml, file, 30);
        for (String cat : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(cat);
            if (s == null) continue;
            Map<Integer, Integer> cd = new HashMap<>();
            ConfigurationSection c = s.getConfigurationSection("cooldown");
            if (c != null) {
                for (String k : c.getKeys(false)) {
                    try {
                        cd.put(Integer.parseInt(k), c.getInt(k));
                    } catch (NumberFormatException ignored) {
                        // zły wpis - pomijamy
                    }
                }
            }
            states.put(cat, new State(new ArrayList<>(s.getIntegerList("picked")), s.getLong("next-at"), cd));
        }
    }

    /** Sprawdza wszystkie kategorie z rotacją - losuje, gdy minął czas albo pula się zmieniła. Woła się co kilka minut i po reloadzie. */
    public void check() {
        long now = System.currentTimeMillis();
        for (Category c : config.get().categories().values()) {
            Rotation r = c.rotation();
            if (r == null || !r.enabled() || r.pool().isEmpty()) continue;
            State s = states.get(c.id());
            boolean broken = s != null && s.picked().stream().anyMatch(i -> i >= r.pool().size());
            if (s == null || now >= s.nextAt() || broken) roll(c, now);
        }
    }

    private void roll(Category c, long now) {
        Rotation r = c.rotation();
        State old = states.get(c.id());
        Map<Integer, Integer> cooldown = new HashMap<>(old == null ? Map.of() : old.cooldown());
        cooldown.keySet().removeIf(i -> i >= r.pool().size());
        List<Integer> picked = RotationRules.pick(r.pool().size(), r.show(), cooldown, random);
        State s = new State(picked, now + r.everyDays() * DAY_MS, RotationRules.nextCooldown(cooldown, picked, REST));
        states.put(c.id(), s);
        yml.set(c.id() + ".picked", picked);
        yml.set(c.id() + ".next-at", s.nextAt());
        Map<String, Object> cd = new LinkedHashMap<>();
        s.cooldown().forEach((k, v) -> cd.put(String.valueOf(k), v));
        yml.set(c.id() + ".cooldown", cd.isEmpty() ? null : cd);
        saver.oznaczZmiane();
        plugin.getLogger().info("Shop rotation: new offer in category '" + c.id() + "' (" + picked.size() + " items).");
        if (r.announce()) announce(c, active(c));
    }

    /** Ogłoszenie na czacie po wylosowaniu nowej oferty (rotation.announce: false wyłącza). Teksty w lang/. */
    private void announce(Category c, List<ShopItem> offer) {
        if (offer.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) return;
        Map<String, String> head = Map.of("category", c.name(), "days", String.valueOf(daysLeft(c.id())));
        for (var p : Bukkit.getOnlinePlayers()) {
            lang.send(p, plugin, "rotation.broadcast-header", head);
            for (ShopItem it : offer) {
                Map<String, String> ph = new LinkedHashMap<>();
                ph.put("price", ShopManager.money(it.buyable() ? it.buy() : it.sellable() ? it.sell() : 0.0));
                ph.put("amount", String.valueOf(it.buyable() ? it.amount() : it.sellAmount()));
                Component line = lang.msg(plugin, "rotation.broadcast-item", ph).replaceText(
                        TextReplacementConfig.builder().matchLiteral("{item}").replacement(items.name(it)).build());
                p.sendMessage(line);
            }
            lang.send(p, plugin, "rotation.broadcast-footer", head);
        }
    }

    /** Wymusza nowe losowanie (categoryId = null -> wszystkie kategorie z rotacją). Zwraca, ile kategorii przelosowano. */
    public int force(String categoryId) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (Category c : config.get().categories().values()) {
            if (c.rotation() == null || !c.rotation().enabled() || c.rotation().pool().isEmpty()) continue;
            if (categoryId != null && !categoryId.equalsIgnoreCase(c.id())) continue;
            roll(c, now);
            n++;
        }
        return n;
    }

    /** Aktualnie wylosowane pozycje kategorii (pusta lista, gdy nie rotuje). */
    public List<ShopItem> active(Category c) {
        Rotation r = c.rotation();
        State s = states.get(c.id());
        if (r == null || !r.enabled() || s == null) return List.of();
        List<ShopItem> out = new ArrayList<>();
        for (int i : s.picked()) if (i >= 0 && i < r.pool().size()) out.add(r.pool().get(i));
        return out;
    }

    public Map<String, List<ShopItem>> allActive() {
        Map<String, List<ShopItem>> out = new HashMap<>();
        for (Category c : config.get().categories().values()) {
            List<ShopItem> a = active(c);
            if (!a.isEmpty()) out.put(c.id(), a);
        }
        return out;
    }

    public int daysLeft(String categoryId) {
        State s = states.get(categoryId);
        if (s == null) return 0;
        return (int) Math.max(0, (s.nextAt() - System.currentTimeMillis() + DAY_MS - 1) / DAY_MS);
    }

    public int onCooldown(String categoryId) {
        State s = states.get(categoryId);
        return s == null ? 0 : s.cooldown().size();
    }

    public void close() {
        saver.zamknij();
    }
}
