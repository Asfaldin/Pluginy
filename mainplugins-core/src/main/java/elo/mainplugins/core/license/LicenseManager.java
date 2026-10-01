package elo.mainplugins.core.license;

import elo.mainplugins.core.api.LicenseService;
import elo.mainplugins.license.LicenseToken;
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
 *   url: "https://api.rsmc-network.pl"
 * keys:
 *   tools: "MPX-XXXXX-XXXXX-XXXXX"
 * }</pre>
 *
 * BEZPIECZEŃSTWO: ważna licencja to wyłącznie odpowiedź z tokenem PODPISANYM przez serwer
 * licencji (Ed25519, weryfikacja w {@link LicenseToken} z mainplugins-license). Samo
 * {@code "valid": true} nic nie znaczy - inaczej wystarczyłoby wskazać w api.url własny
 * serwer. Token zawiera plugin, id serwera i jednorazowy nonce z tego zapytania (nie da się
 * odtworzyć starej odpowiedzi) oraz datę ważności.
 *
 * Odporność na chwilową niedostępność serwera licencji: ostatni podpisany token trzymamy
 * w {@code license-cache.yml} i jest on ważny do swojej daty wygaśnięcia (7 dni od
 * wydania). Edycja pliku nic nie da - zmieniony token nie przejdzie weryfikacji podpisu.
 * Jawna odpowiedź "invalid" (klucz odwołany/przypisany gdzie indziej) od razu kasuje
 * zapisany token.
 *
 * Płatne pluginy nie ufają samemu {@link #isLicensed}: pobierają {@link #licenseProof}
 * i sprawdzają podpis własną kopią weryfikatora (LicenseGuard), więc podmieniony Core
 * niczego nie odblokuje.
 */
public class LicenseManager implements LicenseService {

    private static final Duration RECHECK_INTERVAL = Duration.ofHours(6);
    private static final String DEFAULT_API_URL = "https://api.rsmc-network.pl";
    private static final Pattern VALID_PATTERN = Pattern.compile("\"valid\"\\s*:\\s*(true|false)");
    private static final Pattern REASON_PATTERN = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"token\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"");
    private static final Pattern SIGNATURE_PATTERN = Pattern.compile("\"signature\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"");

    /** proof = "token.podpis" albo null, gdy licencja nieważna. */
    private record CacheEntry(boolean valid, long checkedAtMillis, String proof) {}

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
        if (LicenseToken.isDevKey()) {
            plugin.getLogger().warning("Jary zbudowane z TESTOWYM kluczem licencji (-Dlicense.publicKey) - przyjmują tylko licencje z lokalnego license-servera. Nie dawaj ich klientom.");
        }
    }

    private void loadConfig() {
        boolean isNew = !configFile.exists();
        if (isNew) {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            FileConfiguration fresh = new YamlConfiguration();
            fresh.set("server.id", UUID.randomUUID().toString());
            fresh.set("api.url", DEFAULT_API_URL);
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
        if (apiUrl == null || apiUrl.isBlank() || apiUrl.contains("your-license-server.example.com")) {
            apiUrl = DEFAULT_API_URL;
        }
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

        CacheEntry cached = memoryCache.get(pluginId);
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.checkedAtMillis()) < RECHECK_INTERVAL.toMillis()) {
            // Nawet świeży wpis sprawdzamy pod kątem wygaśnięcia tokenu.
            return cached.valid() && proofValid(cached.proof(), pluginId, now);
        }

        return validateLive(pluginId, key, now);
    }

    @Override
    public String licenseProof(String pluginId) {
        if (!isLicensed(pluginId)) return null;
        CacheEntry cached = memoryCache.get(pluginId);
        return cached != null && proofValid(cached.proof(), pluginId, System.currentTimeMillis()) ? cached.proof() : null;
    }

    /** Podpis się zgadza, token jest dla tego pluginu i tego serwera i jeszcze nie wygasł. */
    private boolean proofValid(String proof, String pluginId, long now) {
        LicenseToken.Claims claims = LicenseToken.verifyProof(proof);
        return claims != null && claims.validFor(pluginId, now) && claims.serverId().equals(serverId);
    }

    private boolean validateLive(String pluginId, String key, long now) {
        String nonce = UUID.randomUUID().toString();
        try {
            String body = "{\"key\":\"" + escape(key) + "\",\"plugin\":\"" + escape(pluginId) + "\",\"serverId\":\"" + escape(serverId)
                    + "\",\"nonce\":\"" + nonce + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl.replaceAll("/+$", "") + "/api/validate"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("Serwer licencyjny zwrócił HTTP " + response.statusCode() + " dla '" + pluginId + "'.");
                return fallbackToCachedToken(pluginId, now, "http " + response.statusCode());
            }

            String responseBody = response.body();
            Matcher validMatcher = VALID_PATTERN.matcher(responseBody);
            if (!validMatcher.find()) {
                plugin.getLogger().warning("Nie udało się odczytać odpowiedzi serwera licencyjnego dla '" + pluginId + "'.");
                return fallbackToCachedToken(pluginId, now, "unparseable response");
            }

            if (!Boolean.parseBoolean(validMatcher.group(1))) {
                plugin.getLogger().warning("Licencja dla '" + pluginId + "' nieważna: " + extract(REASON_PATTERN, responseBody, "(brak powodu w odpowiedzi)"));
                memoryCache.put(pluginId, new CacheEntry(false, now, null));
                persistProof(pluginId, null);
                return false;
            }

            // "valid": true liczy się tylko z podpisanym tokenem dla TEGO zapytania.
            String token = extract(TOKEN_PATTERN, responseBody, null);
            String signature = extract(SIGNATURE_PATTERN, responseBody, null);
            LicenseToken.Claims claims = LicenseToken.verify(token, signature);
            boolean genuine = claims != null
                    && claims.validFor(pluginId, now)
                    && claims.serverId().equals(serverId)
                    && claims.nonce().equals(nonce);
            if (!genuine) {
                plugin.getLogger().severe("Odpowiedź serwera licencji dla '" + pluginId + "' nie ma prawidłowego podpisu - odrzucona"
                        + " (sprawdź api.url w license.yml; akceptowany jest tylko oficjalny serwer licencji).");
                memoryCache.put(pluginId, new CacheEntry(false, now, null));
                return false;
            }
            String proof = token + "." + signature;
            memoryCache.put(pluginId, new CacheEntry(true, now, proof));
            persistProof(pluginId, proof);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            plugin.getLogger().warning("Serwer licencyjny nieosiągalny przy sprawdzaniu '" + pluginId + "': " + e.getMessage());
            return fallbackToCachedToken(pluginId, now, "network error");
        }
    }

    private static String extract(Pattern pattern, String body, String fallback) {
        Matcher m = pattern.matcher(body);
        return m.find() ? m.group(1) : fallback;
    }

    /** Serwer licencji chwilowo niedostępny - ważny jest ostatni podpisany token do swojej daty wygaśnięcia. */
    private boolean fallbackToCachedToken(String pluginId, long now, String cause) {
        FileConfiguration cacheCfg = YamlConfiguration.loadConfiguration(cacheFile);
        String proof = cacheCfg.getString("proofs." + pluginId);
        boolean valid = proofValid(proof, pluginId, now);
        if (valid) {
            LicenseToken.Claims claims = LicenseToken.verifyProof(proof);
            plugin.getLogger().warning("Licencja '" + pluginId + "' ważna z zapisanego tokenu (" + cause + ") do "
                    + java.time.Instant.ofEpochMilli(claims.expiresAt()));
        } else {
            plugin.getLogger().severe("Licencja '" + pluginId + "' nieważna: brak połączenia z serwerem licencyjnym (" + cause
                    + ") i brak ważnego zapisanego tokenu.");
        }
        memoryCache.put(pluginId, new CacheEntry(valid, now, valid ? proof : null));
        return valid;
    }

    private void persistProof(String pluginId, String proof) {
        FileConfiguration cacheCfg = YamlConfiguration.loadConfiguration(cacheFile);
        cacheCfg.set("proofs." + pluginId, proof);
        cacheCfg.set("lastValidAt", null); // stary, niepodpisany format - już nieużywany
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
