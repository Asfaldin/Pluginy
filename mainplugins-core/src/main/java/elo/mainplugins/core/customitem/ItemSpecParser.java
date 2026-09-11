package elo.mainplugins.core.customitem;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Czyta sekcję "items" jednego pliku katalogu. Złe wpisy są pomijane z ostrzeżeniem, nigdy wyjątek. */
public final class ItemSpecParser {

    private ItemSpecParser() {}

    public static List<ItemSpec> parseFile(ConfigurationSection root, String fileName, Consumer<String> warn) {
        List<ItemSpec> out = new ArrayList<>();
        ConfigurationSection items = root.getConfigurationSection("items");
        if (items == null) {
            warn.accept(fileName + ": no 'items' section - skipping file.");
            return out;
        }
        for (String id : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(id);
            if (s == null) {
                warn.accept(fileName + ": '" + id + "' is not a section - skipping.");
                continue;
            }
            String material = s.getString("material");
            if (material == null || material.isBlank()) {
                warn.accept(fileName + ": '" + id + "' has no material - skipping.");
                continue;
            }
            Map<String, Integer> enchants = new LinkedHashMap<>();
            ConfigurationSection e = s.getConfigurationSection("enchants");
            if (e != null) {
                for (String name : e.getKeys(false)) {
                    int level = e.getInt(name, 0);
                    if (level < 1) {
                        warn.accept(fileName + ": '" + id + "' enchant '" + name + "' needs a level >= 1 - skipping enchant.");
                        continue;
                    }
                    enchants.put(name.toLowerCase(Locale.ROOT), level);
                }
            }
            out.add(new ItemSpec(id, material, s.getString("name"), List.copyOf(s.getStringList("lore")),
                    s.getString("model"), s.getBoolean("glint", false), Collections.unmodifiableMap(enchants),
                    s.getBoolean("unbreakable", false), fileName));
        }
        return out;
    }
}
