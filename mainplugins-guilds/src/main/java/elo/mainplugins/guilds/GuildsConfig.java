package elo.mainplugins.guilds;

import org.bukkit.configuration.ConfigurationSection;

import java.util.function.Consumer;

/**
 * guilds.yml: koszt założenia, limity członków (bez planu / z planem), długość tagu i nazwy,
 * obrażenia między członkami, odliczanie przed teleportem do domu, czas na przyjęcie zaproszenia.
 */
public record GuildsConfig(
        double createCost,
        int maxMembersFree,
        int maxMembersPlan,
        int tagMin,
        int tagMax,
        int nameMax,
        boolean friendlyFire,
        int homeDelaySeconds,
        int inviteSeconds,
        int maxAllies) {

    /** Czyta guilds.yml - złe wartości dostają domyślne z ostrzeżeniem, nigdy wyjątek. */
    public static GuildsConfig parse(ConfigurationSection root, Consumer<String> warn) {
        ConfigurationSection s = root.getConfigurationSection("guilds");
        if (s == null) s = root;
        int tagMin = clamp(s, "tag-min-length", 2, 1, 8, warn);
        int tagMax = clamp(s, "tag-max-length", 5, tagMin, 12, warn);
        int free = clamp(s, "max-members", 8, 2, 500, warn);
        return new GuildsConfig(
                Math.max(0, s.getDouble("create-cost", 1000)),
                free,
                clamp(s, "max-members-with-plan", 30, free, 1000, warn),
                tagMin,
                tagMax,
                clamp(s, "name-max-length", 24, 3, 48, warn),
                s.getBoolean("friendly-fire", false),
                clamp(s, "home-delay-seconds", 3, 0, 60, warn),
                clamp(s, "invite-seconds", 120, 10, 3600, warn),
                clamp(s, "max-allies", 3, 0, 50, warn));
    }

    private static int clamp(ConfigurationSection s, String key, int def, int min, int max, Consumer<String> warn) {
        int v = s.getInt(key, def);
        if (v < min || v > max) {
            warn.accept("guilds.yml " + key + " must be between " + min + " and " + max + " - using " + def + ".");
            return Math.max(min, Math.min(max, def));
        }
        return v;
    }
}
