package elo.mainplugins.core.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Po starcie serwera (wszystkie pluginy włączone) nakłada commands.yml na komendy
 * pluginów Mainplugins*: zmiana nazwy, aliasy, wyłączenie, plus domyślne uprawnienie
 * dla komend, które go nie mają. Executor komendy się nie zmienia - tylko pod jakimi
 * nazwami jest zarejestrowana w CommandMap.
 */
public final class CommandRemapper implements Listener {

    private final Plugin core;

    public CommandRemapper(Plugin core) {
        this.core = core;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        File file = new File(core.getDataFolder(), "commands.yml");
        if (!file.exists()) core.saveResource("commands.yml", false);
        Map<String, CommandSetting> settings =
                CommandSettingsParser.parse(YamlConfiguration.loadConfiguration(file), core.getLogger()::warning);

        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        Set<PluginCommand> ours = new LinkedHashSet<>();
        for (Command c : known.values()) {
            if (c instanceof PluginCommand pc && pc.getPlugin().getName().startsWith("Mainplugins")) ours.add(pc);
        }

        for (PluginCommand cmd : ours) {
            ensurePermission(cmd);
            CommandSetting setting = settings.get(cmd.getName().toLowerCase(Locale.ROOT));
            if (setting != null) apply(map, known, cmd, setting);
        }
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
    }

    private void ensurePermission(PluginCommand cmd) {
        if (cmd.getPermission() != null) return;
        String node = PermissionNodes.nodeFor(cmd.getPlugin().getName(), cmd.getName());
        if (Bukkit.getPluginManager().getPermission(node) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(node,
                    PermissionNodes.isAdmin(cmd.getName()) ? PermissionDefault.OP : PermissionDefault.TRUE));
        }
        cmd.setPermission(node);
    }

    private void apply(CommandMap map, Map<String, Command> known, PluginCommand cmd, CommandSetting setting) {
        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, Command> e : known.entrySet()) {
            if (e.getValue() == cmd) labels.add(e.getKey());
        }
        labels.forEach(known::remove);
        cmd.unregister(map);

        if (!setting.enabled()) {
            core.getLogger().info("Command /" + setting.command() + " is turned off in commands.yml.");
            return;
        }
        cmd.setAliases(setting.aliases());
        cmd.setLabel(setting.name());
        String prefix = cmd.getPlugin().getName().toLowerCase(Locale.ROOT);
        if (!map.register(setting.name(), prefix, cmd)) {
            core.getLogger().warning("/" + setting.name() + " is already used by another plugin - ours is available as /"
                    + prefix + ":" + setting.name() + ". Rename it in commands.yml.");
        }
    }
}
