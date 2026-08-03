package elo.mainplugins.core.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * /adminhelp - "wszystko naraz" dla operatora: status każdego pluginu z rodziny
 * Mainplugins, podstawowe statystyki serwera i pełna segmentowa lista komend
 * (to samo co /komendy, tylko w jednym miejscu z resztą). Chronione uprawnieniem
 * (domyślnie: op) - w przeciwieństwie do /komendy, które jest dla każdego gracza.
 */
public class AdminHelpCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        sender.sendMessage(Component.text("=== Mainplugins - panel administratora ===", NamedTextColor.GOLD, TextDecoration.BOLD));

        sender.sendMessage(Component.text("Online: ", NamedTextColor.GRAY)
                .append(Component.text(Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers(), NamedTextColor.GREEN))
                .append(Component.text("  TPS: ", NamedTextColor.GRAY))
                .append(Component.text(String.format(java.util.Locale.US, "%.1f", Math.min(Bukkit.getTPS()[0], 20.0)), NamedTextColor.GREEN)));

        sender.sendMessage(Component.text("— Moduły —", NamedTextColor.AQUA, TextDecoration.BOLD));
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!plugin.getName().startsWith("Mainplugins")) continue;
            NamedTextColor kolor = plugin.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED;
            String status = plugin.isEnabled() ? "WŁĄCZONY" : "WYŁĄCZONY";
            sender.sendMessage(Component.text(" - " + plugin.getName() + " v" + plugin.getDescription().getVersion() + " ", NamedTextColor.GRAY)
                    .append(Component.text("[" + status + "]", kolor)));
        }

        sender.sendMessage(Component.text("— Komendy —", NamedTextColor.AQUA, TextDecoration.BOLD));
        CommandCatalog.wypisz(sender);
        return true;
    }
}