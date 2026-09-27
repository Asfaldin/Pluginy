package elo.mainplugins.core;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Wysyła graczom przy wejściu paczki zasobów - dwie warstwy:
 * <ol>
 *   <li><b>bazowa</b> - wspólny resourcepack Mainplugins (custom tekstury itemów, np. Ewoluujący
 *       Kilof z mainplugins-tools) spod url/hash z resourcepack.yml;</li>
 *   <li><b>serwerowa</b> - paczka serwera z Texturepack Creatora w aplikacji, hostowana przez
 *       sam Core (wbudowany serwer HTTP), z pliku resourcepack/pack.zip w folderze pluginu.
 *       Nakłada się na bazową, więc tekstury Mainplugins nie znikają.</li>
 * </ol>
 * Aplikacja wgrywa pack.zip i wywołuje /@reloadresourcepack - gracze online dostają nową
 * paczkę od razu, bez restartu, bez zewnętrznego hostingu i bez ruszania server.properties.
 * resourcepack.yml kopiowany 1:1 przy pierwszym uruchomieniu (ręczne zmiany admina zostają).
 */
public class ResourcePackManager implements Listener {

    private static final UUID BAZOWA_ID = UUID.nameUUIDFromBytes("mainplugins:bazowa".getBytes(StandardCharsets.UTF_8));
    private static final UUID SERWEROWA_ID = UUID.nameUUIDFromBytes("mainplugins:serwerowa".getBytes(StandardCharsets.UTF_8));

    private record Paczka(String url, String hash) {
        byte[] hashBytes() {
            try {
                return hash == null || hash.isBlank() ? null : HexFormat.of().parseHex(hash.trim());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    private final Plugin plugin;
    private volatile Paczka bazowa;
    private volatile Paczka serwerowa;
    private volatile boolean wymagany;
    private volatile byte[] dane;
    private HttpServer http;
    private int httpPort = -1;
    /** Publiczny adres wykryty automatycznie (raz na uruchomienie). */
    private volatile String wykrytyAdres;

    public ResourcePackManager(Plugin plugin) {
        this.plugin = plugin;
        wczytaj();
    }

    /** Wczytuje konfigurację i paczkę serwera. Wywoływane przy starcie i przez /@reloadresourcepack. */
    public synchronized void wczytaj() {
        File plik = new File(plugin.getDataFolder(), "resourcepack.yml");
        if (!plik.exists()) {
            plugin.saveResource("resourcepack.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);
        String url = cfg.getString("url", "");
        bazowa = url.isBlank() ? null : new Paczka(url, cfg.getString("hash", ""));
        wymagany = cfg.getBoolean("wymagany", false);

        boolean hosting = cfg.getBoolean("wbudowany-hosting.wlaczony", true);
        int port = cfg.getInt("wbudowany-hosting.port", 8163);
        String adres = cfg.getString("wbudowany-hosting.adres", "").trim();

        // Folder tworzony zawsze - aplikacja wgrywa do niego paczkę przez SFTP.
        File folder = new File(plugin.getDataFolder(), "resourcepack");
        folder.mkdirs();
        File zip = new File(folder, "pack.zip");
        serwerowa = null;
        if (!hosting || !zip.isFile()) {
            zatrzymajHttp();
            dane = null;
            return;
        }
        String sha1;
        try {
            byte[] bytes = Files.readAllBytes(zip.toPath());
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
            dane = bytes;
            uruchomHttp(port);
        } catch (Exception e) {
            plugin.getLogger().warning("Paczka serwera (resourcepack/pack.zip) niedostępna: " + e.getMessage());
            zatrzymajHttp();
            dane = null;
            return;
        }

        String host = !adres.isEmpty() ? adres : adresZSerwera();
        if (host != null) {
            ustawSerwerowa(host, port, sha1);
            return;
        }
        // Brak adresu w konfiguracji i w server.properties - publiczne IP wykrywamy w tle
        // (zapytanie do api.ipify.org), żeby nie blokować wątku serwera.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String ip = wykryjPubliczneIp();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ip == null) {
                    plugin.getLogger().warning("Nie udało się ustalić adresu serwera dla paczki zasobów - ustaw "
                            + "wbudowany-hosting.adres w resourcepack.yml i wpisz /@reloadresourcepack.");
                    return;
                }
                wykrytyAdres = ip;
                ustawSerwerowa(ip, port, sha1);
                Bukkit.getOnlinePlayers().forEach(this::wyslij);
            });
        });
    }

    private void ustawSerwerowa(String host, int port, String sha1) {
        // ?v= zmienia adres przy każdej nowej paczce - klient nie weźmie starej z pamięci.
        serwerowa = new Paczka("http://" + host + ":" + port + "/pack.zip?v=" + sha1.substring(0, 12), sha1);
        plugin.getLogger().info("Paczka serwera hostowana pod " + serwerowa.url());
    }

    private String adresZSerwera() {
        String ip = Bukkit.getIp();
        if (ip != null && !ip.isBlank() && !ip.equals("0.0.0.0")) return ip;
        return wykrytyAdres;
    }

    private String wykryjPubliczneIp() {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ipify.org")).timeout(Duration.ofSeconds(4)).build();
            String ip = client.send(req, HttpResponse.BodyHandlers.ofString()).body().trim();
            return ip.matches("[0-9a-fA-F.:]{3,45}") ? ip : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Minimalny serwer HTTP: wyłącznie GET /pack.zip z bieżącą paczką, nic więcej. */
    private void uruchomHttp(int port) throws IOException {
        if (http != null && httpPort == port) return;
        zatrzymajHttp();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/pack.zip", exchange -> {
            try (exchange) {
                byte[] body = dane;
                if (!"GET".equals(exchange.getRequestMethod()) || body == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Mainplugins-ResourcePack-HTTP");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        http = server;
        httpPort = port;
    }

    private void zatrzymajHttp() {
        if (http != null) {
            http.stop(0);
            http = null;
            httpPort = -1;
        }
    }

    /** Wyłączenie pluginu - zwalnia port. */
    public synchronized void zamknij() {
        zatrzymajHttp();
    }

    /** Wysyła graczowi obie paczki (bazowa pod spodem, serwerowa na wierzchu). */
    public void wyslij(Player player) {
        Paczka b = bazowa;
        Paczka s = serwerowa;
        try {
            if (b != null) player.addResourcePack(BAZOWA_ID, b.url(), b.hashBytes(), null, wymagany);
            if (s != null) player.addResourcePack(SERWEROWA_ID, s.url(), s.hashBytes(), null, wymagany);
        } catch (NoSuchMethodError starszySerwer) {
            // Serwer bez wielu paczek naraz (przed 1.20.3) - wysyłamy tylko jedną.
            Paczka jedna = s != null ? s : b;
            if (jedna != null) player.setResourcePack(jedna.url(), jedna.hash(), wymagany);
        }
    }

    /** Po wgraniu nowej paczki: przeładowanie i wysłanie graczom online. */
    public void przeladujIWyslij() {
        wczytaj();
        Bukkit.getOnlinePlayers().forEach(this::wyslij);
    }

    public String opisStanu() {
        Paczka s = serwerowa;
        if (s != null) return "Paczka serwera: " + s.url();
        return dane != null ? "Paczka serwera wczytana - ustalam adres..." : "Brak paczki serwera (resourcepack/pack.zip).";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        wyslij(event.getPlayer());
    }
}
