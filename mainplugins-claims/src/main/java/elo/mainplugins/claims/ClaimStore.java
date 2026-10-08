package elo.mainplugins.claims;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Zabezpieczone chunki ("świat;x;z" -> właściciel) i ustawienia właścicieli (zaufani, flagi -
 * wspólne dla wszystkich chunków danego gracza). Czysta logika bez zdarzeń Bukkita - łatwa do testów.
 */
public final class ClaimStore {

    /** Ustawienia jednego właściciela - obowiązują na każdym jego chunku. */
    public static final class Owner {
        public final Set<UUID> trusted = new LinkedHashSet<>();
        public boolean pvp;
        public boolean explosions;
        public boolean mobGriefing;
    }

    private final File file;
    private final Consumer<String> warn;
    private final Map<String, UUID> chunks = new HashMap<>();
    private final Map<UUID, Owner> owners = new HashMap<>();
    private boolean dirty;

    public ClaimStore(File file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
    }

    public static String key(String world, int x, int z) {
        return world.toLowerCase() + ";" + x + ";" + z;
    }

    public UUID ownerAt(String world, int x, int z) {
        return chunks.get(key(world, x, z));
    }

    /** Ustawienia właściciela; nowy właściciel dostaje domyślne flagi z configu. */
    public Owner owner(UUID id, ClaimsConfig defaults) {
        return owners.computeIfAbsent(id, k -> {
            Owner o = new Owner();
            if (defaults != null) {
                o.pvp = defaults.defaultPvp();
                o.explosions = defaults.defaultExplosions();
                o.mobGriefing = defaults.defaultMobGriefing();
            }
            dirty = true;
            return o;
        });
    }

    public Owner ownerIfExists(UUID id) {
        return owners.get(id);
    }

    public int count(UUID owner) {
        int n = 0;
        for (UUID u : chunks.values()) if (u.equals(owner)) n++;
        return n;
    }

    public List<String> chunksOf(UUID owner) {
        return chunks.entrySet().stream().filter(e -> e.getValue().equals(owner)).map(Map.Entry::getKey).sorted().toList();
    }

    public void claim(String world, int x, int z, UUID owner) {
        chunks.put(key(world, x, z), owner);
        dirty = true;
    }

    public boolean unclaim(String world, int x, int z) {
        boolean removed = chunks.remove(key(world, x, z)) != null;
        dirty |= removed;
        return removed;
    }

    /** Czy gracz może tu budować/otwierać: brak claimu, właściciel, zaufany albo bypass. */
    public boolean canUse(String world, int x, int z, UUID player, boolean bypass) {
        UUID owner = ownerAt(world, x, z);
        if (owner == null || bypass || owner.equals(player)) return true;
        Owner o = owners.get(owner);
        return o != null && o.trusted.contains(player);
    }

    public void markDirty() {
        dirty = true;
    }

    public void load() {
        chunks.clear();
        owners.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        // Lista "świat;x;z=uuid" zamiast kluczy YAML - nazwa świata może mieć kropki.
        for (String line : yml.getStringList("chunks")) {
            int eq = line.lastIndexOf('=');
            try {
                if (eq <= 0) throw new IllegalArgumentException("no '='");
                chunks.put(line.substring(0, eq).toLowerCase(), UUID.fromString(line.substring(eq + 1)));
            } catch (IllegalArgumentException e) {
                warn.accept("data.yml: bad chunk entry '" + line + "' - skipping.");
            }
        }
        ConfigurationSection os = yml.getConfigurationSection("owners");
        if (os != null) {
            for (String k : os.getKeys(false)) {
                ConfigurationSection s = os.getConfigurationSection(k);
                if (s == null) continue;
                try {
                    Owner o = new Owner();
                    s.getStringList("trusted").forEach(t -> o.trusted.add(UUID.fromString(t)));
                    o.pvp = s.getBoolean("pvp");
                    o.explosions = s.getBoolean("explosions");
                    o.mobGriefing = s.getBoolean("mob-griefing");
                    owners.put(UUID.fromString(k), o);
                } catch (IllegalArgumentException e) {
                    warn.accept("data.yml: bad owner " + k + " - skipping.");
                }
            }
        }
        dirty = false;
    }

    public void save() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("chunks", chunks.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).sorted().toList());
        owners.forEach((id, o) -> {
            String p = "owners." + id;
            yml.set(p + ".trusted", o.trusted.stream().map(UUID::toString).toList());
            yml.set(p + ".pvp", o.pvp);
            yml.set(p + ".explosions", o.explosions);
            yml.set(p + ".mob-griefing", o.mobGriefing);
        });
        try {
            file.getParentFile().mkdirs();
            yml.save(file);
            dirty = false;
        } catch (IOException e) {
            warn.accept("Could not save data.yml: " + e.getMessage());
        }
    }
}
