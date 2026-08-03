package elo.mainplugins.core.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** /komendy - publiczna, segmentowa lista wszystkich komend serwera (dostępna dla każdego gracza). */
public class PomocCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        sender.sendMessage(Component.text("=== Wszystkie komendy serwera ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        CommandCatalog.wypisz(sender);
        return true;
    }
}