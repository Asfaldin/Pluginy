package elo.mainplugins.guilds;

import elo.mainplugins.core.api.LangService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** /g (gracze) i /@guilds (admin: reload, disband). */
final class GuildCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("create", "invite", "accept", "kick", "leave", "disband", "info", "top",
            "chat", "c", "home", "sethome", "promote", "demote", "transfer", "bank", "ally", "unally", "help");

    private final JavaPlugin plugin;
    private final GuildManager guilds;
    private final LangService lang;
    private final Runnable reload;

    GuildCommand(JavaPlugin plugin, GuildManager guilds, LangService lang, Runnable reload) {
        this.plugin = plugin;
        this.guilds = guilds;
        this.lang = lang;
        this.reload = reload;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] a) {
        if (cmd.getName().equalsIgnoreCase("@guilds")) return admin(sender, a);
        if (!(sender instanceof Player p)) {
            lang.send(sender, plugin, "error.players-only");
            return true;
        }
        if (a.length == 0) {
            guilds.openMenu(p);
            return true;
        }
        String arg1 = a.length > 1 ? a[1] : null;
        switch (a[0].toLowerCase()) {
            case "create" -> {
                if (a.length < 3) lang.send(p, plugin, "help.create");
                else guilds.create(p, a[1], String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
            }
            case "invite" -> { if (arg1 == null) lang.send(p, plugin, "help.invite"); else guilds.invite(p, arg1); }
            case "accept" -> guilds.accept(p, arg1);
            case "kick" -> { if (arg1 == null) lang.send(p, plugin, "help.kick"); else guilds.kick(p, arg1); }
            case "leave" -> guilds.leave(p);
            case "disband" -> guilds.disband(p);
            case "info" -> guilds.info(p, arg1);
            case "top" -> guilds.top(p);
            case "chat" -> guilds.toggleChat(p);
            case "c" -> {
                if (a.length < 2) guilds.toggleChat(p);
                else guilds.guildMessage(p, String.join(" ", Arrays.copyOfRange(a, 1, a.length)));
            }
            case "home" -> guilds.home(p);
            case "sethome" -> guilds.setHome(p);
            case "promote", "demote" -> { if (arg1 == null) lang.send(p, plugin, "help.promote"); else guilds.promote(p, arg1, a[0].equalsIgnoreCase("promote")); }
            case "transfer" -> { if (arg1 == null) lang.send(p, plugin, "help.transfer"); else guilds.transfer(p, arg1); }
            case "bank" -> {
                if (a.length >= 3 && (a[1].equalsIgnoreCase("deposit") || a[1].equalsIgnoreCase("withdraw"))) guilds.bank(p, a[1].toLowerCase(), a[2]);
                else guilds.bank(p, null, null);
            }
            case "ally" -> { if (arg1 == null) lang.send(p, plugin, "help.ally"); else guilds.ally(p, arg1, true); }
            case "unally" -> { if (arg1 == null) lang.send(p, plugin, "help.ally"); else guilds.ally(p, arg1, false); }
            default -> lang.send(p, plugin, "help.all");
        }
        return true;
    }

    private boolean admin(CommandSender sender, String[] a) {
        if (a.length == 0) {
            lang.send(sender, plugin, "admin.usage");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "reload" -> {
                reload.run();
                lang.send(sender, plugin, "admin.reloaded");
            }
            case "disband" -> {
                Guild g = a.length > 1 ? guilds.store().byTag(a[1]) : null;
                if (g == null) {
                    lang.send(sender, plugin, "error.no-guild", Map.of("tag", a.length > 1 ? a[1] : ""));
                    return true;
                }
                guilds.store().remove(g);
                guilds.save();
                lang.send(sender, plugin, "admin.disbanded", Map.of("tag", g.tag));
            }
            default -> lang.send(sender, plugin, "admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] a) {
        if (cmd.getName().equalsIgnoreCase("@guilds")) {
            return a.length == 1 ? List.of("reload", "disband").stream().filter(s -> s.startsWith(a[0].toLowerCase())).toList() : List.of();
        }
        if (a.length == 1) return SUBS.stream().filter(s -> s.startsWith(a[0].toLowerCase())).toList();
        if (a.length == 2) {
            String sub = a[0].toLowerCase();
            if (List.of("invite", "kick", "promote", "demote", "transfer").contains(sub)) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[1].toLowerCase())).toList();
            }
            if (List.of("ally", "unally", "info", "accept").contains(sub)) {
                return guilds.store().all().stream().map(g -> g.tag).filter(t -> t.toLowerCase().startsWith(a[1].toLowerCase())).toList();
            }
            if (sub.equals("bank")) return List.of("deposit", "withdraw");
        }
        return List.of();
    }
}
