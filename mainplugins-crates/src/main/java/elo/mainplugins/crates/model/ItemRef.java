package elo.mainplugins.crates.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

/** Odwołanie do przedmiotu w crates.yml: { item: MATERIAL } albo { custom: ID }, opcjonalnie amount. */
public record ItemRef(String material, String customId, int amount) {

    public boolean isCustom() {
        return customId != null;
    }

    /** Mapa albo sekcja YAML; null, gdy nie ma ani "item", ani "custom". */
    public static ItemRef from(Object raw) {
        Map<?, ?> map;
        if (raw instanceof ConfigurationSection s) map = s.getValues(false);
        else if (raw instanceof Map<?, ?> m) map = m;
        else return null;
        Object custom = map.get("custom");
        Object item = map.get("item");
        int amount = map.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        if (custom != null) return new ItemRef(null, String.valueOf(custom), amount);
        if (item != null) return new ItemRef(String.valueOf(item).toUpperCase(), null, amount);
        return null;
    }
}
