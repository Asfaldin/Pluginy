package elo.mainplugins.core.lang;

import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Czysta logika tłumaczeń (bez serwera): lista warstw klucz→tekst w kolejności
 * ważności (np. plik serwera w wybranym języku, domyślny z jara, potem angielski).
 * Brak klucza we wszystkich warstwach = zwracamy sam klucz + jedno ostrzeżenie w logu.
 */
public final class MessageCatalog {

    private final List<Map<String, String>> layers;
    private final Consumer<String> warn;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public MessageCatalog(List<Map<String, String>> layers, Consumer<String> warn) {
        this.layers = List.copyOf(layers);
        this.warn = warn;
    }

    public String resolve(String key, Map<String, String> placeholders) {
        String text = null;
        for (Map<String, String> layer : layers) {
            text = layer.get(key);
            if (text != null) break;
        }
        if (text == null) {
            if (warned.add(key)) warn.accept("Missing message '" + key + "' in every language file.");
            return key;
        }
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue());
        }
        return text;
    }

    /** Spłaszcza plik językowy: "reward.money" -> tekst, listy łączone znakiem nowej linii. */
    public static Map<String, String> flatten(ConfigurationSection section) {
        Map<String, String> out = new HashMap<>();
        for (String key : section.getKeys(true)) {
            if (section.isString(key)) out.put(key, section.getString(key));
            else if (section.isList(key)) out.put(key, String.join("\n", section.getStringList(key)));
        }
        return out;
    }
}
