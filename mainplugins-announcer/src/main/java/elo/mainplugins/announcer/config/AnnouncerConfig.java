package elo.mainplugins.announcer.config;

import elo.mainplugins.announcer.model.AnnGroup;
import elo.mainplugins.announcer.model.AnnMessage;
import elo.mainplugins.announcer.model.EventSpec;
import elo.mainplugins.announcer.model.OnboardingStep;
import elo.mainplugins.announcer.model.Ordering;
import elo.mainplugins.announcer.model.ScheduleWindow;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Cały ogloszenia.yml sparsowany do modelu. Tworzy plik z sensownym przykładem
 * przy pierwszym uruchomieniu; przy kolejnych tylko czyta (ręczne/aplikacyjne
 * zmiany + /@reloadannouncer). Nic tu nie planuje ani nie wysyła - to robią
 * GroupRunner / EventBridge / OnboardingManager na podstawie tego obiektu.
 */
public final class AnnouncerConfig {

    public final int intervalSeconds;
    public final boolean placeholdersEnabled;
    public final LocalTime quietFrom, quietTo;

    public final String discordWebhookUrl;
    public final String discordUsername;
    public final String discordAvatarUrl;

    public final List<AnnGroup> groups;
    public final Map<String, EventSpec> events;

    public final boolean onboardingEnabled;
    public final List<OnboardingStep> onboarding;

    private AnnouncerConfig(int intervalSeconds, boolean placeholdersEnabled, LocalTime quietFrom, LocalTime quietTo,
                            String discordWebhookUrl, String discordUsername, String discordAvatarUrl,
                            List<AnnGroup> groups, Map<String, EventSpec> events,
                            boolean onboardingEnabled, List<OnboardingStep> onboarding) {
        this.intervalSeconds = intervalSeconds;
        this.placeholdersEnabled = placeholdersEnabled;
        this.quietFrom = quietFrom;
        this.quietTo = quietTo;
        this.discordWebhookUrl = discordWebhookUrl;
        this.discordUsername = discordUsername;
        this.discordAvatarUrl = discordAvatarUrl;
        this.groups = groups;
        this.events = events;
        this.onboardingEnabled = onboardingEnabled;
        this.onboarding = onboarding;
    }

    public boolean isQuietNow() {
        if (quietFrom == null && quietTo == null) return false;
        return ScheduleWindow.inTimeRange(LocalTime.now(), quietFrom, quietTo);
    }

    public boolean discordConfigured() {
        return discordWebhookUrl != null && !discordWebhookUrl.isBlank();
    }

    // ------------------------------------------------------------------ wczytywanie

    public static AnnouncerConfig load(File file, Logger log, Supplier<InputStream> bundled) {
        if (!file.exists()) {
            writeDefault(file, log, bundled);
        }
        FileConfiguration c = YamlConfiguration.loadConfiguration(file);

        int interval = Math.max(20, c.getInt("interval-seconds", 300));
        boolean papi = c.getBoolean("placeholders.enabled", true);
        LocalTime[] quiet = ScheduleWindow.parseRange(c.getString("quiet-hours", ""));

        String hook = c.getString("discord.webhook-url", "");
        String dUser = c.getString("discord.username", "Serwer");
        String dAvatar = c.getString("discord.avatar-url", "");

        Ordering defaultOrdering = Ordering.from(c.getString("default-order", "SEQUENTIAL"), Ordering.SEQUENTIAL);

        List<AnnGroup> groups = new ArrayList<>();

        // (1) niejawna grupa "default" z płaskiej listy "messages"
        List<AnnMessage> flat = new ArrayList<>();
        int i = 0;
        for (Object o : c.getList("messages", List.of())) {
            AnnMessage m = AnnMessage.parse(o, i++);
            if (m != null) flat.add(m);
        }
        if (!flat.isEmpty()) {
            groups.add(new AnnGroup("default", 0, defaultOrdering, true, "",
                    List.of(), "", false, false, ScheduleWindow.ALWAYS, flat));
        }

        // (2) grupy z "groups"
        ConfigurationSection groupsSec = c.getConfigurationSection("groups");
        if (groupsSec != null) {
            for (String key : groupsSec.getKeys(false)) {
                ConfigurationSection gs = groupsSec.getConfigurationSection(key);
                if (gs != null) {
                    groups.add(AnnGroup.fromMap(key, deep(gs), defaultOrdering));
                }
            }
        }

        // (3) events
        Map<String, EventSpec> events = new LinkedHashMap<>();
        ConfigurationSection eventsSec = c.getConfigurationSection("events");
        if (eventsSec != null) {
            for (String key : eventsSec.getKeys(false)) {
                ConfigurationSection es = eventsSec.getConfigurationSection(key);
                if (es != null) {
                    events.put(key, EventSpec.fromMap(key, deep(es)));
                }
            }
        }

        // (4) onboarding
        boolean onboardingEnabled = c.getBoolean("onboarding.enabled", false);
        List<OnboardingStep> onboarding = new ArrayList<>();
        for (Object o : c.getList("onboarding.messages", List.of())) {
            if (o instanceof Map<?, ?> om) {
                OnboardingStep step = OnboardingStep.fromMap(om);
                if (step != null) onboarding.add(step);
            }
        }

        return new AnnouncerConfig(interval, papi, quiet[0], quiet[1], hook, dUser, dAvatar,
                groups, events, onboardingEnabled, onboarding);
    }

    /** Rekurencyjnie zamienia ConfigurationSection (i zagnieżdżone) na zwykłe Mapy - modele parsują Mapy, nie sekcje. */
    private static Map<String, Object> deep(ConfigurationSection sec) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : sec.getKeys(false)) {
            Object v = sec.get(k);
            out.put(k, v instanceof ConfigurationSection cs ? deep(cs) : v);
        }
        return out;
    }

    // ------------------------------------------------------------------ plik domyślny

    /**
     * Pierwsze uruchomienie: kopiuje dołączony pakiet (defaults/<język>/ogloszenia.yml - powitanie z najważniejszymi
     * komendami, porady, przydatne eventy i pomoc dla nowych graczy; ten sam co szablon "Gotowy" w aplikacji).
     * Bez pakietu w jarze plik nie powstaje, a plugin startuje bez ogłoszeń.
     */
    public static void writeDefault(File file, Logger log, Supplier<InputStream> bundled) {
        try (InputStream in = bundled.get()) {
            if (in == null) {
                log.warning("Brak domyslnego ogloszenia.yml w jarze - start bez ogloszen.");
                return;
            }
            file.getParentFile().mkdirs();
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warning("Nie mozna zapisac ogloszenia.yml: " + e.getMessage());
        }
    }
}
