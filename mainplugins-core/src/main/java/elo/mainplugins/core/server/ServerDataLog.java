package elo.mainplugins.core.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pliki CSV w plugins/MainpluginsCore/server-data/ - czytane przez aplikację (Statystyki,
 * historia harmonogramu i kopii). Dopisywanie linii + przycinanie starych wpisów.
 * Pierwsza kolumna każdej linii to czas w sekundach od epoki.
 */
public final class ServerDataLog {
    private final File dir;
    private final Consumer<String> warn;

    public ServerDataLog(File dataFolder, Consumer<String> warn) {
        this.dir = new File(dataFolder, "server-data");
        this.warn = warn;
    }

    public synchronized void append(String file, String line) {
        try {
            Files.createDirectories(dir.toPath());
            Files.writeString(new File(dir, file).toPath(), line.replace('\n', ' ') + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            warn.accept("Could not write server-data/" + file + ": " + e.getMessage());
        }
    }

    /** Usuwa linie starsze niż `keepSeconds`. */
    public synchronized void trim(String file, long keepSeconds) {
        File f = new File(dir, file);
        if (!f.isFile()) return;
        long cutoff = System.currentTimeMillis() / 1000 - keepSeconds;
        try {
            List<String> kept = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).stream()
                    .filter(l -> {
                        int comma = l.indexOf(',');
                        try {
                            return comma > 0 && Long.parseLong(l.substring(0, comma)) >= cutoff;
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    })
                    .toList();
            Files.write(f.toPath(), kept, StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn.accept("Could not trim server-data/" + file + ": " + e.getMessage());
        }
    }

    /** Pole CSV bez przecinków i nowych linii (nazwy zadań, wiadomości). */
    public static String clean(String s) {
        return s == null ? "" : s.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }

    public static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
