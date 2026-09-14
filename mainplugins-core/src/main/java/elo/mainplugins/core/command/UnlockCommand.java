package elo.mainplugins.core.command;

import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.UnlockService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** /@unlock give|take|list <gracz> [nazwa] - ręczne odblokowania (testy, pomoc graczom). */
public final class UnlockCommand implements CommandExecutor, TabCompleter {

    private final Plugin core;
    private final UnlockService unlocks;
    private final LangService lang;

    public UnlockCommand(Plugin core, UnlockService unlocks, LangService lang) {
        this.core = core;
        this.unlocks = unlocks;
        this.lang = lang;
    }

    private OfflinePlayer find(String name) {
        OfflinePlayer online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length < 2) {
            lang.send(sender, core, "admin.unlock.usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        OfflinePlayer target = find(args[1]);
        if (target == null) {
            lang.send(sender, core, "admin.player-not-found", Map.of("player", args[1]));
            return true;
        }
        String playerName = target.getName() != null ? target.getName() : args[1];
        switch (sub) {
            case "list" -> {
                Set<String> list = unlocks.list(target.getUniqueId());
                lang.send(sender, core, "admin.unlock.list",
                        Map.of("player", playerName, "list", list.isEmpty() ? "-" : String.join(", ", list)));
            }
            case "give", "take" -> {
                if (args.length < 3) {
                    lang.send(sender, core, "admin.unlock.usage");
                    return true;
                }
                boolean give = sub.equals("give");
                boolean changed = give ? unlocks.give(target.getUniqueId(), args[2]) : unlocks.take(target.getUniqueId(), args[2]);
                String key = give ? (changed ? "admin.unlock.given" : "admin.unlock.already")
                        : (changed ? "admin.unlock.taken" : "admin.unlock.missing");
                lang.send(sender, core, key, Map.of("player", playerName, "name", args[2].trim().toLowerCase(Locale.ROOT)));
            }
            default -> lang.send(sender, core, "admin.unlock.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return filter(List.of("give", "take", "list"), args[0]);
        if (args.length == 2) return null; // nicki graczy online
        if (args.length == 3 && args[0].equalsIgnoreCase("take")) {
            OfflinePlayer target = find(args[1]);
            return target == null ? List.of() : filter(new ArrayList<>(unlocks.list(target.getUniqueId())), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
