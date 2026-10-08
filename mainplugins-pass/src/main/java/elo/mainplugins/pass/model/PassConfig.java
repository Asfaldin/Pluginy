package elo.mainplugins.pass.model;

import elo.mainplugins.core.api.Reward;

import java.time.LocalDate;
import java.util.List;

/**
 * Całość pass.yml: sezon i jego poziomy (darmowa + premium ścieżka), źródła XP,
 * nagrody dzienne i za głosowanie.
 */
public record PassConfig(Season season, XpSources xp, String premiumPermission, List<LevelDef> levels, Daily daily, Vote vote) {

    public PassConfig {
        levels = List.copyOf(levels);
    }

    /** id zmienione = nowy sezon: postęp graczy wraca do zera. ends = null - bez daty końca. */
    public record Season(String id, String name, LocalDate ends, int xpPerLevel) {}

    /** Ile XP przepustki za co. playtimeMinutes = co ile minut gry dostaje się playtimeXp. */
    public record XpSources(int dailyLogin, int playtimeMinutes, int playtimeXp, int mobKill, int vote) {}

    /** Jeden poziom: nagrody darmowe i premium (każda lista może być pusta). */
    public record LevelDef(int level, List<Reward> free, List<Reward> premium) {
        public LevelDef {
            free = List.copyOf(free);
            premium = List.copyOf(premium);
        }
    }

    /** Nagrody za kolejne dni logowania; po ostatnim dniu cykl zaczyna się od nowa. */
    public record Daily(boolean enabled, boolean resetIfMissed, List<List<Reward>> days) {
        public Daily {
            days = days.stream().map(List::copyOf).toList();
        }
    }

    public record Vote(boolean enabled, List<Reward> rewards) {
        public Vote {
            rewards = List.copyOf(rewards);
        }
    }

    public int maxLevel() {
        return levels.isEmpty() ? 0 : levels.get(levels.size() - 1).level();
    }

    public LevelDef level(int n) {
        for (LevelDef l : levels) if (l.level() == n) return l;
        return null;
    }
}
