package elo.mainplugins.pass;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/** Postęp graczy w players.yml: XP i odebrane poziomy w sezonie, seria nagród dziennych, oczekujące głosy. */
public final class PlayerStore {

    /** Dane jednego gracza - zmieniane w miejscu, zapisywane przez {@link #save()}. */
    public static final class Data {
        public String season = "";
        public long xp;
        public final Set<Integer> claimedFree = new TreeSet<>();
        public final Set<Integer> claimedPremium = new TreeSet<>();
        public int streak;
        public LocalDate lastDaily;
        /** Minuty gry od ostatniego XP za czas gry. */
        public int minutes;
        /** Głosy oddane, gdy gracz był offline - nagroda przy następnym wejściu. */
        public int pendingVotes;
    }

    private final File file;
    private final Consumer<String> warn;
    private final Map<UUID, Data> players = new HashMap<>();
    private boolean dirty;

    public PlayerStore(File file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
    }

    public void load() {
        players.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection s = yml.getConfigurationSection(key);
            if (s == null) continue;
            Data d = new Data();
            d.season = s.getString("season", "");
            d.xp = s.getLong("xp");
            s.getIntegerList("claimed-free").forEach(d.claimedFree::add);
            s.getIntegerList("claimed-premium").forEach(d.claimedPremium::add);
            d.streak = s.getInt("streak");
            String last = s.getString("last-daily", "");
            if (last != null && !last.isBlank()) {
                try {
                    d.lastDaily = LocalDate.parse(last);
                } catch (RuntimeException ignored) {
                    // uszkodzona data - seria zacznie się od nowa
                }
            }
            d.minutes = s.getInt("minutes");
            d.pendingVotes = s.getInt("pending-votes");
            players.put(id, d);
        }
    }

    public Data get(UUID id) {
        return players.computeIfAbsent(id, k -> new Data());
    }

    public void markDirty() {
        dirty = true;
    }

    /** Zapisuje tylko, gdy coś się zmieniło od ostatniego zapisu. */
    public void save() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        players.forEach((id, d) -> {
            String k = id.toString();
            yml.set(k + ".season", d.season);
            yml.set(k + ".xp", d.xp);
            yml.set(k + ".claimed-free", d.claimedFree.stream().toList());
            yml.set(k + ".claimed-premium", d.claimedPremium.stream().toList());
            yml.set(k + ".streak", d.streak);
            yml.set(k + ".last-daily", d.lastDaily == null ? "" : d.lastDaily.toString());
            yml.set(k + ".minutes", d.minutes);
            yml.set(k + ".pending-votes", d.pendingVotes);
        });
        try {
            file.getParentFile().mkdirs();
            yml.save(file);
            dirty = false;
        } catch (IOException e) {
            warn.accept("Could not save players.yml: " + e.getMessage());
        }
    }
}
