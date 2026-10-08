package elo.mainplugins.guilds;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Gildie w data.yml (osobno od guilds.yml, który edytuje appka). */
public final class GuildStore {

    private final File file;
    private final Consumer<String> warn;
    private final Map<String, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, Guild> byMember = new HashMap<>();
    private boolean dirty;

    public GuildStore(File file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
    }

    public void load() {
        guilds.clear();
        byMember.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            if (s == null) continue;
            try {
                Guild g = new Guild(s.getString("tag", id), s.getString("name", id), UUID.fromString(s.getString("leader", "")));
                s.getStringList("officers").forEach(u -> g.officers.add(UUID.fromString(u)));
                s.getStringList("members").forEach(u -> g.members.add(UUID.fromString(u)));
                g.allies.addAll(s.getStringList("allies"));
                g.home = s.getString("home");
                g.bank = s.getDouble("bank");
                g.created = s.getLong("created", g.created);
                put(g);
            } catch (IllegalArgumentException e) {
                warn.accept("data.yml: guild '" + id + "' is broken (" + e.getMessage() + ") - skipping.");
            }
        }
    }

    public void put(Guild g) {
        guilds.put(g.id, g);
        g.members.forEach(m -> byMember.put(m, g));
        dirty = true;
    }

    public void remove(Guild g) {
        guilds.remove(g.id);
        g.members.forEach(byMember::remove);
        guilds.values().forEach(o -> o.allies.remove(g.id));
        dirty = true;
    }

    public void join(Guild g, UUID player) {
        g.members.add(player);
        byMember.put(player, g);
        dirty = true;
    }

    public void leave(Guild g, UUID player) {
        g.members.remove(player);
        g.officers.remove(player);
        byMember.remove(player);
        dirty = true;
    }

    public Guild of(UUID player) {
        return byMember.get(player);
    }

    public Guild byTag(String tag) {
        return tag == null ? null : guilds.get(tag.toLowerCase());
    }

    public Collection<Guild> all() {
        return guilds.values();
    }

    public void markDirty() {
        dirty = true;
    }

    public void save() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Guild g : guilds.values()) {
            yml.set(g.id + ".tag", g.tag);
            yml.set(g.id + ".name", g.name);
            yml.set(g.id + ".leader", g.leader.toString());
            yml.set(g.id + ".officers", g.officers.stream().map(UUID::toString).toList());
            yml.set(g.id + ".members", g.members.stream().map(UUID::toString).toList());
            yml.set(g.id + ".allies", g.allies.stream().toList());
            yml.set(g.id + ".home", g.home);
            yml.set(g.id + ".bank", g.bank);
            yml.set(g.id + ".created", g.created);
        }
        try {
            file.getParentFile().mkdirs();
            yml.save(file);
            dirty = false;
        } catch (IOException e) {
            warn.accept("Could not save data.yml: " + e.getMessage());
        }
    }
}
