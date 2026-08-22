package elo.mainplugins.chatfilter;

import elo.mainplugins.core.util.TabCompleteUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /wycisz (alias /mute) <gracz> - osobiste wyciszenie, przełącznik (drugie wywołanie na
 * tym samym graczu = odciszenie). Dostępne dla każdego, bez permisji - to nie jest
 * narzędzie moderacji, tylko osobisty filtr czatu.
 */
public class MuteCommand implements CommandExecutor, TabCompleter {

    private final MuteManager muteManager;

    public MuteCommand(MuteManager muteManager) {
        this.muteManager = muteManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(Component.text("Użycie: /" + label + " <gracz>", NamedTextColor.RED));
            return true;
        }

        String targetName = args[0];
        Player online = Bukkit.getPlayerExact(targetName);
        @SuppressWarnings("deprecation")
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(targetName);
        if (online == null && !target.hasPlayedBefore()) {
            player.sendMessage(Component.text("Nie znaleziono gracza o nicku " + targetName + ".", NamedTextColor.RED));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Nie możesz wyciszyć samego siebie!", NamedTextColor.RED));
            return true;
        }

        boolean terazWyciszony = muteManager.przelacz(player, target);
        String nick = target.getName() != null ? target.getName() : targetName;

        if (terazWyciszony) {
            player.sendMessage(Component.text("Wyciszono gracza " + nick + " - nie będziesz już widział jego wiadomości na czacie (tylko u Ciebie).", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("Odciszono gracza " + nick + ".", NamedTextColor.GREEN));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        return args.length == 1 ? TabCompleteUtils.dopasujGraczy(args[0]) : TabCompleteUtils.PUSTA;
    }
}
