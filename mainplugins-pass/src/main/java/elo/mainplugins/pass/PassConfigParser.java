package elo.mainplugins.pass;

import elo.mainplugins.core.api.Reward;
import elo.mainplugins.pass.model.PassConfig;
import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Czyta pass.yml. Złe wpisy są pomijane z ostrzeżeniem - nigdy wyjątek. */
public final class PassConfigParser {

    public static final String DEFAULT_PREMIUM_PERMISSION = "mainplugins.pass.premium";

    private PassConfigParser() {}

    /** rewards = RewardService::parse (w testach podmieniany), warn = ostrzeżenie do logu. */
    public static PassConfig parse(ConfigurationSection root, BiFunction<List<?>, String, List<Reward>> rewards, Consumer<String> warn) {
        ConfigurationSection s = root.getConfigurationSection("season");
        String id = s != null ? s.getString("id", "season-1") : "season-1";
        String name = s != null ? s.getString("name", "&6&lSeason 1") : "&6&lSeason 1";
        int xpPerLevel = s != null ? s.getInt("xp-per-level", 100) : 100;
        if (xpPerLevel <= 0) {
            warn.accept("pass.yml season.xp-per-level must be above 0 - using 100.");
            xpPerLevel = 100;
        }
        LocalDate ends = null;
        String endsRaw = s != null ? s.getString("ends", "") : "";
        if (endsRaw != null && !endsRaw.isBlank()) {
            try {
                ends = LocalDate.parse(endsRaw.trim());
            } catch (DateTimeParseException e) {
                warn.accept("pass.yml season.ends must be a date like 2026-12-31 - ignoring '" + endsRaw + "'.");
            }
        }

        ConfigurationSection x = root.getConfigurationSection("xp");
        PassConfig.XpSources xp = new PassConfig.XpSources(
                nonNegative(x, "daily-login", 50, warn),
                Math.max(1, x != null ? x.getInt("playtime-minutes", 10) : 10),
                nonNegative(x, "playtime-xp", 10, warn),
                nonNegative(x, "mob-kill", 1, warn),
                nonNegative(x, "vote", 40, warn));

        List<PassConfig.LevelDef> levels = new ArrayList<>();
        List<?> rawLevels = root.getList("levels");
        if (rawLevels != null) {
            int auto = 1;
            for (Object o : rawLevels) {
                if (!(o instanceof Map<?, ?> m)) {
                    warn.accept("pass.yml levels: every entry must be a map with free/premium - skipping one.");
                    continue;
                }
                int level = m.get("level") instanceof Number n ? n.intValue() : auto;
                auto = level + 1;
                String where = "pass.yml levels[" + level + "]";
                List<Reward> free = m.get("free") instanceof List<?> l ? rewards.apply(l, where + ".free") : List.of();
                List<Reward> premium = m.get("premium") instanceof List<?> l ? rewards.apply(l, where + ".premium") : List.of();
                if (level < 1 || levels.stream().anyMatch(d -> d.level() == level)) {
                    warn.accept(where + ": level must be 1 or more and unique - skipping.");
                    continue;
                }
                levels.add(new PassConfig.LevelDef(level, free, premium));
            }
        }
        levels.sort(Comparator.comparingInt(PassConfig.LevelDef::level));

        ConfigurationSection d = root.getConfigurationSection("daily");
        List<List<Reward>> days = new ArrayList<>();
        if (d != null && d.getList("days") != null) {
            int i = 1;
            for (Object o : d.getList("days")) {
                String where = "pass.yml daily.days[" + i++ + "]";
                if (o instanceof Map<?, ?> m && m.get("rewards") instanceof List<?> l) days.add(rewards.apply(l, where));
                else if (o instanceof List<?> l) days.add(rewards.apply(l, where));
                else warn.accept(where + ": expected { rewards: [...] } - skipping.");
            }
        }
        PassConfig.Daily daily = new PassConfig.Daily(d == null || d.getBoolean("enabled", true), d == null || d.getBoolean("reset-if-missed", true), days);

        ConfigurationSection v = root.getConfigurationSection("vote");
        List<Reward> voteRewards = v != null && v.getList("rewards") != null ? rewards.apply(v.getList("rewards"), "pass.yml vote.rewards") : List.of();
        PassConfig.Vote vote = new PassConfig.Vote(v == null || v.getBoolean("enabled", true), voteRewards);

        String perm = root.getString("premium-permission", DEFAULT_PREMIUM_PERMISSION);
        if (perm == null || perm.isBlank()) perm = DEFAULT_PREMIUM_PERMISSION;
        return new PassConfig(new PassConfig.Season(id, name, ends, xpPerLevel), xp, perm, levels, daily, vote);
    }

    private static int nonNegative(ConfigurationSection sec, String key, int def, Consumer<String> warn) {
        int v = sec != null ? sec.getInt(key, def) : def;
        if (v < 0) {
            warn.accept("pass.yml xp." + key + " can't be negative - using " + def + ".");
            return def;
        }
        return v;
    }
}
