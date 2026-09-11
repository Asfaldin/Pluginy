package elo.mainplugins.announcer.send;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Mirror ogłoszeń na kanał Discord przez zwykły webhook (bez biblioteki - goły
 * HTTP POST z JSON-em). Wyłączony, dopóki webhook-url w ogloszenia.yml jest pusty.
 * Wszystko leci asynchronicznie (HttpClient.sendAsync) - nigdy nie blokuje wątku
 * serwera.
 */
public final class DiscordWebhook {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Logger log;
    private volatile String url;
    private volatile String username;
    private volatile String avatarUrl;

    public DiscordWebhook(Logger log, String url, String username, String avatarUrl) {
        this.log = log;
        update(url, username, avatarUrl);
    }

    public void update(String url, String username, String avatarUrl) {
        this.url = url == null ? "" : url.trim();
        this.username = username == null ? "" : username;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
    }

    public boolean enabled() {
        return !url.isEmpty();
    }

    /** Wyślij zwykły tekst (już bez kodów kolorów). No-op, gdy webhook nie ustawiony. */
    public void send(String plainContent) {
        if (!enabled() || plainContent == null || plainContent.isBlank()) return;

        String json = "{"
                + "\"content\":\"" + escape(trim(plainContent, 1900)) + "\""
                + (username.isEmpty() ? "" : ",\"username\":\"" + escape(username) + "\"")
                + (avatarUrl.isEmpty() ? "" : ",\"avatar_url\":\"" + escape(avatarUrl) + "\"")
                + ",\"allowed_mentions\":{\"parse\":[]}"
                + "}";

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException badUrl) {
            log.warning("Discord webhook-url jest niepoprawny, pomijam: " + badUrl.getMessage());
            return;
        }

        http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(t -> {
                    log.warning("Nie udalo sie wyslac ogloszenia na Discord: " + t.getMessage());
                    return null;
                });
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
