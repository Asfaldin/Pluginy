package elo.mainplugins.announcer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jeden wpis z sekcji {@code events:} - reakcja na
 * {@link elo.mainplugins.core.api.ServerAnnounceEvent} (albo wbudowany event
 * Bukkita: "vanilla-advancement", "death", "first-join") o danym kluczu.
 */
public record EventSpec(
        String key,
        boolean enabled,
        String textLegacy,
        List<Channel> channels,
        String sound,
        int cooldownSeconds,   // globalny odstęp między kolejnymi broadcastami tego klucza
        boolean discord
) {
    public List<Channel> channelsOrChat() {
        return channels.isEmpty() ? List.of(Channel.CHAT) : channels;
    }

    public static EventSpec fromMap(String key, Map<?, ?> m) {
        boolean enabled = m.get("enabled") == null || Boolean.parseBoolean(m.get("enabled").toString());
        String text = m.get("text") == null ? "" : m.get("text").toString();
        List<Channel> channels = new ArrayList<>();
        if (m.get("channels") instanceof List<?> l) {
            for (Object o : l) {
                Channel c = Channel.from(String.valueOf(o));
                if (c != null) channels.add(c);
            }
        }
        String sound = m.get("sound") == null ? "" : m.get("sound").toString();
        int cooldown = m.get("cooldown-seconds") instanceof Number n ? n.intValue() : 0;
        boolean discord = Boolean.parseBoolean(String.valueOf(m.get("discord")));
        return new EventSpec(key, enabled, text, channels, sound, Math.max(0, cooldown), discord);
    }
}
