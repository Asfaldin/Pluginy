package elo.mainplugins.core.license;

import elo.mainplugins.core.api.LicenseService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Weryfikuje klucze licencyjne płatnych pluginów przeciwko serwerowi licencyjnemu
 * (patrz repo {@code license-server/}). Konfiguracja w {@code license.yml}
 * (folder danych Core):
 * <pre>{@code
 * server:
 *   id: "<losowe UUID, generowane automatycznie - NIE edytować ręcznie>"
 * api:
 *   url: "https://twoj-serwer-licencyjny.example.com"
 * keys:
 *   tools: "MPX-XXXXX-XXXXX-XXXXX"
 * }</pre>
 *
 * Brak zewnętrznej biblioteki JSON w tym module celowo - odpowiedź serwera ma
 * stały, prosty kształt ({@code {"valid":bool,"reason":"..."}}), więc parsujemy
 * ją ręcznie zamiast dokładać zależność do mainplugins-core.
 *
 * Odporność na chwilową niedostępność serwera licencyjnego (np. darmowy hosting
 * usypiający się po bezczynności): jeśli żywe zapytanie się nie powiedzie, a
 * poprzednia udana weryfikacja tego klucza była w oknie karencji ({@link #GRACE_PERIOD}),
 * traktujemy klucz jako nadal ważny - klient płacący nie traci dostępu przez
 * przejściowy problem sieciowy. Jawna odpowiedź "invalid" z serwera (klucz
 * odwołany/przypisany gdzie indziej) NIE korzysta z karencji - to świadoma decyzja
 * operatora, nie awaria.
 */
public class LicenseManager implements LicenseService {

    private static final Duration RECHECK_INTERVAL = Duration.ofHours(6);
    private static final Duration GRACE_PERIOD = Duration.ofHours(72);
    private static final Pattern VALID_PATTERN = Pattern.compile("\"valid\"\\s*:\\s*(true|false)");
    private static final Pattern REASON_PATTERN = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"");

    private record CacheEntry(boolean valid, long checkedAtMillis) {}

    private final Plugin plugin;
    private final File configFile;
    private final File cacheFile;
    private final HttpClient httpClient;
    private final Map<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();

    private FileConfiguration config;
    private String serverId;
    private String apiUrl;

    public LicenseManager(Plugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "license.yml");
        this.cacheFile = new File(plugin.getDataFolder(), "license-cache.yml");
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
        loadConfig();
        schedulePeriodicRecheck();
    }

    private void loadConfig() {
        boolean isNew = !configFile.exists();
        if (isNew) {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            FileConfiguration fresh = new YamlConfiguration();
            fresh.set("server.id", UUID.randomUUID().toString());
            fresh.set("api.url", "https://your-license-server.example.com");
            fresh.createSection("keys");
            try {
                fresh.save(configFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Nie udało się utworzyć license.yml: " + e.getMessage());
            }
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        serverId = config.getString("server.id");
        if (serverId == null || serverId.isBlank()) {
            serverId = UUID.randomUUID().toString();
            config.set("server.id", serverId);
            saveConfigQuietly();
        }
        apiUrl = config.getString("api.url", "");
    }

    private void saveConfigQuietly() {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się zapisać license.yml: " + e.getMessage());
        }
    }

    @Override
    public boolean isLicensed(String pluginId) {
        String key = config.getString("keys." + pluginId);
        if (key == null || key.isBlank()) {
            plugin.getLogger().warning("Brak klucza licencyjnego dla '" + pluginId + "' w license.yml (sekcja keys) - plugin uznany za nielicencjonowany.");
            return false;
        }
        if (apiUrl == null || apiUrl.isBlank() || apiUrl.contains("your-license-server.example.com")) {
            plugin.getLogger().warning("license.yml: api.url nie jest skonfigurowany - nie można zweryfikować licencji dla '" + pluginId + "'.");
            return false;
        }

        CacheEntry cached = memoryCache.get(pluginId);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.checkedAtMillis()) < RECHECK_INTERVAL.toMillis()) {
            return cached.valid();
        }

        return validateLive(pluginId, key, now);
    }

    private boolean validateLive(String pluginId, String key, long now) {
        try {
            String body = "{\"key\":\"" + escape(key) + "\",\"plugin\":\"" + escape(pluginId) + "\",\"serverId\":\"" + escape(serverId) + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl.replaceAll("/+$", "") + "/api/validate"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("Serwer licencyjny zwrócił HTTP " + response.statusCode() + " dla '" + pluginId + "'.");
                return fallbackToGrace(pluginId, now, "http " + response.statusCode());
            }

            Matcher validMatcher = VALID_PATTERN.matcher(response.body());
            if (!validMatcher.find()) {
                plugin.getLogger().warning("Nie udało się odczytać odpowiedzi serwera licencyjnego dla '" + pluginId + "': " + response.body());
                return fallbackToGrace(pluginId, now, "unparseable response");
            }

            boolean valid = Boolean.parseBoolean(validMatcher.group(1));
            String reason = extractReason(response.body());
            memoryCache.put(pluginId, new CacheEntry(valid, now));
            if (valid) {
                persistLastValid(pluginId, now);
            } else {
                plugin.getLogger().warning("Licencja dla '" + pluginId + "' nieważna: " + reason);
                clearPersistedValid(pluginId);
            }
            return valid;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            plugin.getLogger().warning("Serwer licencyjny nieosiągalny przy sprawdzaniu '" + pluginId + "': " + e.getMessage());
            return fallbackToGrace(pluginId, now, "network error");
        }
    }

    private static String extractReason(String body) {
        Matcher m = REASON_PATTERN.matcher(body);
        return m.find() ? m.group(1) : "(brak powodu w odpowiedzi)";
    }

    private boolean fallbackToGrace(String pluginId, long now, String cause) {
        FileConfiguration cacheCfg = YamlConfiguration.loadConfiguration(cacheFile);
        long lastValidAt = cacheCfg.getLong("lastValidAt." + pluginId, -1);
        boolean withinGrace = lastValidAt > 0 && (now - lastValidAt) < GRACE_PERIOD.toMillis();
        if (withinGrace) {
            plugin.getLogger().warning("Licencja '" + pluginId + "' w okresie karencji (" + cause + ") - traktowana jako ważna do "
                    + java.time.Instant.ofEpochMilli(lastValidAt + GRACE_PERIOD.toMillis()));
        } else {
            plugin.getLogger().severe("Licencja '" + pluginId + "' nieważna: brak połączenia z serwerem licencyjnym (" + cause + ") i okres karencji minął lub nigdy nie był aktywowany.");
        }
        memoryCache.put(pluginId, new CacheEntry(withinGrace, now));
        return withinGrace;
    }

    private void persistLastValid(String pluginId, long now) {
        FileConfiguration cacheCfg = YamlConfiguration.loadConfiguration(cacheFile);
        cacheCfg.set("lastValidAt." + pluginId, now);
        try {
            cacheCfg.save(cacheFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się zapisać license-cache.yml: " + e.getMessage());
        }
    }

    private void clearPersistedValid(String pluginId) {
        FileConfiguration cacheCfg = YamlConfiguration.loadConfiguration(cacheFile);
        cacheCfg.set("lastValidAt." + pluginId, null);
        try {
            cacheCfg.save(cacheFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się zapisać license-cache.yml: " + e.getMessage());
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Odświeża w tle wszystkie skonfigurowane klucze co {@link #RECHECK_INTERVAL}, żeby np. odwołanie licencji zadziałało bez restartu serwera klienta. */
    private void schedulePeriodicRecheck() {
        long ticks = RECHECK_INTERVAL.toSeconds() * 20L;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            ConfigurationSection keys = config.getConfigurationSection("keys");
            if (keys == null) return;
            for (String pluginId : keys.getKeys(false)) {
                validateLive(pluginId, config.getString("keys." + pluginId), System.currentTimeMillis());
            }
        }, ticks, ticks);
    }
}
