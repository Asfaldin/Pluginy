package elo.mainplugins.core.command;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Czyta commands.yml. Złe nazwy i nazwy zajęte przez wcześniejszy wpis są pomijane z ostrzeżeniem. */
public final class CommandSettingsParser {

    private static final Pattern LABEL = Pattern.compile("@?[a-z0-9_-]+");

    private CommandSettingsParser() {}

    public static Map<String, CommandSetting> parse(ConfigurationSection root, Consumer<String> warn) {
        Map<String, CommandSetting> out = new LinkedHashMap<>();
        ConfigurationSection commands = root.getConfigurationSection("commands");
        if (commands == null) return out;

        Set<String> taken = new HashSet<>();
        for (String key : commands.getKeys(false)) {
            String command = key.toLowerCase(Locale.ROOT);
            ConfigurationSection s = commands.getConfigurationSection(key);
            if (s == null) {
                warn.accept("commands.yml: '" + key + "' is not a section - skipping.");
                continue;
            }
            String name = label(s.getString("name", command), key, warn);
            if (name == null) name = command;
            if (!taken.add(name)) {
                warn.accept("commands.yml: '/" + name + "' is already used by another entry - '" + key + "' keeps its original name.");
                name = command;
                taken.add(name);
            }
            List<String> aliases = new ArrayList<>();
            for (String raw : s.getStringList("aliases")) {
                String alias = label(raw, key, warn);
                if (alias == null) continue;
                if (!taken.add(alias)) {
                    warn.accept("commands.yml: alias '/" + alias + "' of '" + key + "' is already used - skipping it.");
                    continue;
                }
                aliases.add(alias);
            }
            out.put(command, new CommandSetting(command, s.getBoolean("enabled", true), name, aliases));
        }
        return out;
    }

    private static String label(String raw, String key, Consumer<String> warn) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!LABEL.matcher(value).matches()) {
            warn.accept("commands.yml: '" + raw + "' in '" + key + "' is not a valid command name (letters, digits, _ and - only) - skipping it.");
            return null;
        }
        return value;
    }
}
