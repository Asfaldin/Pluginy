package elo.mainplugins.core.server;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Raport serwera dla aplikacji: plugins/MainpluginsCore/server-info.yml - silnik, wersja Minecrafta,
 * Java, pluginy. Aplikacja czyta go jako pierwsze źródło wersji, więc wykrywanie działa niezależnie
 * od tego, gdzie hosting trzyma logi i jak nazywa jar serwera. Odświeżany co minutę.
 */
public final class ServerInfoWriter {
    /** Wersja formatu pliku - podnosimy przy zmianach niezgodnych wstecz. */
    static final int FORMAT = 1;

    private final JavaPlugin plugin;
    private final File file;

    public ServerInfoWriter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "server-info.yml");
    }

    public void start() {
        // pierwszy zapis po starcie serwera - wtedy lista pluginów jest pełna
        Bukkit.getScheduler().runTask(plugin, () -> write(true));
        Bukkit.getScheduler().runTaskTimer(plugin, () -> write(true), 20L * 60, 20L * 60);
    }

    /** Przy wyłączaniu: running=false (synchronicznie, bo scheduler już nie działa). */
    public void stop() {
        writeNow(snapshot(false));
    }

    private void write(boolean running) {
        YamlConfiguration yml = snapshot(running);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeNow(yml));
    }

    private YamlConfiguration snapshot(boolean running) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of("Zapisywane przez MainpluginsCore dla aplikacji (wersja serwera, pluginy). Nie edytuj."));
        yml.set("format", FORMAT);
        yml.set("written-at", System.currentTimeMillis());
        yml.set("running", running);
        yml.set("engine", Bukkit.getName());
        yml.set("minecraft", Bukkit.getMinecraftVersion());
        yml.set("server-version", Bukkit.getVersion());
        yml.set("bukkit-version", Bukkit.getBukkitVersion());
        yml.set("java", System.getProperty("java.version"));
        yml.set("port", Bukkit.getPort());
        yml.set("online-mode", Bukkit.getOnlineMode());
        yml.set("players.online", Bukkit.getOnlinePlayers().size());
        yml.set("players.max", Bukkit.getMaxPlayers());
        List<Map<String, Object>> plugins = new ArrayList<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", p.getName());
            entry.put("version", p.getPluginMeta().getVersion());
            entry.put("enabled", p.isEnabled());
            plugins.add(entry);
        }
        yml.set("plugins", plugins);
        yml.set("bridge.protocol", FileCommandBridge.PROTOCOL);
        yml.set("bridge.dir", "bridge");
        return yml;
    }

    private void writeNow(YamlConfiguration yml) {
        try {
            File dir = file.getParentFile();
            if (!dir.exists() && !dir.mkdirs()) return;
            File tmp = new File(dir, "server-info.yml.tmp");
            Files.writeString(tmp.toPath(), yml.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie zapisano server-info.yml: " + e.getMessage());
        }
    }
}
