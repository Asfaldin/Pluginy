package elo.mainplugins.market;

import elo.mainplugins.market.model.ButtonDef;
import elo.mainplugins.market.model.MarketSettings;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashSet;
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

        Map<String, ButtonDef> buttons = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        Map<String, ButtonDef> defaults = MarketSettings.defaultButtons();
        for (String id : MarketSettings.BUTTON_IDS) {
            String where = "menu.buttons." + id;
            ButtonDef def = defaults.get(id);
            int slot = root.getInt(where + ".slot", def.slot());
            String mat = material(root.getString(where + ".material"), def.material(), where + ".material", materialExists, warn);
            if (slot < 0 || slot > 53) {
                warn.accept(FILE + " " + where + ".slot: must be 0-53 - button hidden.");
                continue;
            }
            if (MarketSettings.OFFER_SLOTS.contains(slot)) {
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
                title, background, Map.copyOf(buttons));
    }

    private static String material(String raw, String fallback, String where, Predicate<String> materialExists, Consumer<String> warn) {
        if (raw == null) return fallback;
        String m = raw.trim().toUpperCase(Locale.ROOT);
        if (materialExists.test(m)) return m;
        warn.accept(FILE + " " + where + ": unknown material '" + raw + "' - using " + fallback + ".");
        return fallback;
    }
}
