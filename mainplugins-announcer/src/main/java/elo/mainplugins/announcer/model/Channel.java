package elo.mainplugins.announcer.model;

/**
 * Gdzie pokazać ogłoszenie. Jedna wiadomość może mieć kilka kanałów naraz
 * (np. [CHAT, ACTIONBAR]). Parsowane tolerancyjnie - nieznana nazwa w YAML-u
 * jest pomijana z ostrzeżeniem, a nie wywala configu (patrz AnnouncerConfig).
 */
public enum Channel {
    /** Zwykła linia na czacie (domyślny). */
    CHAT,
    /** Pasek nad hotbarem - krótki, znika sam. */
    ACTIONBAR,
    /** Duży tytuł + podtytuł na środku ekranu. */
    TITLE,
    /** Pasek bossa u góry - widoczny X sekund, potem znika. */
    BOSSBAR;

    public static Channel from(String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
