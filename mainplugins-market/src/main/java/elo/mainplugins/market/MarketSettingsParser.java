package elo.mainplugins.market;

import elo.mainplugins.market.model.ButtonDef;
import elo.mainplugins.market.model.MarketSettings;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Czyta market.yml. Zły wpis = wartość domyślna + ostrzeżenie, nigdy wyjątek. */
public final class MarketSettingsParser {

    private static final String FILE = "market.yml";

    private MarketSettingsParser() {}

    public static MarketSettings parse(ConfigurationSection root, Predicate<String> materialExists, Consumer<String> warn) {
        MarketSettings d = MarketSettings.defaults();

        int limit = root.getInt("limits.default", d.defaultLimit());
        if (limit < 1) {
            warn.accept(FILE + " limits.default: must be at least 1 - using " + d.defaultLimit() + ".");
            limit = d.defaultLimit();
        }

        long min = root.getLong("min-price", d.minPrice());
        long max = root.getLong("max-price", d.maxPrice());
        if (min < 1 || max < min) {
            warn.accept(FILE + " min-price/max-price: need 1 <= min-price <= max-price - using " + d.minPrice() + "-" + d.maxPrice() + ".");
            min = d.minPrice();
            max = d.maxPrice();
        }

        int expire = root.getInt("expire-days", d.expireDays());
        if (expire < 0) {
            warn.accept(FILE + " expire-days: cannot be negative - using 0 (never).");
            expire = 0;
        }

        // Podatek: tax.enabled + tax.percent. Stare pliki mialy samo tax-percent (0 = brak) - czytamy oba.
        ConfigurationSection taxSection = root.getConfigurationSection("tax");
        boolean taxOn = taxSection != null ? taxSection.getBoolean("enabled", d.taxEnabled())
                : root.getInt("tax-percent", 0) > 0;
        int tax = taxSection != null ? taxSection.getInt("percent", d.taxPercent())
                : root.getInt("tax-percent", d.taxPercent());
        if (tax < 0 || tax > 100) {
            int fixed = Math.max(0, Math.min(100, tax));
            warn.accept(FILE + " tax.percent: must be 0-100 - using " + fixed + ".");
            tax = fixed;
        }

        String title = root.getString("menu.title", d.title());
        String background = material(root.getString("menu.background"), d.background(), "menu.background", materialExists, warn);

        int size = root.getInt("menu.size", d.size());
        if (size < 9 || size > 54 || size % 9 != 0) {
            warn.accept(FILE + " menu.size: must be 9, 18, 27, 36, 45 or 54 - using " + d.size() + ".");
            size = d.size();
        }
        // Pola na oferty: w oknie, bez powtórek. Brak listy = domyślny blok 7x3 (o ile mieści się w oknie).
        List<Integer> offerSlots = new ArrayList<>();
        if (root.isList("menu.offer-slots")) {
            Set<Integer> seen = new HashSet<>();
            for (Object o : root.getList("menu.offer-slots", List.of())) {
                if (!(o instanceof Number n) || n.intValue() < 0 || n.intValue() >= size) {
                    warn.accept(FILE + " menu.offer-slots: '" + o + "' is not a slot 0-" + (size - 1) + " - skipping it.");
                    continue;
                }
                if (seen.add(n.intValue())) offerSlots.add(n.intValue());
            }
        } else {
            for (int s : d.offerSlots()) if (s < size) offerSlots.add(s);
        }
        if (offerSlots.isEmpty()) {
            warn.accept(FILE + " menu.offer-slots: no slots for offers - using the first row of the window.");
            for (int s = 0; s < Math.min(9, size); s++) offerSlots.add(s);
        }

        Map<String, ButtonDef> buttons = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        Map<String, ButtonDef> defaults = MarketSettings.defaultButtons();
        for (String id : MarketSettings.BUTTON_IDS) {
            String where = "menu.buttons." + id;
            ButtonDef def = defaults.get(id);
            int slot = root.getInt(where + ".slot", def.slot());
            String mat = material(root.getString(where + ".material"), def.material(), where + ".material", materialExists, warn);
            if (slot < 0 || slot >= size) {
                warn.accept(FILE + " " + where + ".slot: must be 0-" + (size - 1) + " - button hidden.");
                continue;
            }
            if (offerSlots.contains(slot)) {
                warn.accept(FILE + " " + where + ".slot: " + slot + " is used by offers - button hidden.");
                continue;
            }
            if (!used.add(slot)) {
                warn.accept(FILE + " " + where + ".slot: " + slot + " is already used by another button - button hidden.");
                continue;
            }
            buttons.put(id, new ButtonDef(slot, mat));
        }

        return new MarketSettings(limit, min, max, expire, root.getBoolean("mailbox", d.mailbox()), taxOn, tax,
                title, background, Map.copyOf(buttons), size, List.copyOf(offerSlots), rankLimits(root, warn));
    }

    /** limits.ranks: {vip: 15} - ile ofert naraz dla rangi; 1-10000, inaczej pominięta. */
    private static Map<String, Integer> rankLimits(ConfigurationSection root, Consumer<String> warn) {
        Map<String, Integer> out = new LinkedHashMap<>();
        ConfigurationSection r = root.getConfigurationSection("limits.ranks");
        if (r == null) return Map.of();
        for (String rank : r.getKeys(false)) {
            int n = r.getInt(rank, -1);
            if (n < 1 || n > 10000) {
                warn.accept(FILE + " limits.ranks." + rank + ": must be 1-10000 - skipping it.");
                continue;
            }
            out.put(rank.toLowerCase(Locale.ROOT), n);
        }
        return Map.copyOf(out);
    }

    private static String material(String raw, String fallback, String where, Predicate<String> materialExists, Consumer<String> warn) {
        if (raw == null) return fallback;
        String m = raw.trim().toUpperCase(Locale.ROOT);
        if (materialExists.test(m)) return m;
        warn.accept(FILE + " " + where + ": unknown material '" + raw + "' - using " + fallback + ".");
        return fallback;
    }
}
