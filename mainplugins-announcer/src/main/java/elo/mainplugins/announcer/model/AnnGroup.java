package elo.mainplugins.announcer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Grupa ogłoszeń z własnym, niezależnym harmonogramem (osobny BukkitTask - patrz
 * GroupRunner). "messages" bez grupy trafiają do niejawnej grupy "default".
 */
public final class AnnGroup {

    public final String name;
    public final int intervalSeconds;      // <=0 => użyj globalnego interval-seconds
    public final Ordering ordering;
    public final boolean noRepeat;         // nie powtarzaj tej samej wiadomości dwa razy pod rząd
    public final String prefixLegacy;      // doklejane przed każdą wiadomością (kanał CHAT)
    public final List<Channel> channels;   // domyślne kanały dla wiadomości bez własnych
    public final String sound;             // domyślny dźwięk ("" = brak)
    public final boolean discord;          // domyślne mirrorowanie na Discord
    public final boolean frame;            // linie ozdobne przed/po (kanał CHAT)
    public final ScheduleWindow schedule;
    public final List<AnnMessage> messages;

    public AnnGroup(String name, int intervalSeconds, Ordering ordering, boolean noRepeat, String prefixLegacy,
                    List<Channel> channels, String sound, boolean discord, boolean frame,
                    ScheduleWindow schedule, List<AnnMessage> messages) {
        this.name = name;
        this.intervalSeconds = intervalSeconds;
        this.ordering = ordering;
        this.noRepeat = noRepeat;
        this.prefixLegacy = prefixLegacy;
        this.channels = channels;
        this.sound = sound;
        this.discord = discord;
        this.frame = frame;
        this.schedule = schedule;
        this.messages = messages;
    }

    public int effectiveIntervalSeconds(int globalDefault) {
        return intervalSeconds > 0 ? intervalSeconds : globalDefault;
    }

    /** Kanały wiadomości z fallbackiem: własne -> grupy -> [CHAT]. */
    public List<Channel> channelsFor(AnnMessage m) {
        if (m.channels != null && !m.channels.isEmpty()) return m.channels;
        if (channels != null && !channels.isEmpty()) return channels;
        return List.of(Channel.CHAT);
    }

    public String soundFor(AnnMessage m) {
        if (m.sound != null) return m.sound;   // "" = świadomy brak
        return sound;
    }

    public boolean discordFor(AnnMessage m) {
        return m.discord != null ? m.discord : discord;
    }

    @SuppressWarnings("unchecked")
    public static AnnGroup fromMap(String name, Map<?, ?> m, Ordering defaultOrdering) {
        int interval = m.get("interval") instanceof Number n ? n.intValue() : 0;
        Ordering ordering = Ordering.from(str(m.get("order")), defaultOrdering);
        boolean noRepeat = bool(m.get("no-repeat"), true);
        String prefix = str(m.get("prefix"));
        String sound = str(m.get("sound"));
        boolean discord = bool(m.get("discord"), false);
        boolean frame = bool(m.get("frame"), false);

        List<Channel> channels = new ArrayList<>();
        if (m.get("channels") instanceof List<?> l) {
            for (Object o : l) {
                Channel c = Channel.from(String.valueOf(o));
                if (c != null) channels.add(c);
            }
        }

        ScheduleWindow schedule = ScheduleWindow.ALWAYS;
        if (m.get("schedule") instanceof Map<?, ?> sm) {
            List<String> days = new ArrayList<>();
            if (sm.get("days") instanceof List<?> dl) for (Object o : dl) if (o != null) days.add(o.toString());
            schedule = ScheduleWindow.of(days, str(sm.get("time-range")));
        }

        List<AnnMessage> messages = new ArrayList<>();
        Object msgs = m.get("messages");
        if (msgs instanceof List<?> l) {
            int i = 0;
            for (Object o : l) {
                AnnMessage parsed = AnnMessage.parse(o, i++);
                if (parsed != null) messages.add(parsed);
            }
        }
        return new AnnGroup(name, interval, ordering, noRepeat, prefix, channels, sound, discord, frame, schedule, messages);
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static boolean bool(Object o, boolean def) { return o == null ? def : Boolean.parseBoolean(o.toString()); }
}
