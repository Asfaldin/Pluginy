package elo.mainplugins.core.server;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScheduledTaskTest {

    @Test
    void dailyTimeOnSelectedDays() {
        List<String> w = new ArrayList<>();
        ScheduledTask t = ScheduledTask.parse("r", Map.of("type", "restart", "at", List.of("4:30"), "days", List.of("mon", "fri")), w);
        assertTrue(w.isEmpty(), w.toString());
        assertEquals(List.of(300, 60, 10), t.warnSeconds());
        LocalDateTime monday = LocalDateTime.of(2026, 10, 5, 4, 30);
        assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek());
        assertTrue(t.isDue(monday));
        assertFalse(t.isDue(monday.plusMinutes(1)));
        assertFalse(t.isDue(monday.plusDays(1)));
    }

    @Test
    void everyMinutesFromMidnight() {
        ScheduledTask t = ScheduledTask.parse("b", Map.of("type", "broadcast", "every", 30, "message", "hi"), new ArrayList<>());
        assertTrue(t.isDue(LocalDateTime.of(2026, 10, 6, 10, 30)));
        assertTrue(t.isDue(LocalDateTime.of(2026, 10, 6, 0, 0)));
        assertFalse(t.isDue(LocalDateTime.of(2026, 10, 6, 10, 15)));
    }

    @Test
    void badValuesWarnAndFallBack() {
        List<String> w = new ArrayList<>();
        ScheduledTask t = ScheduledTask.parse("x", Map.of("type", "explode", "at", List.of("25:99"), "enabled", false), w);
        assertEquals("command", t.type());
        assertFalse(t.enabled());
        assertTrue(w.size() >= 2);
        assertFalse(t.isDue(LocalDateTime.of(2026, 10, 6, 1, 0)));
    }

    @Test
    void commandsDropLeadingSlash() {
        ScheduledTask t = ScheduledTask.parse("c", Map.of("at", "12:00", "commands", List.of("/say hi", " ", "time set day")), new ArrayList<>());
        assertEquals(List.of("say hi", "time set day"), t.commands());
    }
}
