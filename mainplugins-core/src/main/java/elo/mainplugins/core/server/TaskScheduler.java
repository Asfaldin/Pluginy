package elo.mainplugins.core.server;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Harmonogram z plugins/MainpluginsCore/scheduler.yml. Sprawdzany co sekundę, każde zadanie
 * odpala się najwyżej raz na minutę. Plik jest przeładowywany sam po zmianie (aplikacja
 * wgrywa nowy) i komendą /@schedule reload. Każde uruchomienie trafia do
 * server-data/scheduler-log.csv: czas,id,typ,wynik.
 */
public final class TaskScheduler {
    private final JavaPlugin plugin;
    private final ServerDataLog log;
    private final BackupManager backups;
    private final File file;

    private Map<String, ScheduledTask> tasks = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private String restartCommand = "";
    private long loadedModified = -1;
    private LocalDateTime lastMinute;
    private boolean restarting;

    public TaskScheduler(JavaPlugin plugin, ServerDataLog log, BackupManager backups) {
        this.plugin = plugin;
        this.log = log;
        this.backups = backups;
        this.file = new File(plugin.getDataFolder(), "scheduler.yml");
    }

    public void start() {
        if (!file.exists()) plugin.saveResource("scheduler.yml", false);
        reload();
        lastMinute = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public List<String> reload() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        loadedModified = file.lastModified();
        warnings.clear();
        Map<String, ScheduledTask> next = new LinkedHashMap<>();
        ConfigurationSection sec = yml.getConfigurationSection("tasks");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection t = sec.getConfigurationSection(id);
                if (t == null) continue;
                next.put(id, ScheduledTask.parse(id, t.getValues(false), warnings));
            }
        }
        tasks = next;
        restartCommand = yml.getString("restart-command", "").trim().replaceFirst("^/", "");
        backups.configure(yml.getConfigurationSection("backups"));
        warnings.forEach(w -> plugin.getLogger().warning("scheduler.yml: " + w));
        return List.copyOf(warnings);
    }

    public Map<String, ScheduledTask> tasks() {
        return tasks;
    }

    private void tick() {
        if (file.lastModified() != loadedModified) reload();
        LocalDateTime minute = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);
        if (!minute.isAfter(lastMinute)) return;
        lastMinute = minute;
        for (ScheduledTask t : tasks.values()) {
            if (t.isDue(minute)) run(t, "schedule");
        }
    }

    /** Uruchamia zadanie teraz (z harmonogramu albo /@schedule run). */
    public String run(ScheduledTask t, String trigger) {
        String result;
        switch (t.type()) {
            case "broadcast" -> {
                broadcast(t.message());
                result = "ok";
            }
            case "restart" -> result = restart(t);
            case "backup" -> result = backups.start(trigger + ":" + t.id(), msg -> {
                plugin.getLogger().info(msg);
                Bukkit.getOnlinePlayers().stream().filter(p -> p.hasPermission("mainplugins.core.admin"))
                        .forEach(p -> p.sendMessage(Component.text("[Mainplugins] " + msg)));
            }) ? "started" : "skipped: backup already running";
            default -> {
                int ok = 0;
                for (String c : t.commands()) if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c)) ok++;
                result = ok == t.commands().size() ? "ok" : "partial: " + ok + "/" + t.commands().size();
            }
        }
        log.append("scheduler-log.csv", ServerDataLog.now() + "," + ServerDataLog.clean(t.id()) + "," + t.type() + "," + ServerDataLog.clean(trigger + " " + result));
        return result;
    }

    private String restart(ScheduledTask t) {
        if (restarting) return "skipped: restart already pending";
        restarting = true;
        int total = t.warnSeconds().isEmpty() ? 0 : t.warnSeconds().get(0);
        String msg = t.message().isBlank() ? "&cServer restarts in &e{time}&c." : t.message();
        for (int s : t.warnSeconds()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> broadcast(msg.replace("{time}", human(s))), 20L * (total - s));
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (String c : t.commands()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
            if (!restartCommand.isEmpty()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), restartCommand);
            else Bukkit.shutdown();
        }, 20L * total + 20L);
        return "ok (in " + human(total) + ")";
    }

    private static String human(int s) {
        if (s >= 60 && s % 60 == 0) return (s / 60) + " min";
        return s + " s";
    }

    private static void broadcast(String legacy) {
        Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(legacy);
        Bukkit.getServer().sendMessage(c);
    }

    public void save(YamlConfiguration yml) throws IOException {
        yml.save(file);
    }
}
