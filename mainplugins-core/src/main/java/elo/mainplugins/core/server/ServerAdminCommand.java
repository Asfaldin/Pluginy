package elo.mainplugins.core.server;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * /@schedule [list|run &lt;id&gt;|reload], /@backup, /@reloadcommands - wywoływane też przez
 * aplikację (RCON) po wgraniu plików. Odpowiedzi po angielsku, krótkie - appka je pokazuje.
 */
public final class ServerAdminCommand implements TabExecutor {
    private final TaskScheduler scheduler;
    private final BackupManager backups;
    private final CustomCommandsManager customCommands;

    public ServerAdminCommand(TaskScheduler scheduler, BackupManager backups, CustomCommandsManager customCommands) {
        this.scheduler = scheduler;
        this.backups = backups;
        this.customCommands = customCommands;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        switch (command.getName().toLowerCase()) {
            case "@backup" -> {
                boolean started = backups.start("manual", sender::sendMessage);
                sender.sendMessage(started ? "§aBackup started - it runs in the background." : "§eA backup is already running.");
            }
            case "@reloadcommands" -> sender.sendMessage("§aCustom commands loaded: " + customCommands.reload() + " " + customCommands.names());
            default -> schedule(sender, args);
        }
        return true;
    }

    private void schedule(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                List<String> warnings = scheduler.reload();
                sender.sendMessage("§aSchedule reloaded: " + scheduler.tasks().size() + " tasks" + (warnings.isEmpty() ? "." : ", warnings: " + String.join(" | ", warnings)));
            }
            case "run" -> {
                ScheduledTask t = args.length > 1 ? scheduler.tasks().get(args[1]) : null;
                if (t == null) {
                    sender.sendMessage("§cUnknown task. Use /@schedule list.");
                    return;
                }
                sender.sendMessage("§aRan " + t.id() + ": " + scheduler.run(t, "manual"));
            }
            default -> {
                if (scheduler.tasks().isEmpty()) sender.sendMessage("§7No tasks in scheduler.yml.");
                scheduler.tasks().values().forEach(t -> sender.sendMessage((t.enabled() ? "§a● " : "§7○ ") + t.id() + " §7(" + t.type() + ")"));
            }
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("@schedule")) return List.of();
        if (args.length == 1) return List.of("list", "run", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("run")) return new ArrayList<>(scheduler.tasks().keySet());
        return List.of();
    }
}
