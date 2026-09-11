package elo.mainplugins.announcer.command;

import elo.mainplugins.announcer.model.Channel;
import elo.mainplugins.announcer.send.Dispatcher;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code /announce <tekst>}            - natychmiastowy broadcast na czacie
 * {@code /announce preview <tekst>}    - podgląd tylko do siebie (nie rusza nikogo innego)
 * {@code /announce as <grupa>}         - wyślij teraz jedną wiadomość z danej grupy
 */
public final class AnnounceCommand implements CommandExecutor, TabCompleter {

    private final Dispatcher dispatcher;
    private final Function<String, Boolean> fireGroupNow;
    private final Supplier<List<String>> groupNames;

    public AnnounceCommand(Dispatcher dispatcher, Function<String, Boolean> fireGroupNow, Supplier<List<String>> groupNames) {
        this.dispatcher = dispatcher;
        this.fireGroupNow = fireGroupNow;
        this.groupNames = groupNames;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§eUżycie: §f/announce <tekst>§7 | §f/announce preview <tekst>§7 | §f/announce as <grupa>");
            return true;
        }

        if (args[0].equalsIgnoreCase("preview")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("§cPodgląd działa tylko dla gracza.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§cPodaj tekst do podglądu.");
                return true;
            }
            dispatcher.previewTo(p, join(args, 1));
            return true;
        }

        if (args[0].equalsIgnoreCase("as")) {
            if (args.length < 2) {
                sender.sendMessage("§cPodaj nazwę grupy: §f/announce as <grupa>");
                return true;
            }
            boolean ok = fireGroupNow.apply(args[1]);
            sender.sendMessage(ok ? "§aWysłano ogłoszenie z grupy §f" + args[1] + "§a."
                    : "§cNie ma grupy §f" + args[1] + "§c albo jest pusta.");
            return true;
        }

        dispatcher.dispatchAdHoc(join(args, 0), List.of(Channel.CHAT));
        sender.sendMessage("§aOgłoszenie wysłane.");
        return true;
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("preview", "as")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("as")) {
            for (String g : groupNames.get()) {
                if (g.toLowerCase().startsWith(args[1].toLowerCase())) out.add(g);
            }
        }
        return out;
    }
}
