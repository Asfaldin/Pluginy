package elo.mainplugins.announcer.model;

/** Kolejność wybierania wiadomości w grupie przy każdym "tyknięciu" harmonogramu. */
public enum Ordering {
    /** Po kolei, w kółko (jak stary announcer). */
    SEQUENTIAL,
    /** Losowo, każda wiadomość z równą szansą. */
    RANDOM,
    /** Losowo, ale proporcjonalnie do "weight" wiadomości. */
    WEIGHTED;

    public static Ordering from(String raw, Ordering domyslne) {
        if (raw == null || raw.isBlank()) return domyslne;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return domyslne;
        }
    }
}
