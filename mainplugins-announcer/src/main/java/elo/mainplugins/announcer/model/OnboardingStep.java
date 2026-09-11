package elo.mainplugins.announcer.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jeden krok sekwencji powitalnej - wiadomość wysyłana TYLKO do gracza, który
 * łączy się pierwszy raz w życiu, {@code delaySeconds} po jego wejściu (o ile
 * nadal jest online). Patrz OnboardingManager.
 */
public record OnboardingStep(int delaySeconds, String textLegacy, List<Channel> channels) {

    public List<Channel> channelsOrChat() {
        return channels.isEmpty() ? List.of(Channel.CHAT) : channels;
    }

    public static OnboardingStep fromMap(Map<?, ?> m) {
        if (m == null || m.get("text") == null) return null;
        int delay = m.get("delay-seconds") instanceof Number n ? n.intValue() : 0;
        List<Channel> channels = new ArrayList<>();
        if (m.get("channels") instanceof List<?> l) {
            for (Object o : l) {
                Channel c = Channel.from(String.valueOf(o));
                if (c != null) channels.add(c);
            }
        }
        return new OnboardingStep(Math.max(0, delay), m.get("text").toString(), channels);
    }
}
