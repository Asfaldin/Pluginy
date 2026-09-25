package elo.mainplugins.shop;

import elo.mainplugins.shop.model.Category;
import elo.mainplugins.shop.model.DynamicSettings;
import elo.mainplugins.shop.model.MenuScreen;
import elo.mainplugins.shop.model.Rotation;
import elo.mainplugins.shop.model.Rounding;
import elo.mainplugins.shop.model.ShopConfig;
import elo.mainplugins.shop.model.ShopItem;
import elo.mainplugins.shop.model.ShopSettings;
import elo.mainplugins.shop.model.SlotEntry;
import elo.mainplugins.shop.model.SlotRole;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Czyta shop.yml i categories/*.yml. Zły wpis = pominięty albo poprawiony z ostrzeżeniem, nigdy wyjątek. */
public final class ShopConfigParser {

    private ShopConfigParser() {}

    // ---------- shop.yml ----------

    public static ShopSettings parseSettings(ConfigurationSection root, Predicate<String> materialExists, Consumer<String> warn) {
        ShopSettings d = ShopSettings.defaults();

        List<String> order = new ArrayList<>();
        for (String id : root.getStringList("categories")) if (!order.contains(id)) order.add(id);

        Rounding rounding = d.rounding();
        String r = root.getString("price-rounding");
        if (r != null) {
            switch (r.trim().toLowerCase(Locale.ROOT)) {
                case "whole" -> rounding = Rounding.WHOLE;
                case "cents" -> rounding = Rounding.CENTS;
                default -> warn.accept("shop.yml price-rounding: '" + r + "' is not 'whole' or 'cents' - using cents.");
            }
        }

        DynamicSettings dd = DynamicSettings.defaults();
        ConfigurationSection dyn = root.getConfigurationSection("dynamic-prices");
        DynamicSettings dynamic = dd;
        if (dyn != null) {
            int cycle = dyn.getInt("cycle-minutes", dd.cycleMinutes());
            if (cycle < 1) {
                warn.accept("shop.yml dynamic-prices.cycle-minutes: must be at least 1 - using " + dd.cycleMinutes() + ".");
                cycle = dd.cycleMinutes();
            }
            double min = dyn.getDouble("min-multiplier", dd.minMultiplier());
            double max = dyn.getDouble("max-multiplier", dd.maxMultiplier());
            if (min <= 0 || min > 1 || max < 1) {
                warn.accept("shop.yml dynamic-prices: need 0 < min-multiplier <= 1 <= max-multiplier - using "
                        + dd.minMultiplier() + " and " + dd.maxMultiplier() + ".");
                min = dd.minMultiplier();
                max = dd.maxMultiplier();
            }
            int reset = dyn.getInt("reset-days", dd.resetDays());
            // 0 = automatyczny reset wyłączony (ceny same nie wracają do normy).
            if (reset < 0) {
                warn.accept("shop.yml dynamic-prices.reset-days: must be 0 (reset off) or more - using " + dd.resetDays() + ".");
                reset = dd.resetDays();
            }
            double share = dyn.getDouble("max-sell-share", dd.maxSellShare());
            if (share <= 0 || share > 1) {
                warn.accept("shop.yml dynamic-prices.max-sell-share: must be above 0 and at most 1 - using " + dd.maxSellShare() + ".");
                share = dd.maxSellShare();
            }
            dynamic = new DynamicSettings(dyn.getBoolean("enabled", dd.enabled()), cycle, min, max, reset, share,
                    dyn.getBoolean("announce-events", dd.announceEvents()),
                    dyn.getBoolean("announce-reset", dd.announceReset()),
                    parseTuning(dyn.getConfigurationSection("tuning"), warn));
        }

        Map<String, MenuScreen> menus = new LinkedHashMap<>();
        for (String screen : ShopSettings.SCREENS) {
            ConfigurationSection s = root.getConfigurationSection("menus." + screen);
            menus.put(screen, s == null ? d.menu(screen) : parseScreen("shop.yml menus." + screen, s, d.menu(screen), materialExists, warn));
        }

        Map<String, String> buttons = new LinkedHashMap<>(ShopSettings.defaultButtons());
        ConfigurationSection b = root.getConfigurationSection("menus.buttons");
        if (b != null) {
            for (String id : b.getKeys(false)) {
                if (!buttons.containsKey(id)) {
                    warn.accept("shop.yml menus.buttons." + id + ": unknown button - ignoring it.");
                    continue;
                }
                String m = material(b.getString(id), materialExists);
                if (m == null) warn.accept("shop.yml menus.buttons." + id + ": unknown material '" + b.getString(id) + "' - using " + buttons.get(id) + ".");
                else buttons.put(id, m);
            }
        }

        // category-page-sort: order (kolejność z pliku) | buy | sell - co widać, zanim gracz kliknie lejek.
        ShopSettings.CategorySort sort = d.categorySort();
        String sortRaw = root.getString("category-page-sort");
        if (sortRaw != null) {
            try {
                sort = ShopSettings.CategorySort.valueOf(sortRaw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warn.accept("shop.yml category-page-sort: '" + sortRaw + "' - use order, buy or sell - using " + sort.name().toLowerCase(java.util.Locale.ROOT) + ".");
            }
        }
        return new ShopSettings(List.copyOf(order), rounding, dynamic, root.getBoolean("stats.enabled", d.statsEnabled()),
                Map.copyOf(menus), Map.copyOf(buttons), sort, root.getBoolean("center-small-categories", d.centerSmallCategories()));
    }

    private static MenuScreen parseScreen(String where, ConfigurationSection s, MenuScreen def,
                                          Predicate<String> materialExists, Consumer<String> warn) {
        int size = s.getInt("size", def.size());
        if (size < 9 || size > 54 || size % 9 != 0) {
            warn.accept(where + ".size: must be 9, 18, 27, 36, 45 or 54 - using " + def.size() + ".");
            size = def.size();
        }
        if (!s.isList("layout")) return new MenuScreen(size, def.layout());
        List<SlotEntry> layout = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        List<?> raw = s.getList("layout", List.of());
        for (int i = 0; i < raw.size(); i++) {
            String at = where + ".layout[" + (i + 1) + "]";
            if (!(raw.get(i) instanceof Map<?, ?> e)) {
                warn.accept(at + ": not a {slot, role} entry - skipping it.");
                continue;
            }
            int slot = e.get("slot") instanceof Number n ? n.intValue() : -1;
            SlotRole role;
            try {
                role = SlotRole.valueOf(String.valueOf(e.get("role")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                warn.accept(at + ": unknown role '" + e.get("role") + "' - skipping it.");
                continue;
            }
            if (slot < 0 || slot >= size) {
                warn.accept(at + ": slot " + slot + " is outside the window (0-" + (size - 1) + ") - skipping it.");
                continue;
            }
            if (!used.add(slot)) {
                warn.accept(at + ": slot " + slot + " is used twice - skipping it.");
                continue;
            }
            int amount = e.get("amount") instanceof Number n ? n.intValue() : 0;
            if (role == SlotRole.AMOUNT_SLOT && amount < 1) {
                warn.accept(at + ": AMOUNT_SLOT needs 'amount' (how many pieces) - skipping it.");
                continue;
            }
            String material = null;
            if (e.get("material") != null) {
                material = material(String.valueOf(e.get("material")), materialExists);
                if (material == null) warn.accept(at + ": unknown material '" + e.get("material") + "' - using the background.");
            }
            layout.add(new SlotEntry(slot, role, material, amount));
        }
        return new MenuScreen(size, List.copyOf(layout));
    }

    // ---------- categories/<id>.yml ----------

    public static Category parseCategory(String id, ConfigurationSection root, Predicate<String> materialExists, Consumer<String> warn) {
        String file = "categories/" + id + ".yml";
        String name = root.getString("name", id);

        String iconMaterial = null;
        String iconCustom = null;
        Object icon = root.get("icon");
        if (icon instanceof ConfigurationSection s && s.getString("custom") != null) {
            iconCustom = s.getString("custom");
        } else if (icon instanceof Map<?, ?> m && m.get("custom") != null) {
            iconCustom = String.valueOf(m.get("custom"));
        } else {
            String raw = icon instanceof ConfigurationSection s ? s.getString("item") : icon instanceof String str ? str : null;
            iconMaterial = material(raw, materialExists);
            if (iconMaterial == null) {
                warn.accept(file + " icon: missing or unknown material - using CHEST.");
                iconMaterial = "CHEST";
            }
        }

        List<ShopItem> items = parseItems(root.getList("items", List.of()), file + " items", materialExists, warn);

        Rotation rotation = null;
        ConfigurationSection rot = root.getConfigurationSection("rotation");
        if (rot != null) {
            int show = rot.getInt("show", 5);
            if (show < 1) {
                warn.accept(file + " rotation.show: must be at least 1 - using 1.");
                show = 1;
            }
            int every = rot.getInt("every-days", 14);
            if (every < 1) {
                warn.accept(file + " rotation.every-days: must be at least 1 - using 14.");
                every = 14;
            }
            rotation = new Rotation(rot.getBoolean("enabled", true), show, every, rot.getBoolean("announce", true),
                    parseItems(rot.getList("pool", List.of()), file + " rotation.pool", materialExists, warn));
        }
        // Własny układ strony tej kategorii (size + layout jak w shop.yml menus.category-page); brak = wspólny.
        ConfigurationSection lay = root.getConfigurationSection("layout");
        MenuScreen layout = lay == null ? null
                : parseScreen(file + " layout", lay, ShopSettings.defaults().menu("category-page"), materialExists, warn);
        return new Category(id, name, iconMaterial, iconCustom, items, rotation, layout);
    }

    private static List<ShopItem> parseItems(List<?> raw, String where, Predicate<String> materialExists, Consumer<String> warn) {
        List<ShopItem> out = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String at = where + "[" + (i + 1) + "]";
            Map<?, ?> m = raw.get(i) instanceof Map<?, ?> map ? map
                    : raw.get(i) instanceof ConfigurationSection s ? s.getValues(false) : null;
            if (m == null) {
                warn.accept(at + ": not an item entry - skipping it.");
                continue;
            }
            ShopItem item = parseItem(m, at, materialExists, warn);
            if (item != null) out.add(item);
        }
        return List.copyOf(out);
    }

    private static ShopItem parseItem(Map<?, ?> m, String at, Predicate<String> materialExists, Consumer<String> warn) {
        String material = null;
        String custom = null;
        if (m.get("custom") != null) {
            custom = String.valueOf(m.get("custom"));
        } else {
            material = material(m.get("item") == null ? null : String.valueOf(m.get("item")), materialExists);
            if (material == null) {
                warn.accept(at + ": needs 'item' (a known material) or 'custom' - skipping it.");
                return null;
            }
        }
        Double buy = price(m.get("buy"), at + " buy", warn);
        Double sell = price(m.get("sell"), at + " sell", warn);
        if (Double.valueOf(-1).equals(buy) || Double.valueOf(-1).equals(sell)) return null;
        if (buy == null && sell == null) {
            warn.accept(at + ": has neither 'buy' nor 'sell' - skipping it.");
            return null;
        }
        int amount = count(m.get("amount"), at + " amount", warn);
        int sellAmount = count(m.get("sell-amount"), at + " sell-amount", warn);
        List<String> lore = new ArrayList<>();
        if (m.get("lore") instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
        // Brak wpisu "dynamic" = ceny dynamiczne działają (tak było, zanim ta opcja powstała).
        boolean dynamic = !(m.get("dynamic") instanceof Boolean b) || b;
        return new ShopItem(material, custom, buy, sell, amount, sellAmount,
                m.get("name") == null ? null : String.valueOf(m.get("name")), Collections.unmodifiableList(lore),
                m.get("instrument") == null ? null : String.valueOf(m.get("instrument")), dynamic);
    }


    /**
     * Strojenie cyklu cen dynamicznych. Zła wartość = ostrzeżenie i wartość domyślna, jak wszędzie
     * w tym pliku - serwer ma wstać nawet z popsutym configiem.
     */
    private static DynamicSettings.Tuning parseTuning(ConfigurationSection t, Consumer<String> warn) {
        DynamicSettings.Tuning d = DynamicSettings.Tuning.defaults();
        if (t == null) return d;
        return new DynamicSettings.Tuning(
                part(t, "max-drop-per-cycle", d.maxDropPerCycle(), warn),
                above(t, "drop-at-top", d.dropAtTop(), 1.0, warn),
                part(t, "recover-from-below", d.recoverFromBelow(), warn),
                part(t, "rise-per-cycle", d.risePerCycle(), warn),
                part(t, "quiet-threshold", d.quietThreshold(), warn),
                atLeast(t, "cycles-to-rise", d.cyclesToRise(), 1, warn),
                atLeast(t, "cycles-frozen", d.cyclesFrozen(), 0, warn),
                part(t, "norm-learn-rate", d.normLearnRate(), warn));
    }

    /** Ułamek: musi być powyżej 0 i najwyżej 1. */
    private static double part(ConfigurationSection t, String key, double fallback, Consumer<String> warn) {
        double v = t.getDouble(key, fallback);
        if (v > 0 && v <= 1) return v;
        warn.accept("shop.yml dynamic-prices.tuning." + key + ": must be above 0 and at most 1 - using " + fallback + ".");
        return fallback;
    }

    private static double above(ConfigurationSection t, String key, double fallback, double min, Consumer<String> warn) {
        double v = t.getDouble(key, fallback);
        if (v >= min) return v;
        warn.accept("shop.yml dynamic-prices.tuning." + key + ": must be at least " + min + " - using " + fallback + ".");
        return fallback;
    }

    private static int atLeast(ConfigurationSection t, String key, int fallback, int min, Consumer<String> warn) {
        int v = t.getInt(key, fallback);
        if (v >= min) return v;
        warn.accept("shop.yml dynamic-prices.tuning." + key + ": must be at least " + min + " - using " + fallback + ".");
        return fallback;
    }

    /** null = brak ceny; -1 = zła cena (pozycja do pominięcia, ostrzeżenie już wysłane). */
    private static Double price(Object raw, String at, Consumer<String> warn) {
        if (raw == null) return null;
        if (!(raw instanceof Number n) || n.doubleValue() < 0) {
            warn.accept(at + ": must be a number 0 or higher - skipping the item.");
            return -1.0;
        }
        return Math.round(n.doubleValue() * 100) / 100.0;
    }

    private static int count(Object raw, String at, Consumer<String> warn) {
        if (raw == null) return 1;
        if (raw instanceof Number n && n.intValue() >= 1) return n.intValue();
        warn.accept(at + ": must be 1 or more - using 1.");
        return 1;
    }

    private static String material(String raw, Predicate<String> materialExists) {
        if (raw == null) return null;
        String m = raw.trim().toUpperCase(Locale.ROOT);
        return materialExists.test(m) ? m : null;
    }

    // ---------- całość ----------

    /** Kolejność kategorii: najpierw z shop.yml (znane), potem reszta; ostrzega o brakach. */
    public static ShopConfig combine(ShopSettings s, Map<String, Category> cats, Consumer<String> warn) {
        List<String> order = new ArrayList<>();
        for (String id : s.categoryOrder()) {
            if (cats.containsKey(id)) order.add(id);
            else warn.accept("shop.yml categories: no file categories/" + id + ".yml - ignoring '" + id + "'.");
        }
        Map<String, Category> sorted = new LinkedHashMap<>();
        for (String id : order) sorted.put(id, cats.get(id));
        for (Map.Entry<String, Category> e : cats.entrySet()) {
            if (sorted.containsKey(e.getKey())) continue;
            warn.accept("categories/" + e.getKey() + ".yml: not in shop.yml 'categories' - it has no icon in the menu (selling still works) - '" + e.getKey() + "'.");
            sorted.put(e.getKey(), e.getValue());
        }
        ShopSettings fixed = new ShopSettings(List.copyOf(order), s.rounding(), s.dynamic(), s.statsEnabled(), s.menus(), s.buttonMaterials(),
                s.categorySort(), s.centerSmallCategories());
        return new ShopConfig(fixed, Collections.unmodifiableMap(sorted));
    }
}
