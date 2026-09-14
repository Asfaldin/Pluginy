package elo.mainplugins.quests;

import elo.mainplugins.core.api.LangService;
import elo.mainplugins.quests.model.CategoryDef;
import elo.mainplugins.quests.model.QuestDef;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /@quests reload | list | reset <gracz> [kategoria] | complete <gracz> <kategoria> <nr> */
final class QuestCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final QuestManager quests;
    private final LangService lang;

    QuestCommand(Plugin plugin, QuestManager quests, LangService lang) {
        this.plugin = plugin;
        this.quests = quests;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                quests.reload();
                int count = quests.config().categories().values().stream().mapToInt(c -> c.quests().size()).sum();
                lang.send(sender, plugin, "admin.reloaded", Map.of(
                        "categories", String.valueOf(quests.config().categories().size()), "quests", String.valueOf(count)));
            }
            case "list" -> {
                if (quests.config().categories().isEmpty()) lang.send(sender, plugin, "admin.list-empty");
                for (CategoryDef c : quests.config().categories().values()) {
                    lang.send(sender, plugin, "admin.list", Map.of("category", c.name(), "id", c.id(), "quests", String.valueOf(c.quests().size())));
                }
            }
            case "reset" -> reset(sender, args);
            case "complete" -> complete(sender, args);
            case "undo" -> undo(sender, args);
            default -> lang.send(sender, plugin, "admin.usage");
        }
        return true;
    }

    private CategoryDef category(CommandSender sender, String id) {
        CategoryDef c = quests.config().categories().get(id);
        if (c == null) {
            lang.send(sender, plugin, "admin.unknown-category", Map.of("id", id, "list", String.join(", ", quests.config().categories().keySet())));
        }
        return c;
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            lang.send(sender, plugin, "admin.usage");
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            lang.send(sender, plugin, "admin.player-not-found", Map.of("player", args[1]));
            return;
        }
        String name = target.getName() != null ? target.getName() : args[1];
        if (args.length >= 3) {
            if (category(sender, args[2]) == null) return;
            quests.reset(target.getUniqueId(), args[2]);
            lang.send(sender, plugin, "admin.reset-category", Map.of("category", args[2], "player", name));
        } else {
            quests.reset(target.getUniqueId(), null);
            lang.send(sender, plugin, "admin.reset-all", Map.of("player", name));
        }
    }

    private void complete(CommandSender sender, String[] args) {
        if (args.length < 4) {
            lang.send(sender, plugin, "admin.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            lang.send(sender, plugin, "admin.player-not-found", Map.of("player", args[1]));
            return;
        }
        CategoryDef c = category(sender, args[2]);
        if (c == null) return;
        int id;
        try {
            id = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            lang.send(sender, plugin, "admin.usage");
            return;
        }
        QuestDef q = c.quests().stream().filter(x -> x.id() == id).findFirst().orElse(null);
        Map<String, String> ph = Map.of("quest", args[3], "category", c.id(), "player", target.getName());
        if (q == null) {
            lang.send(sender, plugin, "admin.unknown-quest", ph);
            return;
        }
        lang.send(sender, plugin, quests.forceComplete(target, c, q) ? "admin.completed" : "admin.already-done", ph);
    }

    /** Cofa jedno zadanie graczowi (też offline) - np. gdy coś się zbugowało. Nagród nie zabiera. */
    private void undo(CommandSender sender, String[] args) {
        if (args.length < 4) {
            lang.send(sender, plugin, "admin.usage");
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null) {
            lang.send(sender, plugin, "admin.player-not-found", Map.of("player", args[1]));
            return;
        }
        CategoryDef c = category(sender, args[2]);
        if (c == null) return;
        int id;
        try {
            id = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            lang.send(sender, plugin, "admin.usage");
            return;
        }
        String name = target.getName() != null ? target.getName() : args[1];
        Map<String, String> ph = Map.of("quest", args[3], "category", c.id(), "player", name);
        lang.send(sender, plugin, quests.undo(target.getUniqueId(), c.id(), id) ? "admin.undone" : "admin.not-done", ph);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return filter(List.of("reload", "list", "reset", "complete", "undo"), args[0]);
        boolean withPlayer = args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("complete") || args[0].equalsIgnoreCase("undo");
        if (args.length == 2 && withPlayer) return null;
        if (args.length == 3 && withPlayer) return filter(new ArrayList<>(quests.config().categories().keySet()), args[2]);
        if (args.length == 4 && (args[0].equalsIgnoreCase("complete") || args[0].equalsIgnoreCase("undo"))) {
            CategoryDef c = quests.config().categories().get(args[2]);
            if (c == null) return List.of();
            return filter(c.quests().stream().map(q -> String.valueOf(q.id())).toList(), args[3]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
