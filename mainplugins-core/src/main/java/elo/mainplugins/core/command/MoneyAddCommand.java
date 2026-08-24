package elo.mainplugins.core.command;

import elo.mainplugins.core.api.EconomyService;
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
 * /moneyadd [gracz] <kwota> - testowe doładowanie ekonomii, bez tego trzeba by
 * było zbierać/sprzedawać przedmioty ręcznie, żeby przetestować cokolwiek
 * płatnego (sklep, ulepszenia wyspy, spawnery). Bez podanego gracza doładowuje
 * nadawcy, jeśli jest graczem.
 */
public class MoneyAddCommand implements CommandExecutor, TabCompleter {

    private final EconomyService economyService;

    public MoneyAddCommand(EconomyService economyService) {
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String targetName;
        double kwota;

        if (args.length >= 2) {
            targetName = args[0];
            kwota = parsujKwote(sender, args[1]);
        } else if (args.length == 1 && sender instanceof Player self) {
            targetName = self.getName();
            kwota = parsujKwote(sender, args[0]);
        } else {
            sender.sendMessage(Component.text("Użycie: /moneyadd [gracz] <kwota>", NamedTextColor.RED));
            return true;
        }

        if (Double.isNaN(kwota)) return true;

        Player online = Bukkit.getPlayerExact(targetName);
        @SuppressWarnings("deprecation")
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(targetName);
        if (online == null && !target.hasPlayedBefore()) {
            sender.sendMessage(Component.text("Nie znaleziono gracza o nicku " + targetName + ".", NamedTextColor.RED));
            return true;
        }

        economyService.dodajKase(target.getUniqueId(), kwota);
        sender.sendMessage(Component.text("Dodano " + kwota + " $ dla " + targetName + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        // Tylko pierwszy argument bywa nickiem - drugi (kwota) i tak nie da się sensownie podpowiedzieć.
        return args.length == 1 ? TabCompleteUtils.dopasujGraczy(args[0]) : TabCompleteUtils.PUSTA;
    }

    private double parsujKwote(CommandSender sender, String raw) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Kwota musi być liczbą.", NamedTextColor.RED));
            return Double.NaN;
        }
    }
}