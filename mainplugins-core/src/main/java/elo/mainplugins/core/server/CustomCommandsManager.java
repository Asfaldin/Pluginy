package elo.mainplugins.core.server;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Własne komendy z aplikacji (Server -> Commands -> My commands), plik
 * plugins/MainpluginsCore/custom-commands.yml. Każda staje się prawdziwą komendą w grze
 * (/starterkit), wykonuje akcje z konsoli z {player} podmienionym na gracza, z permisją
 * i cooldownem. /@reloadcommands przeładowuje bez restartu.
 */
public final class CustomCommandsManager {
    private final JavaPlugin plugin;
    private final File file;
    private final List<Command> registered = new ArrayList<>();
    private final Map<String, Long> cooldowns = new HashMap<>();

    public CustomCommandsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "custom-commands.yml");
    }

    /** Zwraca liczbę zarejestrowanych komend. */
    public int reload() {
        CommandMap map = Bukkit.getCommandMap();
        for (Command c : registered) {
            c.unregister(map);
            map.getKnownCommands().values().removeIf(k -> k == c);
        }
        registered.clear();
        if (!file.isFile()) return 0;
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(file).getConfigurationSection("commands");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                ConfigurationSection c = sec.getConfigurationSection(name);
                if (c == null || !c.getBoolean("enabled", true)) continue;
                String label = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
                if (label.isEmpty()) continue;
                if (map.getCommand(label) != null) {
                    plugin.getLogger().warning("custom-commands.yml: /" + label + " already exists - skipped.");
                    continue;
                }
                Custom cmd = new Custom(label, c.getString("description", ""), c.getString("permission", ""), c.getLong("cooldown", 0), c.getStringList("actions"));
                map.register("mainplugins", cmd);
                registered.add(cmd);
            }
        }
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        return registered.size();
    }

    private final class Custom extends Command {
        private final String perm;
        private final long cooldown;
        private final List<String> actions;

        Custom(String name, String description, String perm, long cooldown, List<String> actions) {
            super(name, description, "/" + name, List.of());
            this.perm = perm == null ? "" : perm.trim();
            this.cooldown = Math.max(0, cooldown);
            this.actions = actions;
            if (!this.perm.isEmpty()) setPermission(this.perm);
        }

        @Override
        public boolean execute(@NotNull CommandSender sender, @NotNull String label, @NotNull String[] args) {
            if (!perm.isEmpty() && !sender.hasPermission(perm)) {
                sender.sendMessage("§cYou don't have permission to use this command.");
                return true;
            }
            String player = sender instanceof Player p ? p.getName() : (args.length > 0 ? args[0] : "");
            if (sender instanceof Player p && cooldown > 0) {
                String key = getName() + ":" + p.getUniqueId();
                long now = System.currentTimeMillis();
                Long until = cooldowns.get(key);
                if (until != null && until > now) {
                    sender.sendMessage("§cWait " + ((until - now) / 1000 + 1) + " s before using /" + getName() + " again.");
                    return true;
                }
                cooldowns.put(key, now + cooldown * 1000);
            }
            for (String a : actions) {
                String cmd = a.replace("{player}", player).replaceFirst("^/", "");
                for (int i = 0; i < args.length; i++) cmd = cmd.replace("{arg" + (i + 1) + "}", args[i]);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            return true;
        }
    }

    /** Dla testów/diagnostyki. */
    public List<String> names() {
        return registered.stream().map(Command::getName).toList();
    }
}
