package elo.mainplugins.core.server;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Statystyki zbierane na serwerze (także gdy aplikacja jest zamknięta):
 * stats.csv  - co minutę: czas,online,max,tps,mspt (7 dni),
 * players.csv - wejścia/wyjścia: czas,join|quit,uuid,nick (30 dni).
 */
public final class StatsRecorder implements Listener {
    private static final long STATS_KEEP = 7L * 24 * 3600;
    private static final long PLAYERS_KEEP = 30L * 24 * 3600;

    private final JavaPlugin plugin;
    private final ServerDataLog log;

    public StatsRecorder(JavaPlugin plugin, ServerDataLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    public void start() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            log.trim("stats.csv", STATS_KEEP);
            log.trim("players.csv", PLAYERS_KEEP);
        });
        // Odczyt na głównym wątku, zapis pliku asynchronicznie.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int online = Bukkit.getOnlinePlayers().size();
            int max = Bukkit.getMaxPlayers();
            double tps = Math.min(20.0, Bukkit.getTPS()[0]);
            double mspt = Bukkit.getAverageTickTime();
            String line = String.format(Locale.ROOT, "%d,%d,%d,%.2f,%.2f", ServerDataLog.now(), online, max, tps, mspt);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> log.append("stats.csv", line));
        }, 20L * 30, 20L * 60);
        // Raz na dobę przycinamy, żeby pliki nie rosły bez końca przy długim uptime.
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            log.trim("stats.csv", STATS_KEEP);
            log.trim("players.csv", PLAYERS_KEEP);
        }, 20L * 3600 * 24, 20L * 3600 * 24);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        record("join", e.getPlayer().getUniqueId().toString(), e.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        record("quit", e.getPlayer().getUniqueId().toString(), e.getPlayer().getName());
    }

    private void record(String kind, String uuid, String name) {
        String line = ServerDataLog.now() + "," + kind + "," + uuid + "," + ServerDataLog.clean(name);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> log.append("players.csv", line));
    }
}
