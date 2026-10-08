package elo.mainplugins.core.server;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Kopie zapasowe: zip światów (+ opcjonalnie konfiguracji pluginów, bez .jar) do
 * &lt;serwer&gt;/backups/backup-RRRR-MM-DD_GG-mm.zip. Zapis świata na głównym wątku,
 * pakowanie asynchronicznie (autozapis wyłączony na czas kopii). Zostaje `keep` najnowszych.
 * Ustawienia w scheduler.yml, sekcja `backups:`.
 */
public final class BackupManager {
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final List<String> SKIP_FILES = List.of("session.lock");

    private final JavaPlugin plugin;
    private final ServerDataLog log;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private String folder = "backups";
    private int keep = 7;
    private boolean includePlugins = true;
    private List<String> worlds = List.of();

    public BackupManager(JavaPlugin plugin, ServerDataLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    public void configure(ConfigurationSection s) {
        if (s == null) return;
        folder = s.getString("folder", "backups");
        keep = Math.max(1, s.getInt("keep", 7));
        includePlugins = s.getBoolean("include-plugins", true);
        worlds = s.getStringList("worlds");
    }

    public boolean isRunning() {
        return running.get();
    }

    public File backupDir() {
        File root = Bukkit.getWorldContainer().getAbsoluteFile();
        File f = new File(folder);
        return f.isAbsolute() ? f : new File(root, folder);
    }

    /** Startuje kopię; `done` dostaje opis wyniku (na głównym wątku). Zwraca false, gdy kopia już trwa. */
    public boolean start(String reason, Consumer<String> done) {
        if (!running.compareAndSet(false, true)) return false;
        List<World> chosen = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            if (worlds.isEmpty() || worlds.contains(w.getName())) chosen.add(w);
        }
        List<World> autosaveWasOn = new ArrayList<>();
        for (World w : chosen) {
            w.save();
            if (w.isAutoSave()) {
                autosaveWasOn.add(w);
                w.setAutoSave(false);
            }
        }
        List<File> worldDirs = chosen.stream().map(World::getWorldFolder).toList();
        File pluginsDir = plugin.getDataFolder().getParentFile();
        File target = new File(backupDir(), "backup-" + LocalDateTime.now().format(NAME) + ".zip");
        long started = System.currentTimeMillis();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String result;
            try {
                Files.createDirectories(target.getParentFile().toPath());
                File tmp = new File(target.getPath() + ".part");
                try (OutputStream out = Files.newOutputStream(tmp.toPath()); ZipOutputStream zip = new ZipOutputStream(out)) {
                    for (File dir : worldDirs) addTree(zip, dir.toPath(), "worlds/" + dir.getName(), false);
                    if (includePlugins && pluginsDir != null) addTree(zip, pluginsDir.toPath(), "plugins", true);
                }
                Files.move(tmp.toPath(), target.toPath());
                prune();
                long secs = (System.currentTimeMillis() - started) / 1000;
                result = String.format(Locale.ROOT, "ok,%s,%d,%d", target.getName(), target.length(), secs);
            } catch (Exception e) {
                result = "error," + ServerDataLog.clean(e.getMessage()) + ",0,0";
                plugin.getLogger().warning("Backup failed: " + e.getMessage());
            }
            log.append("backups.csv", ServerDataLog.now() + "," + ServerDataLog.clean(reason) + "," + result);
            String finalResult = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (World w : autosaveWasOn) w.setAutoSave(true);
                running.set(false);
                done.accept(finalResult.startsWith("ok") ? "Backup saved: " + target.getName() : "Backup failed: " + finalResult.split(",")[1]);
            });
        });
        return true;
    }

    private void addTree(ZipOutputStream zip, Path base, String prefix, boolean skipJars) throws IOException {
        if (!Files.isDirectory(base)) return;
        Path backups = backupDir().toPath().toAbsolutePath().normalize();
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return dir.toAbsolutePath().normalize().startsWith(backups) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (SKIP_FILES.contains(name) || (skipJars && name.endsWith(".jar"))) return FileVisitResult.CONTINUE;
                String entry = prefix + "/" + base.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entry));
                try {
                    Files.copy(file, zip);
                } catch (IOException e) {
                    // Plik zablokowany/znika w trakcie (np. log) - pomijamy, reszta kopii zostaje.
                    plugin.getLogger().fine("Backup skipped " + entry + ": " + e.getMessage());
                }
                zip.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void prune() {
        File[] all = backupDir().listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        if (all == null || all.length <= keep) return;
        Arrays.sort(all, Comparator.comparing(File::getName).reversed());
        for (int i = keep; i < all.length; i++) {
            if (!all[i].delete()) plugin.getLogger().warning("Could not delete old backup " + all[i].getName());
        }
    }
}
