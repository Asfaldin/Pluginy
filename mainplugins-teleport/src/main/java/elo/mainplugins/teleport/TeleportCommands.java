package elo.mainplugins.teleport;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Wcześniej ta klasa (jako "Komendy") w ogóle nie była zarejestrowana w głównym
 * pluginie - żyła w repo jako martwy kod, a jej komendy (/teleportuj, /tpakceptuj,
 * /tpodrzuc - aliasy /tp, /tpaccept, /tpdeny) nie istniały nawet w plugin.yml.
 * Tutaj jest w końcu realnie podpięta. Case "menu"
 * z oryginału został usunięty - komenda /menu należy wyłącznie do MainpluginsMenu,
 * nie może jej rejestrować drugi plugin.
 */
public class TeleportCommands implements CommandExecutor, TabCompleter {

    private final TeleportManager teleportManager;

    public TeleportCommands(TeleportManager teleportManager) {
        this.teleportManager = teleportManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Te komendy mogą być używane tylko przez gracza!");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "teleportuj" -> {
                if (args.length < 1) {
                    player.sendMessage("Użycie: /" + label + " <gracz>");
                    return true;
                }
                teleportManager.wyslijProsbe(player, args[0]);
                return true;
            }
            case "tpakceptuj" -> {
                teleportManager.zaakceptujProsbe(player);
                return true;
            }
            case "tpodrzuc" -> {
                teleportManager.odrzucProsbe(player);
                return true;
            }
        }
        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        // Tylko /teleportuj bierze argument (nazwa gracza) - reszta (tpakceptuj/tpodrzuc) nic nie przyjmuje.
        if (!command.getName().equalsIgnoreCase("teleportuj") || args.length != 1) return List.of();

        List<String> nazwy = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return StringUtil.copyPartialMatches(args[0], nazwy, new ArrayList<>());
    }
}