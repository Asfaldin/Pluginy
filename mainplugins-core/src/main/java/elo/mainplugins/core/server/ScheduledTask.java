package elo.mainplugins.core.server;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Zadanie z scheduler.yml (aplikacja: zakładka Server -> Schedule).
 * Typy: command (komendy z konsoli), broadcast (wiadomość do wszystkich),
 * restart (ostrzeżenia, zapis, wyłączenie), backup (kopia zapasowa).
 * Kiedy: o godzinach `at` (opcjonalnie tylko w dni `days`) albo co `every` minut.
 */
public record ScheduledTask(
        String id,
        String name,
        boolean enabled,
        String type,
        List<LocalTime> at,
        Set<DayOfWeek> days,
        int everyMinutes,
        List<String> commands,
        String message,
        List<Integer> warnSeconds) {

    public static final List<String> TYPES = List.of("command", "broadcast", "restart", "backup");

    /** Czy zadanie wypada w tej minucie. `every` liczone od północy (co 30 min = :00 i :30). */
    public boolean isDue(LocalDateTime now) {
        if (!enabled) return false;
        if (!days.isEmpty() && !days.contains(now.getDayOfWeek())) return false;
        if (everyMinutes > 0) {
            int minuteOfDay = now.getHour() * 60 + now.getMinute();
            return minuteOfDay % everyMinutes == 0;
        }
        for (LocalTime t : at) {
            if (t.getHour() == now.getHour() && t.getMinute() == now.getMinute()) return true;
        }
        return false;
    }

    /** Odczyt jednego zadania z mapy YAML; błędne pola dostają bezpieczne wartości, problemy trafiają do `warnings`. */
    public static ScheduledTask parse(String id, Map<String, Object> raw, List<String> warnings) {
        String type = String.valueOf(raw.getOrDefault("type", "command")).toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            warnings.add("Task " + id + ": unknown type '" + type + "' - using 'command'.");
            type = "command";
        }
        List<LocalTime> at = new ArrayList<>();
        Object atRaw = raw.get("at");
        List<?> atList = atRaw instanceof List<?> l ? l : atRaw == null ? List.of() : List.of(atRaw);
        for (Object o : atList) {
            try {
                at.add(LocalTime.parse(pad(String.valueOf(o).trim())));
            } catch (Exception e) {
                warnings.add("Task " + id + ": bad time '" + o + "' (use HH:mm).");
            }
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (raw.get("days") instanceof List<?> l) {
            for (Object o : l) {
                DayOfWeek d = day(String.valueOf(o));
                if (d != null) days.add(d);
                else warnings.add("Task " + id + ": bad day '" + o + "'.");
            }
        }
        int every = raw.get("every") instanceof Number n ? Math.max(0, n.intValue()) : 0;
        if (every == 0 && at.isEmpty()) warnings.add("Task " + id + ": no 'at' times and no 'every' - it never runs.");
        List<String> commands = new ArrayList<>();
        if (raw.get("commands") instanceof List<?> l) for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) commands.add(String.valueOf(o).trim().replaceFirst("^/", ""));
        List<Integer> warn = new ArrayList<>();
        if (raw.get("warn") instanceof List<?> l) for (Object o : l) if (o instanceof Number n && n.intValue() > 0) warn.add(n.intValue());
        if (raw.get("warn") == null && type.equals("restart")) warn.addAll(List.of(300, 60, 10));
        warn.sort((a, b) -> b - a);
        return new ScheduledTask(
                id,
                String.valueOf(raw.getOrDefault("name", id)),
                !Boolean.FALSE.equals(raw.get("enabled")),
                type,
                List.copyOf(at),
                days,
                every,
                List.copyOf(commands),
                raw.get("message") == null ? "" : String.valueOf(raw.get("message")),
                List.copyOf(warn));
    }

    private static String pad(String t) {
        return t.matches("\\d:\\d\\d") ? "0" + t : t;
    }

    private static DayOfWeek day(String s) {
        String k = s.trim().toLowerCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().toLowerCase(Locale.ROOT).startsWith(k) && k.length() >= 2) return d;
        }
        return null;
    }
}
