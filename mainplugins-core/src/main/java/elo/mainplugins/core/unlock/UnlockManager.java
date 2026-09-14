package elo.mainplugins.core.unlock;

import elo.mainplugins.core.api.UnlockService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/** Odblokowania w plugins/MainpluginsCore/unlocks.yml. Zmiany rzadkie, więc zapis od razu. */
public final class UnlockManager implements UnlockService {

    private final File file;
    private final Consumer<String> warn;
    private final Map<UUID, Set<String>> unlocks = new HashMap<>();

    public UnlockManager(File file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
        load();
    }

    private static String norm(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private void load() {
        if (!file.exists()) return;
        ConfigurationSection players = YamlConfiguration.loadConfiguration(file).getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                warn.accept("unlocks.yml: '" + key + "' is not a player UUID - skipping.");
                continue;
            }
            Set<String> set = new TreeSet<>();
            for (String n : players.getStringList(key)) if (!norm(n).isEmpty()) set.add(norm(n));
            if (!set.isEmpty()) unlocks.put(uuid, set);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> e : unlocks.entrySet()) {
            if (!e.getValue().isEmpty()) y.set("players." + e.getKey(), new ArrayList<>(e.getValue()));
        }
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            y.save(file);
        } catch (IOException e) {
            warn.accept("Could not save unlocks.yml: " + e.getMessage());
        }
    }

    @Override
    public boolean has(UUID player, String name) {
        return unlocks.getOrDefault(player, Set.of()).contains(norm(name));
    }

    @Override
    public boolean give(UUID player, String name) {
        String n = norm(name);
        if (n.isEmpty()) return false;
        boolean added = unlocks.computeIfAbsent(player, k -> new TreeSet<>()).add(n);
        if (added) save();
        return added;
    }

    @Override
    public boolean take(UUID player, String name) {
        Set<String> set = unlocks.get(player);
        boolean removed = set != null && set.remove(norm(name));
        if (removed) save();
        return removed;
    }

    @Override
    public Set<String> list(UUID player) {
        return Collections.unmodifiableSet(new TreeSet<>(unlocks.getOrDefault(player, Set.of())));
    }
}
