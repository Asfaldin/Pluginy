package elo.mainplugins.quests;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** progress.yml: players.<uuid>.done.<kategoria> = [id zadań], players.<uuid>.titles = [id tytułów]. */
public final class ProgressStore {

    private ProgressStore() {}

    /** Postęp jednego gracza (zmienny - QuestManager dopisuje i zapisuje). */
    public static final class PlayerProgress {
        private final Map<String, Set<Integer>> done = new LinkedHashMap<>();
        private final Set<String> titles = new LinkedHashSet<>();

        /** Do zapisu - tworzy pusty zbiór, gdy go nie ma. */
        public Set<Integer> doneIn(String category) {
            return done.computeIfAbsent(category, k -> new LinkedHashSet<>());
        }

        /** Tylko do odczytu - niczego nie tworzy. */
        public Set<Integer> doneView(String category) {
            return done.getOrDefault(category, Set.of());
        }

        public Map<String, Set<Integer>> done() {
            return done;
        }

        public Set<String> titles() {
            return titles;
        }
    }

    public static Map<UUID, PlayerProgress> read(ConfigurationSection root, Consumer<String> warn) {
        Map<UUID, PlayerProgress> out = new LinkedHashMap<>();
        ConfigurationSection players = root.getConfigurationSection("players");
        if (players == null) return out;
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                warn.accept("progress.yml: '" + key + "' is not a player UUID - skipping.");
                continue;
            }
            PlayerProgress p = new PlayerProgress();
            ConfigurationSection done = players.getConfigurationSection(key + ".done");
            if (done != null) {
                for (String cat : done.getKeys(false)) p.doneIn(cat).addAll(done.getIntegerList(cat));
            }
            p.titles().addAll(players.getStringList(key + ".titles"));
            out.put(uuid, p);
        }
        return out;
    }

    public static void write(ConfigurationSection root, Map<UUID, PlayerProgress> all) {
        root.set("players", null);
        for (Map.Entry<UUID, PlayerProgress> e : all.entrySet()) {
            String base = "players." + e.getKey();
            for (Map.Entry<String, Set<Integer>> d : e.getValue().done().entrySet()) {
                if (!d.getValue().isEmpty()) root.set(base + ".done." + d.getKey(), new ArrayList<>(d.getValue()));
            }
            if (!e.getValue().titles().isEmpty()) root.set(base + ".titles", new ArrayList<>(e.getValue().titles()));
        }
    }
}
