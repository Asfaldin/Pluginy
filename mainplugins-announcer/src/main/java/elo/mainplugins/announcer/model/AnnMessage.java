package elo.mainplugins.announcer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Jedna wiadomość z ogloszenia.yml. Akceptuje dwa zapisy:
 * <ul>
 *   <li>goły string: {@code - "&aTekst"} - reszta pól domyślna,</li>
 *   <li>mapa: {@code - { text: "&aTekst", channels: [CHAT, ACTIONBAR], ... }}.</li>
 * </ul>
 * Pola pozostawione puste dziedziczą z grupy (kanały, dźwięk, discord).
 */
public final class AnnMessage {

    public final String id;
    public final String textLegacy;
    public final int weight;
    public final boolean enabled;

    // --- warunki widoczności (per gracz / stan serwera) ---
    public final String permission;      // "" = wszyscy
    public final List<String> worlds;    // pusty = wszystkie światy
    public final int minPlayers;         // 0 = bez progu
    public final String condition;       // "" lub "%papi% >= 5"

    // --- prezentacja ---
    public final List<Channel> channels; // pusty = dziedzicz z grupy
    public final String sound;           // null = dziedzicz; "" = brak dźwięku
    public final boolean minimessage;    // true = tekst w MiniMessage zamiast kodów &
    public final boolean center;         // true = wyśrodkuj na czacie
    public final Boolean discord;        // null = dziedzicz z grupy

    public final int titleFadeIn, titleStay, titleFadeOut;   // ticki (TITLE)
    public final String bossbarColor;                        // np. "BLUE" (BOSSBAR)
    public final int bossbarSeconds;                         // ile sekund wisi pasek

    // --- interaktywność ---
    public final String clickType;   // "" | RUN_COMMAND | SUGGEST_COMMAND | OPEN_URL
    public final String clickValue;
    public final String hover;       // "" = brak

    // --- "kliknij aby odebrać" ---
    public final ClaimSpec claim;

    private AnnMessage(String id, String textLegacy, int weight, boolean enabled, String permission,
                      List<String> worlds, int minPlayers, String condition, List<Channel> channels,
                      String sound, boolean minimessage, boolean center, Boolean discord,
                      int titleFadeIn, int titleStay, int titleFadeOut, String bossbarColor, int bossbarSeconds,
                      String clickType, String clickValue, String hover, ClaimSpec claim) {
        this.id = id;
        this.textLegacy = textLegacy;
        this.weight = weight;
        this.enabled = enabled;
        this.permission = permission;
        this.worlds = worlds;
        this.minPlayers = minPlayers;
        this.condition = condition;
        this.channels = channels;
        this.sound = sound;
        this.minimessage = minimessage;
        this.center = center;
        this.discord = discord;
        this.titleFadeIn = titleFadeIn;
        this.titleStay = titleStay;
        this.titleFadeOut = titleFadeOut;
        this.bossbarColor = bossbarColor;
        this.bossbarSeconds = bossbarSeconds;
        this.clickType = clickType;
        this.clickValue = clickValue;
        this.hover = hover;
        this.claim = claim;
    }

    /** Szybka wiadomość tylko z tekstem - pod /announce i onboarding. */
    public static AnnMessage plain(String textLegacy, List<Channel> channels) {
        return new AnnMessage("adhoc", textLegacy, 1, true, "", List.of(), 0, "",
                channels == null ? List.of() : channels, null, false, false, null,
                10, 60, 10, "BLUE", 8, "", "", "", null);
    }

    @SuppressWarnings("unchecked")
    public static AnnMessage parse(Object raw, int index) {
        if (raw instanceof String s) {
            return plain(s, List.of());
        }
        if (!(raw instanceof Map<?, ?> m)) return null;
        Object text = m.get("text");
        if (text == null) return null;

        String id = str(m.get("id"), "msg-" + index);
        int weight = intOr(m.get("weight"), 1);
        boolean enabled = boolOr(m.get("enabled"), true);
        String permission = str(m.get("permission"), "");
        List<String> worlds = strList(m.get("worlds"));
        int minPlayers = intOr(m.get("min-players"), 0);
        String condition = str(m.get("condition"), "");

        List<Channel> channels = new ArrayList<>();
        Object ch = m.get("channels");
        if (ch instanceof List<?> l) {
            for (Object o : l) {
                Channel c = Channel.from(String.valueOf(o));
                if (c != null) channels.add(c);
            }
        } else if (m.get("type") != null) { // wsteczna zgodność: type: TITLE
            Channel c = Channel.from(String.valueOf(m.get("type")));
            if (c != null) channels.add(c);
        }

        Object soundObj = m.get("sound");
        String sound = soundObj == null ? null : soundObj.toString();
        boolean minimessage = boolOr(m.get("minimessage"), false);
        boolean center = boolOr(m.get("center"), false);
        Object discordObj = m.get("discord");
        Boolean discord = discordObj == null ? null : Boolean.parseBoolean(discordObj.toString());

        int fin = intOr(m.get("title-fade-in"), 10);
        int stay = intOr(m.get("title-stay"), 60);
        int fout = intOr(m.get("title-fade-out"), 10);
        String bbColor = str(m.get("bossbar-color"), "BLUE").toUpperCase(Locale.ROOT);
        int bbSec = intOr(m.get("bossbar-seconds"), 8);

        String clickType = str(m.get("click"), "").toUpperCase(Locale.ROOT);
        String clickValue = str(m.get("click-value"), "");
        String hover = str(m.get("hover"), "");

        ClaimSpec claim = m.get("claim") instanceof Map<?, ?> cm ? ClaimSpec.fromMap(cm) : null;

        return new AnnMessage(id, text.toString(), Math.max(1, weight), enabled, permission, worlds,
                Math.max(0, minPlayers), condition, channels, sound, minimessage, center, discord,
                fin, stay, fout, bbColor, Math.max(1, bbSec), clickType, clickValue, hover, claim);
    }

    private static String str(Object o, String def) { return o == null ? def : o.toString(); }
    private static int intOr(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static boolean boolOr(Object o, boolean def) { return o == null ? def : Boolean.parseBoolean(o.toString()); }
    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null) out.add(x.toString());
        return out;
    }
}
