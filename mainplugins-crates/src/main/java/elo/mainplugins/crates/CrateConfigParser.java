package elo.mainplugins.crates;

import elo.mainplugins.core.api.Reward;
import elo.mainplugins.crates.model.CrateConfig;
import elo.mainplugins.crates.model.CrateDef;
import elo.mainplugins.crates.model.ItemRef;
import elo.mainplugins.crates.model.KeyDef;
import elo.mainplugins.crates.model.Prize;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Czyta crates.yml. Złe klucze/skrzynki/wygrane są pomijane z ostrzeżeniem - nigdy wyjątek. */
public final class CrateConfigParser {

    private CrateConfigParser() {}

    public static CrateConfig parse(ConfigurationSection root,
                                    BiFunction<List<?>, String, List<Reward>> rewards,
                                    Predicate<String> materialExists,
                                    Consumer<String> warn) {
        Map<String, KeyDef> keys = new LinkedHashMap<>();
        ConfigurationSection keysSec = root.getConfigurationSection("keys");
        if (keysSec != null) {
            for (String id : keysSec.getKeys(false)) {
                ConfigurationSection s = keysSec.getConfigurationSection(id);
                String where = "crates.yml keys." + id;
                ItemRef item = s == null ? null : validItem(ItemRef.from(s.get("item")), materialExists);
                if (item == null) {
                    warn.accept(where + ": missing or unknown 'item' - skipping key.");
                    continue;
                }
                keys.put(id, new KeyDef(id, s.getString("name", id), List.copyOf(s.getStringList("lore")), item));
            }
        }

        Map<String, CrateDef> crates = new LinkedHashMap<>();
        ConfigurationSection cratesSec = root.getConfigurationSection("crates");
        if (cratesSec != null) {
            for (String id : cratesSec.getKeys(false)) {
                ConfigurationSection s = cratesSec.getConfigurationSection(id);
                String where = "crates.yml crates." + id;
                if (s == null) {
                    warn.accept(where + ": not a section - skipping crate.");
                    continue;
                }
                ItemRef item = validItem(ItemRef.from(s.get("item")), materialExists);
                if (item == null) {
                    warn.accept(where + ": missing or unknown 'item' - skipping crate.");
                    continue;
                }
                List<String> crateKeys = new ArrayList<>();
                for (String k : s.getStringList("keys")) {
                    if (keys.containsKey(k)) crateKeys.add(k);
                    else warn.accept(where + ": unknown key '" + k + "' - ignoring it.");
                }
                if (crateKeys.isEmpty()) {
                    warn.accept(where + ": no valid key opens this crate - skipping crate.");
                    continue;
                }
                List<Prize> prizes = parsePrizes(s.getList("prizes"), where, rewards, materialExists, warn);
                if (prizes.isEmpty()) {
                    warn.accept(where + ": no valid prizes - skipping crate.");
                    continue;
                }
                crates.put(id, new CrateDef(id, s.getString("name", id), List.copyOf(s.getStringList("lore")),
                        item, List.copyOf(crateKeys), List.copyOf(prizes)));
            }
        }
        return new CrateConfig(Collections.unmodifiableMap(keys), Collections.unmodifiableMap(crates));
    }

    private static List<Prize> parsePrizes(List<?> raw, String where,
                                           BiFunction<List<?>, String, List<Reward>> rewards,
                                           Predicate<String> materialExists, Consumer<String> warn) {
        List<Prize> out = new ArrayList<>();
        if (raw == null) return out;
        for (int i = 0; i < raw.size(); i++) {
            String at = where + ".prizes[" + (i + 1) + "]";
            if (!(raw.get(i) instanceof Map<?, ?> m)) {
                warn.accept(at + ": not a map - skipping prize.");
                continue;
            }
            ItemRef icon = validItem(ItemRef.from(m.get("icon")), materialExists);
            if (icon == null) {
                warn.accept(at + ": missing or unknown 'icon' - skipping prize.");
                continue;
            }
            int weight = m.get("weight") instanceof Number n ? n.intValue() : 0;
            if (weight < 1) {
                warn.accept(at + ": 'weight' must be 1 or more - skipping prize.");
                continue;
            }
            List<Reward> list = m.get("rewards") instanceof List<?> l ? rewards.apply(l, at + ".rewards") : List.of();
            if (list.isEmpty()) {
                warn.accept(at + ": no valid rewards - skipping prize.");
                continue;
            }
            String name = m.get("name") != null ? String.valueOf(m.get("name")) : "Prize";
            out.add(new Prize(name, icon, weight, Boolean.TRUE.equals(m.get("announce")), List.copyOf(list)));
        }
        return out;
    }

    /** Zwykły materiał musi istnieć; custom item sprawdzany dopiero przy tworzeniu przedmiotu. */
    private static ItemRef validItem(ItemRef ref, Predicate<String> materialExists) {
        if (ref == null) return null;
        if (!ref.isCustom() && !materialExists.test(ref.material())) return null;
        return ref;
    }
}
