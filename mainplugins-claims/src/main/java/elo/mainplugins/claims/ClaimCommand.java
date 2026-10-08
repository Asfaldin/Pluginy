package elo.mainplugins.claims;

import elo.mainplugins.core.api.LangService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /claim (gracze) i /@claims (admin: reload, bypass). */
final class ClaimCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("unclaim", "list", "trust", "untrust", "info", "show", "flag", "help");

    private final JavaPlugin plugin;
    private final ClaimManager claims;
    private final LangService lang;
    private final Runnable reload;

    ClaimCommand(JavaPlugin plugin, ClaimManager claims, LangService lang, Runnable reload) {
        this.plugin = plugin;
        this.claims = claims;
        this.lang = lang;
        this.reload = reload;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] a) {
        if (cmd.getName().equalsIgnoreCase("@claims")) {
            if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                reload.run();
                lang.send(sender, plugin, "admin.reloaded");
            } else if (a.length > 0 && a[0].equalsIgnoreCase("bypass") && sender instanceof Player p) {
                lang.send(p, plugin, claims.toggleBypass(p) ? "admin.bypass-on" : "admin.bypass-off");
            } else {
                lang.send(sender, plugin, "admin.usage");
            }
            return true;
        }
        if (!(sender instanceof Player p)) {
            lang.send(sender, plugin, "error.players-only");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("unclaim")) {
            claims.unclaim(p);
            return true;
        }
        if (a.length == 0) {
            claims.claim(p);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "unclaim" -> claims.unclaim(p);
            case "list" -> claims.list(p);
            case "trust", "untrust" -> {
                if (a.length < 2) lang.send(p, plugin, "help.trust");
                else claims.trust(p, a[1], a[0].equalsIgnoreCase("trust"));
            }
            case "info", "show" -> claims.info(p);
            case "flag" -> claims.flag(p, a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null);
            default -> lang.send(p, plugin, "help.all");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String @NotNull [] a) {
        if (cmd.getName().equalsIgnoreCase("@claims")) return a.length == 1 ? List.of("reload", "bypass") : List.of();
        if (cmd.getName().equalsIgnoreCase("unclaim")) return List.of();
        if (a.length == 1) return SUBS.stream().filter(s -> s.startsWith(a[0].toLowerCase())).toList();
        if (a.length == 2 && (a[0].equalsIgnoreCase("trust") || a[0].equalsIgnoreCase("untrust"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase().startsWith(a[1].toLowerCase())).toList();
        }
        if (a.length == 2 && a[0].equalsIgnoreCase("flag")) return List.of("pvp", "explosions", "mobs");
        if (a.length == 3 && a[0].equalsIgnoreCase("flag")) return List.of("on", "off");
        return List.of();
    }
}
