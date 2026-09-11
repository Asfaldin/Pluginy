package elo.mainplugins.announcer.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Okno czasowe grupy/wiadomości: w które dni tygodnia i w jakim przedziale godzin
 * ogłoszenie w ogóle się pokazuje. Puste = zawsze. Przedział godzin może
 * przekraczać północ ("22:00-02:00"). Czas serwera (strefa lokalna maszyny).
 */
public final class ScheduleWindow {

    /** Zawsze aktywne - brak ograniczeń dni i godzin. */
    public static final ScheduleWindow ALWAYS = new ScheduleWindow(EnumSet.noneOf(DayOfWeek.class), null, null);

    private final Set<DayOfWeek> days;   // pusty = każdy dzień
    private final LocalTime from;        // null = od początku doby
    private final LocalTime to;          // null = do końca doby

    private ScheduleWindow(Set<DayOfWeek> days, LocalTime from, LocalTime to) {
        this.days = days;
        this.from = from;
        this.to = to;
    }

    public boolean isActiveNow() {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (!days.isEmpty() && !days.contains(now.getDayOfWeek())) return false;
        return inTimeRange(now.toLocalTime(), from, to);
    }

    /** Czy {@code t} mieści się w [from, to) - z obsługą przedziału przez północ. from/to null = brzeg doby. */
    public static boolean inTimeRange(LocalTime t, LocalTime from, LocalTime to) {
        if (from == null && to == null) return true;
        LocalTime f = from == null ? LocalTime.MIN : from;
        LocalTime u = to == null ? LocalTime.MAX : to;
        if (!f.isAfter(u)) {
            return !t.isBefore(f) && t.isBefore(u);
        }
        // przez północ, np. 22:00-02:00
        return !t.isBefore(f) || t.isBefore(u);
    }

    /** "HH:mm-HH:mm" -> [from, to]; pusty/nieparsowalny -> [null, null]. */
    public static LocalTime[] parseRange(String raw) {
        LocalTime[] out = new LocalTime[2];
        if (raw == null || raw.isBlank()) return out;
        String[] parts = raw.split("-");
        if (parts.length != 2) return out;
        try {
            out[0] = LocalTime.parse(parts[0].trim());
            out[1] = LocalTime.parse(parts[1].trim());
        } catch (Exception e) {
            out[0] = null;
            out[1] = null;
        }
        return out;
    }

    public static ScheduleWindow of(List<String> dayNames, String timeRange) {
        Set<DayOfWeek> d = EnumSet.noneOf(DayOfWeek.class);
        if (dayNames != null) {
            for (String s : dayNames) {
                try {
                    d.add(DayOfWeek.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                } catch (Exception ignored) { }
            }
        }
        LocalTime[] r = parseRange(timeRange);
        if (d.isEmpty() && r[0] == null && r[1] == null) return ALWAYS;
        return new ScheduleWindow(d, r[0], r[1]);
    }
}
