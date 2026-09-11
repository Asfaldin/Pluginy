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
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public static AnnouncerConfig load(File file, Logger log) {
        if (!file.exists()) {
            writeDefault(file, log);
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

    private static Map<String, Object> msg(String text, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> ev(boolean enabled, String text, List<String> channels, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("text", text);
        m.put("channels", channels);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }

    public static void writeDefault(File file, Logger log) {
        file.getParentFile().mkdirs();
        YamlConfiguration c = new YamlConfiguration();

        c.options().setHeader(List.of(
                "Ogloszenia serwera. Zmiany na zywo: /@reloadannouncer (bez restartu).",
                "Kolory kodami & (np. &aTekst). Ustaw minimessage: true na wpisie, by uzyc <gradient>/<click>.",
                "",
                "channels: dowolny podzbior [CHAT, ACTIONBAR, TITLE, BOSSBAR].",
                "Warunki na wpisie: permission, worlds, min-players, condition (\"%papi% >= 5\").",
                "Interaktywnosc: click (RUN_COMMAND|SUGGEST_COMMAND|OPEN_URL) + click-value + hover.",
                "Nagroda 'kliknij aby odebrac': blok claim: { limit, window-seconds, commands: [...] } (%player% -> nick)."
        ));

        c.set("interval-seconds", 300);
        c.set("default-order", "SEQUENTIAL");
        c.set("placeholders.enabled", true);
        c.set("quiet-hours", "");

        c.set("discord.webhook-url", "");
        c.set("discord.username", "Serwer");
        c.set("discord.avatar-url", "");

        // plaska lista (wsteczna zgodnosc ze starym announcerem)
        c.set("messages", List.of(
                msg("&aWitaj na serwerze! Wpisz &e/menu&a, aby zobaczyc panel gracza.",
                        "click", "SUGGEST_COMMAND", "click-value", "/menu", "hover", "&7Kliknij, aby wpisac /menu"),
                msg("&6Nie masz jeszcze wyspy? Wpisz &e/is&6, aby ja zalozyc!", "interval", 900)
        ));

        // grupy
        Map<String, Object> tips = new LinkedHashMap<>();
        tips.put("interval", 420);
        tips.put("order", "RANDOM");
        tips.put("no-repeat", true);
        tips.put("prefix", "&8[&bPorada&8] &7");
        tips.put("channels", List.of("CHAT"));
        tips.put("messages", List.of(
                msg("Sprzedasz lup w &e/targ&7, a szybkie zakupy zrobisz w &e/sklep&7."),
                msg("Postepy i nagrody znajdziesz w &e/osiagniecia&7."),
                msg("Wbij na Discorda: &e/discord&7.", "click", "RUN_COMMAND", "click-value", "/discord")
        ));

        Map<String, Object> promo = new LinkedHashMap<>();
        promo.put("interval", 1800);
        promo.put("order", "SEQUENTIAL");
        promo.put("channels", List.of("CHAT", "ACTIONBAR"));
        promo.put("discord", false);
        promo.put("schedule", Map.of("days", List.of("SATURDAY", "SUNDAY"), "time-range", ""));
        promo.put("messages", List.of(
                msg("&6&lWEEKEND&r &ena serwerze - podbite dropy i szczescie w skrzynkach!"),
                msg("&aZaproszony znajomy = &e+bonus&a dla Was obu.", "permission", "")
        ));

        Map<String, Object> reward = new LinkedHashMap<>();
        reward.put("interval", 3600);
        reward.put("order", "SEQUENTIAL");
        reward.put("channels", List.of("CHAT"));
        reward.put("messages", List.of(
                msg("&e&lPREZENT!&r &7Pierwsze 10 osob dostaje nagrode.",
                        "claim", Map.of(
                                "limit", 10,
                                "window-seconds", 90,
                                "button", "&a&l[ODBIERZ]",
                                "commands", List.of("give %player% diamond 3")))
        ));

        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("porady", tips);
        groups.put("promocje", promo);
        groups.put("prezenty", reward);
        c.set("groups", groups);

        // events (reakcje na ServerAnnounceEvent + wbudowane)
        Map<String, Object> events = new LinkedHashMap<>();
        events.put("dungeon-boss", ev(true, "&6%player% &epokonal &6Wladce Lochu&e!", List.of("CHAT"),
                "sound", "entity.ender_dragon.growl", "discord", true));
        events.put("rare-fish", ev(true, "&b%player% zlowil &3%fish%&b (%rarity%)!", List.of("CHAT")));
        events.put("crate-legendary", ev(true, "&6%player% wylosowal &e%reward%&6 ze skrzynki!", List.of("CHAT", "ACTIONBAR"),
                "discord", true));
        events.put("achievement", ev(false, "&d%player% zdobyl osiagniecie &f%achievement%&d!", List.of("CHAT")));
        events.put("island-created", ev(true, "&a%player% zalozyl swoja wyspe!", List.of("CHAT")));
        events.put("first-join", ev(true, "&e&l+&r &7Przywitajcie &f%player%&7 - pierwszy raz na serwerze!", List.of("CHAT"),
                "sound", "entity.player.levelup"));
        events.put("vanilla-advancement", ev(false, "&7%player% zdobyl postep &f%advancement%&7.", List.of("CHAT")));
        events.put("death", ev(false, "&7%player% &8pozegnal sie z zyciem.", List.of("CHAT")));
        c.set("events", events);

        // onboarding
        c.set("onboarding.enabled", true);
        c.set("onboarding.messages", List.of(
                Map.of("delay-seconds", 25, "text", "&aMilo Cie widziec! Zacznij od &e/is&a - zalozysz wlasna wyspe.",
                        "channels", List.of("CHAT")),
                Map.of("delay-seconds", 150, "text", "&bZajrzyj do &e/sklep&b i &e/targ&b, gdy uzbierasz pierwsze surowce.",
                        "channels", List.of("CHAT")),
                Map.of("delay-seconds", 420, "text", "&dPotrzebujesz pomocy? Pisz na czacie lub wbij na &e/discord&d.",
                        "channels", List.of("CHAT"))
        ));

        try {
            c.save(file);
        } catch (IOException e) {
            log.warning("Nie mozna zapisac ogloszenia.yml: " + e.getMessage());
        }
    }
}
