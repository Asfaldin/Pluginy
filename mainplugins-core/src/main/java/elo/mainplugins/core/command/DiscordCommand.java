package elo.mainplugins.core.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * /discord - wysyła graczowi klikalny link zaproszenia na Discorda serwera.
 * Minecraft nie potrafi "przenieść" gracza do przeglądarki - to co można zrobić,
 * to klikalna wiadomość czatu (ClickEvent.openUrl), która otwiera link w systemowej
 * przeglądarce po kliknięciu.
 */
public class DiscordCommand implements CommandExecutor {

    private static final String INVITE_URL = "https://discord.gg/8zKF4dQrb";

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        Component link = Component.text("» Kliknij, aby dołączyć do naszego Discorda «", NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(ClickEvent.openUrl(INVITE_URL))
                .hoverEvent(HoverEvent.showText(Component.text(INVITE_URL, NamedTextColor.GRAY)));

        sender.sendMessage(link);
        return true;
    }
}
