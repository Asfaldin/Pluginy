package elo.mainplugins.cosmetics;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Jeden kosmetyk z cosmetics.yml. type: HAT (item na głowie), TRAIL (cząsteczki za graczem),
 * PET (mob chodzący za graczem). value = materiał / id custom itemu (HAT), nazwa cząsteczki
 * (TRAIL) albo typ moba (PET). free = dostępny dla wszystkich; inaczej permisja do sprzedania.
 */
public record Cosmetic(String id, Type type, String name, String icon, String value, boolean custom, boolean free) {

    public enum Type { HAT, TRAIL, PET }

    public String permission() {
        return "mainplugins.cosmetics." + id;
    }

    /** Czyta listy hats/trails/pets. Złe wpisy pomijane z ostrzeżeniem. */
    public static List<Cosmetic> parseAll(ConfigurationSection root, Consumer<String> warn) {
        List<Cosmetic> out = new ArrayList<>();
        for (Type t : Type.values()) {
            String section = switch (t) {
                case HAT -> "hats";
                case TRAIL -> "trails";
                case PET -> "pets";
            };
            List<?> list = root.getList(section);
            if (list == null) continue;
            int i = 0;
            for (Object o : list) {
                i++;
                if (!(o instanceof Map<?, ?> m) || !(m.get("id") instanceof String id) || id.isBlank()) {
                    warn.accept("cosmetics.yml " + section + "[" + i + "]: needs at least an id - skipping.");
                    continue;
                }
                String key = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
                if (out.stream().anyMatch(c -> c.id.equals(key))) {
                    warn.accept("cosmetics.yml: duplicate id '" + key + "' - skipping.");
                    continue;
                }
                String value = str(m, switch (t) {
                    case HAT -> "item";
                    case TRAIL -> "particle";
                    case PET -> "mob";
                });
                boolean custom = false;
                if (t == Type.HAT && value.isEmpty() && m.get("custom") instanceof String cu) {
                    value = cu;
                    custom = true;
                }
                if (value.isEmpty()) {
                    warn.accept("cosmetics.yml " + section + " '" + key + "': missing " + (t == Type.HAT ? "item/custom" : t == Type.TRAIL ? "particle" : "mob") + " - skipping.");
                    continue;
                }
                String icon = str(m, "icon");
                if (icon.isEmpty()) icon = t == Type.HAT && !custom ? value : t == Type.TRAIL ? "BLAZE_POWDER" : t == Type.PET ? "LEAD" : "LEATHER_HELMET";
                out.add(new Cosmetic(key, t, str(m, "name").isEmpty() ? key : str(m, "name"), icon.toUpperCase(Locale.ROOT),
                        t == Type.HAT && custom ? value : value.toUpperCase(Locale.ROOT), custom, Boolean.TRUE.equals(m.get("free"))));
            }
        }
        return out;
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
