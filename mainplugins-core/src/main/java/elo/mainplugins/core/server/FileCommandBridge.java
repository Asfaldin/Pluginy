package elo.mainplugins.core.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Komendy z aplikacji bez RCON - przez pliki (dla hostingów z zablokowanym/wyłączonym RCON-em,
 * gdzie appka ma tylko dostęp do plików, np. SFTP panelu):
 *
 *   bridge/in/<id>.json   aplikacja zapisuje {"id","command"} (najpierw .tmp, potem zmiana nazwy)
 *   bridge/out/<id>.json  Core odpisuje {"id","ok","handled","output"} po wykonaniu
 *   bridge/alive          czas serwera (ms), odświeżany co 2 s - po tym appka poznaje, że serwer działa
 *
 * Bezpieczeństwo: kto może pisać do plików serwera, ten i tak może wgrać dowolny plugin, więc podpis
 * niczego by nie chronił. Ważne jest co innego: czytamy tylko swój folder, każde zlecenie wykonujemy
 * dokładnie raz (plik usuwany przed wykonaniem + pamięć ostatnich id), a zlecenia starsze niż minuta
 * (np. zapisane, gdy serwer był wyłączony) odrzucamy zamiast wykonywać po starcie.
 */
public final class FileCommandBridge {
    static final int PROTOCOL = 1;
    private static final long MAX_AGE_MS = 60_000;
    private static final long OUT_KEEP_MS = 10 * 60_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Gson GSON = new Gson();

    private final JavaPlugin plugin;
    private final File in;
    private final File out;
    private final File alive;
    private final Set<String> seen = new HashSet<>();
    private final Deque<String> seenOrder = new ArrayDeque<>();
    private BukkitTask poll;
    private BukkitTask heartbeat;
    private long lastCleanup;

    public FileCommandBridge(JavaPlugin plugin) {
        File dir = new File(plugin.getDataFolder(), "bridge");
        this.plugin = plugin;
        this.in = new File(dir, "in");
        this.out = new File(dir, "out");
        this.alive = new File(dir, "alive");
    }

    public void start() {
        in.mkdirs();
        out.mkdirs();
        heartbeat = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::beat, 0L, 40L);
        poll = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::scan, 20L, 10L);
    }

    public void stop() {
        if (poll != null) poll.cancel();
        if (heartbeat != null) heartbeat.cancel();
        try {
            Files.deleteIfExists(alive.toPath());
        } catch (IOException ignored) {
            // przy następnym starcie i tak nadpiszemy
        }
    }

    private void beat() {
        writeAtomic(alive, String.valueOf(System.currentTimeMillis()));
    }

    /** Wątek asynchroniczny: szuka nowych zleceń, wykonanie przekazuje na główny wątek. */
    private void scan() {
        File[] files = in.listFiles((d, name) -> name.endsWith(".json"));
        long now = System.currentTimeMillis();
        if (files != null) {
            for (File f : files) {
                String name = f.getName();
                String id = name.substring(0, name.length() - 5);
                if (!ID.matcher(id).matches()) {
                    f.delete();
                    continue;
                }
                long age = now - f.lastModified();
                JsonObject req;
                try {
                    req = GSON.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), JsonObject.class);
                } catch (IOException | JsonParseException e) {
                    // plik jeszcze się zapisuje - spróbujemy za chwilę; po minucie wyrzucamy śmieć
                    if (age > MAX_AGE_MS) f.delete();
                    continue;
                }
                // usunięcie PRZED wykonaniem: każde zlecenie najwyżej raz, nawet przy błędzie niżej
                if (!f.delete() || !remember(id)) continue;
                if (age > MAX_AGE_MS) {
                    respond(id, false, false, "Request expired (written " + age / 1000 + " s ago) - not executed.");
                    continue;
                }
                String command = req != null && req.has("command") ? req.get("command").getAsString().trim() : "";
                if (command.startsWith("/")) command = command.substring(1);
                if (command.isEmpty() || command.length() > 32_000) {
                    respond(id, false, false, "Empty or too long command.");
                    continue;
                }
                String cmd = command;
                Bukkit.getScheduler().runTask(plugin, () -> execute(id, cmd));
            }
        }
        if (now - lastCleanup > 60_000) {
            lastCleanup = now;
            File[] old = out.listFiles();
            if (old != null) for (File f : old) if (now - f.lastModified() > OUT_KEEP_MS) f.delete();
        }
    }

    private synchronized boolean remember(String id) {
        if (!seen.add(id)) return false;
        seenOrder.add(id);
        if (seenOrder.size() > 500) seen.remove(seenOrder.poll());
        return true;
    }

    /** Główny wątek: wykonuje komendę jak konsola i zbiera to, co komenda odpisała. */
    private void execute(String id, String command) {
        StringBuilder output = new StringBuilder();
        CommandSender sender = Bukkit.createCommandSender(c -> output.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n'));
        plugin.getLogger().info("Komenda z aplikacji (przez pliki): " + command);
        boolean handled;
        try {
            handled = Bukkit.dispatchCommand(sender, command);
        } catch (RuntimeException e) {
            respond(id, false, false, "Command failed: " + e.getMessage());
            return;
        }
        String text = output.toString().stripTrailing();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> respond(id, true, handled, text));
    }

    private void respond(String id, boolean ok, boolean handled, String output) {
        JsonObject res = new JsonObject();
        res.addProperty("id", id);
        res.addProperty("ok", ok);
        res.addProperty("handled", handled);
        res.addProperty("output", output);
        res.addProperty("protocol", PROTOCOL);
        writeAtomic(new File(out, id + ".json"), GSON.toJson(res));
    }

    private void writeAtomic(File target, String text) {
        try {
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().warning("Most komend (pliki): nie zapisano " + target.getName() + ": " + e.getMessage());
        }
    }
}
