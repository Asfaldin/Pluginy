package elo.mainplugins.crates;

import elo.mainplugins.core.api.LangService;
import elo.mainplugins.crates.model.PlacedCrate;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** /@crate give <gracz> <skrzynka> [ile] | key <gracz> <klucz> [ile] | place <skrzynka> | remove | list | reload */
final class CrateCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final CrateManager crates;
    private final LangService lang;

    CrateCommand(Plugin plugin, CrateManager crates, LangService lang) {
        this.plugin = plugin;
        this.crates = crates;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                crates.reload();
                lang.send(sender, plugin, "admin.reloaded", Map.of(
                        "crates", String.valueOf(crates.crateIds().size()), "keys", String.valueOf(crates.keyIds().size())));
            }
            case "list" -> {
                lang.send(sender, plugin, "admin.list", Map.of(
                        "crates", String.join(", ", crates.crateIds()), "keys", String.join(", ", crates.keyIds())));
                String gdzie = crates.placed().all().stream()
                        .map(p -> p.crate() + " (" + p.world() + " " + p.x() + " " + p.y() + " " + p.z() + ")")
                        .collect(Collectors.joining(", "));
                lang.send(sender, plugin, "admin.list-placed", Map.of("list", gdzie.isEmpty() ? "-" : gdzie));
            }
            case "give", "key" -> give(sender, args, sub.equals("give"));
            case "place" -> place(sender, args);
            case "remove" -> remove(sender);
            default -> lang.send(sender, plugin, "admin.usage");
        }
        return true;
    }

    /** Blok, na który patrzy admin (do 6 kratek); null + komunikat, gdy nie da się. */
    private Block targetBlock(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, plugin, "admin.players-only");
            return null;
        }
        Block b = player.getTargetBlockExact(6);
        if (b == null || b.getType().isAir()) {
            lang.send(sender, plugin, "admin.no-block");
            return null;
        }
        return b;
    }

    private void place(CommandSender sender, String[] args) {
        if (args.length < 2) {
            lang.send(sender, plugin, "admin.usage");
            return;
        }
        String id = args[1];
        if (!crates.crateIds().contains(id)) {
            lang.send(sender, plugin, "admin.unknown-crate", Map.of("id", id, "list", String.join(", ", crates.crateIds())));
            return;
        }
        Block b = targetBlock(sender);
        if (b == null) return;
        if (!crates.placed().place(b, id)) {
            lang.send(sender, plugin, "admin.already-placed");
            return;
        }
        lang.send(sender, plugin, "admin.placed", Map.of("crate", id));
    }

    private void remove(CommandSender sender) {
        Block b = targetBlock(sender);
        if (b == null) return;
        PlacedCrate p = crates.placed().remove(b);
        if (p == null) lang.send(sender, plugin, "admin.not-placed");
        else lang.send(sender, plugin, "admin.removed", Map.of("crate", p.crate()));
    }

    private void give(CommandSender sender, String[] args, boolean crate) {
        if (args.length < 3) {
            lang.send(sender, plugin, "admin.usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            lang.send(sender, plugin, "admin.player-not-found", Map.of("player", args[1]));
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException e) {
                lang.send(sender, plugin, "admin.usage");
                return;
            }
        }
        String id = args[2];
        ItemStack item = crate ? crates.createCrate(id, amount) : crates.createKey(id, amount);
        if (item == null) {
            lang.send(sender, plugin, crate ? "admin.unknown-crate" : "admin.unknown-key", Map.of(
                    "id", id, "list", String.join(", ", crate ? crates.crateIds() : crates.keyIds())));
            return;
        }
        target.getInventory().addItem(item).values().forEach(l -> target.getWorld().dropItemNaturally(target.getLocation(), l));
        lang.send(sender, plugin, crate ? "admin.given-crate" : "admin.given-key", Map.of(
                "amount", String.valueOf(amount), "id", id, "player", target.getName()));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return filter(List.of("give", "key", "place", "remove", "list", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) return filter(new ArrayList<>(crates.crateIds()), args[1]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("key"))) return null;
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return filter(new ArrayList<>(crates.crateIds()), args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("key")) return filter(new ArrayList<>(crates.keyIds()), args[2]);
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }
}
