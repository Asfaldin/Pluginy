package elo.mainplugins.ranks;

import elo.mainplugins.core.api.Rank;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RankCommands implements CommandExecutor, TabCompleter {

    private final RankManager rankManager;

    public RankCommands(RankManager rankManager) {
        this.rankManager = rankManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (command.getName().equalsIgnoreCase("setranga")) {
            return obslugaSetranga(sender, args);
        }
        if (command.getName().equalsIgnoreCase("ranga")) {
            return obslugaRanga(sender, args);
        }
        return false;
    }

    private boolean obslugaSetranga(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Użycie: /setranga <gracz> <gracz|vip|admin>", NamedTextColor.RED));
            return true;
        }

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(Component.text("Nie znaleziono gracza o nicku " + args[0] + ".", NamedTextColor.RED));
            return true;
        }

        Rank nowaRanga;
        try {
            nowaRanga = Rank.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("Nieznana ranga. Dostępne: gracz, vip, admin.", NamedTextColor.RED));
            return true;
        }

        Rank stara = rankManager.setRank(target.getUniqueId(), nowaRanga, target.getPlayer());
        sender.sendMessage(Component.text("Zmieniono rangę " + target.getName() + ": " + stara + " -> " + nowaRanga, NamedTextColor.GREEN));

        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(Component.text("Twoja ranga została zmieniona na: " + nowaRanga, NamedTextColor.AQUA));
        }
        return true;
    }

    private boolean obslugaRanga(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length >= 1) {
            @SuppressWarnings("deprecation")
            OfflinePlayer resolved = Bukkit.getOfflinePlayer(args[0]);
            target = resolved;
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Użycie: /ranga <gracz>", NamedTextColor.RED));
            return true;
        }

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            sender.sendMessage(Component.text("Nie znaleziono gracza o nicku " + args[0] + ".", NamedTextColor.RED));
            return true;
        }

        Rank rank = rankManager.getRank(target.getUniqueId());
        sender.sendMessage(Component.text(target.getName() + " ma rangę: " + rank, NamedTextColor.AQUA));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("setranga")) {
            if (args.length == 1) {
                return null; // domyślna podpowiedź nicków online od Bukkita
            }
            if (args.length == 2) {
                List<String> wynik = new ArrayList<>();
                for (Rank rank : Rank.values()) {
                    String nazwa = rank.name().toLowerCase(Locale.ROOT);
                    if (nazwa.startsWith(args[1].toLowerCase(Locale.ROOT))) wynik.add(nazwa);
                }
                return wynik;
            }
        }
        return List.of();
    }
}
