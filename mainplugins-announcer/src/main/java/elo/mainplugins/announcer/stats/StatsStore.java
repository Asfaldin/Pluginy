package elo.mainplugins.announcer.stats;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Licznik "ile razy pokazano wiadomość X" i "ile razy odebrano nagrodę X" -
 * zapisywany do announcer-stats.yml (co 5 min + przy wyłączeniu). Do wglądu w
 * aplikacji / do strojenia treści. Nic w silniku od tego nie zależy.
 */
public final class StatsStore {

    private final Plugin plugin;
    private final File file;
    private final Map<String, Integer> shown = new ConcurrentHashMap<>();
    private final Map<String, Integer> claimed = new ConcurrentHashMap<>();
    private BukkitTask autosave;

    public StatsStore(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "announcer-stats.yml");
        loadFromDisk();
    }

    public void start() {
        stop();
        autosave = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, this::save, 20L * 300, 20L * 300);
    }

    public void stop() {
        if (autosave != null) autosave.cancel();
        autosave = null;
    }

    public void recordShown(String id) {
        if (id != null) shown.merge(id, 1, Integer::sum);
    }

    public void recordClaimed(String id) {
        if (id != null) claimed.merge(id, 1, Integer::sum);
    }

    private void loadFromDisk() {
        if (!file.exists()) return;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        if (c.isConfigurationSection("shown")) {
            for (String k : c.getConfigurationSection("shown").getKeys(false)) shown.put(k, c.getInt("shown." + k));
        }
        if (c.isConfigurationSection("claimed")) {
            for (String k : c.getConfigurationSection("claimed").getKeys(false)) claimed.put(k, c.getInt("claimed." + k));
        }
    }

    public void save() {
        YamlConfiguration c = new YamlConfiguration();
        shown.forEach((k, v) -> c.set("shown." + k, v));
        claimed.forEach((k, v) -> c.set("claimed." + k, v));
        try {
            file.getParentFile().mkdirs();
            c.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie mozna zapisac announcer-stats.yml: " + e.getMessage());
        }
    }
}
