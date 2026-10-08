package elo.mainplugins.claims;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * claims.yml: ile chunków może zabezpieczyć gracz (bez planu / z planem), koszt chunka,
 * światy bez claimów, domyślne flagi nowego terenu i czy pokazywać komunikat przy wejściu.
 */
public record ClaimsConfig(
        int maxChunksFree,
        int maxChunksPlan,
        double costPerChunk,
        Set<String> disabledWorlds,
        boolean defaultPvp,
        boolean defaultExplosions,
        boolean defaultMobGriefing,
        boolean enterMessage,
        int borderSeconds) {

    public static ClaimsConfig parse(ConfigurationSection root, Consumer<String> warn) {
        ConfigurationSection s = root.getConfigurationSection("claims");
        if (s == null) s = root;
        int free = clamp(s, "max-chunks", 4, 1, 10_000, warn);
        ConfigurationSection f = s.getConfigurationSection("default-flags");
        List<String> worlds = s.getStringList("disabled-worlds");
        return new ClaimsConfig(
                free,
                clamp(s, "max-chunks-with-plan", 25, free, 100_000, warn),
                Math.max(0, s.getDouble("cost-per-chunk", 0)),
                worlds.stream().map(String::toLowerCase).collect(Collectors.toUnmodifiableSet()),
                f != null && f.getBoolean("pvp", false),
                f != null && f.getBoolean("explosions", false),
                f != null && f.getBoolean("mob-griefing", false),
                s.getBoolean("enter-message", true),
                clamp(s, "show-border-seconds", 10, 1, 120, warn));
    }

    private static int clamp(ConfigurationSection s, String key, int def, int min, int max, Consumer<String> warn) {
        int v = s.getInt(key, def);
        if (v < min || v > max) {
            warn.accept("claims.yml " + key + " must be between " + min + " and " + max + " - using " + def + ".");
            return Math.max(min, Math.min(max, def));
        }
        return v;
    }
}
