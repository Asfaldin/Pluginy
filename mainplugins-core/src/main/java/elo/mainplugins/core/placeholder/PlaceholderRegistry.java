package elo.mainplugins.core.placeholder;

import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Czysta logika placeholderów %mainplugins_<nazwa>%: lista "tłumaczy" od pluginów,
 * pytanych po kolei - pierwszy, który zna nazwę, wygrywa. Tłumacz rzucający wyjątek
 * jest pomijany (jedno ostrzeżenie na właściciela), żeby jeden błąd nie psuł całego TAB-a.
 */
public final class PlaceholderRegistry<O> {

    private record Entry<O>(O owner, BiFunction<OfflinePlayer, String, String> resolver) {}

    private final List<Entry<O>> entries = new CopyOnWriteArrayList<>();
    private final Set<Object> warned = ConcurrentHashMap.newKeySet();
    private final Consumer<String> warn;

    public PlaceholderRegistry(Consumer<String> warn) {
        this.warn = warn;
    }

    public void register(O owner, BiFunction<OfflinePlayer, String, String> resolver) {
        entries.add(new Entry<>(owner, resolver));
    }

    public void unregister(O owner) {
        List<Entry<O>> doUsuniecia = new ArrayList<>();
        for (Entry<O> e : entries) if (e.owner().equals(owner)) doUsuniecia.add(e);
        entries.removeAll(doUsuniecia);
    }

    public String resolve(OfflinePlayer player, String params) {
        for (Entry<O> e : entries) {
            try {
                String value = e.resolver().apply(player, params);
                if (value != null) return value;
            } catch (RuntimeException ex) {
                if (warned.add(e.owner())) {
                    warn.accept("Placeholder resolver of " + e.owner() + " failed: " + ex + " - skipping it.");
                }
            }
        }
        return null;
    }
}
