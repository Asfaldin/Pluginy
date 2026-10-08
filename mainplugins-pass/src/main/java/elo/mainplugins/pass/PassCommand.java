package elo.mainplugins.pass;

import elo.mainplugins.core.api.LangService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/** /pass (okno przepustki), /daily (nagrody dzienne) i /@pass (admin: reload, xp, vote, reset). */
final class PassCommand implements TabExecutor {

    private final JavaPlugin plugin;
    private final PassManager pass;
    private final LangService lang;
    private final Runnable reload;

    PassCommand(JavaPlugin plugin, PassManager pass, LangService lang, Runnable reload) {
        this.plugin = plugin;
        this.pass = pass;
        this.lang = lang;
        this.reload = reload;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        String name = cmd.getName().toLowerCase();
        if (name.equals("pass") || name.equals("daily")) {
            if (!(sender instanceof Player p)) {
                lang.send(sender, plugin, "admin.players-only");
                return true;
            }
            if (name.equals("daily")) pass.openDaily(p);
            else pass.openPass(p, 0);
            return true;
        }
        // /@pass
        if (args.length == 0) {
            lang.send(sender, plugin, "admin.usage");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reload.run();
                lang.send(sender, plugin, "admin.reloaded");
            }
            case "xp" -> {
                if (args.length < 3) {
                    lang.send(sender, plugin, "admin.usage");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    lang.send(sender, plugin, "admin.player-not-found", Map.of("player", args[1]));
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    lang.send(sender, plugin, "admin.usage");
                    return true;
                }
                pass.addXp(target, amount);
                lang.send(sender, plugin, "admin.xp-given", Map.of("player", target.getName(), "xp", String.valueOf(amount)));
            }
            case "vote" -> {
                if (args.length < 2) {
                    lang.send(sender, plugin, "admin.usage");
                    return true;
                }
                if (pass.vote(args[1])) lang.send(sender, plugin, "admin.vote-done", Map.of("player", args[1]));
                else lang.send(sender, plugin, "admin.player-not-found", Map.of("player", args[1]));
            }
            case "reset" -> {
                if (args.length < 2) {
                    lang.send(sender, plugin, "admin.usage");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    lang.send(sender, plugin, "admin.player-not-found", Map.of("player", args[1]));
                    return true;
                }
                PlayerStore.Data d = pass.data(target.getUniqueId());
                d.xp = 0;
                d.claimedFree.clear();
                d.claimedPremium.clear();
                pass.save();
                lang.send(sender, plugin, "admin.reset-done", Map.of("player", target.getName()));
            }
            default -> lang.send(sender, plugin, "admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!cmd.getName().equalsIgnoreCase("@pass")) return List.of();
        if (args.length == 1) return List.of("reload", "xp", "vote", "reset").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (args.length == 2) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        return List.of();
    }
}
