package elo.mainplugins.core.customitem;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/** Domyślne itemy pluginu (z jego jara): które brakują w katalogu i jak je dopisać do pliku serwera. */
public final class DefaultItemMerger {

    private DefaultItemMerger() {}

    public static List<String> missingIds(ConfigurationSection defaultsRoot, ItemCatalog catalog) {
        ConfigurationSection items = defaultsRoot.getConfigurationSection("items");
        if (items == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            if (!catalog.contains(id)) out.add(id);
        }
        return out;
    }

    public static void copyEntries(ConfigurationSection defaultsRoot, ConfigurationSection target, List<String> ids) {
        for (String id : ids) {
            ConfigurationSection src = defaultsRoot.getConfigurationSection("items." + id);
            if (src != null) copySection(src, target.createSection("items." + id));
        }
    }

    private static void copySection(ConfigurationSection from, ConfigurationSection to) {
        for (String key : from.getKeys(false)) {
            if (from.isConfigurationSection(key)) copySection(from.getConfigurationSection(key), to.createSection(key));
            else to.set(key, from.get(key));
        }
    }
}
