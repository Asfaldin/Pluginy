package elo.mainplugins.advancements.command;

import elo.mainplugins.advancements.AchievementManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /resetzadania [gracz]} - kasuje CAŁY postęp osiągnięć (zdobyte, odebrane,
 * kolejkę zaległych nagród) i cofa natywne advancementy z datapacka, żeby dało się
 * zrobić wszystko od nowa. Bez argumentu resetuje siebie; z podanym graczem wymaga
 * permisji {@code mainplugins.advancements.admin}.
 */
public final class ResetCommand implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final AchievementManager manager;

    public ResetCommand(AchievementManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1) {
            if (!sender.hasPermission("mainplugins.advancements.admin")) {
                sender.sendMessage(SER.deserialize("&cMożesz zresetować tylko własne zadania: /resetzadania"));
                return true;
            }
            OfflinePlayer cel = Bukkit.getPlayerExact(args[0]);
            if (cel == null) cel = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (cel == null) {
                sender.sendMessage(SER.deserialize("&cNie znam gracza '" + args[0] + "'."));
                return true;
            }
            manager.adminReset(cel.getUniqueId());
            sender.sendMessage(SER.deserialize("&aZresetowano zadania gracza &f" + cel.getName() + "&a."));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Podaj gracza: /resetzadania <gracz>");
            return true;
        }
        manager.adminReset(player.getUniqueId());
        player.sendMessage(SER.deserialize("&aTwoje zadania zostały wyzerowane - możesz robić wszystko od nowa."));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("mainplugins.advancements.admin")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
