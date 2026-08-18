package elo.mainplugins.core.command;

import elo.mainplugins.core.api.EconomyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /pay <gracz> <kwota> - przelew pieniędzy między graczami, dostępny dla każdego
 * (w przeciwieństwie do /moneyadd, to nie jest narzędzie administratora). Wspiera
 * też odbiorców offline (patrz resolvowanie przez OfflinePlayer/hasPlayedBefore,
 * ten sam wzorzec co MoneyAddCommand i IslandManager.usunCzlonkaKomenda).
 */
public class PayCommand implements CommandExecutor {

    private final EconomyService economyService;

    public PayCommand(EconomyService economyService) {
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(Component.text("Użycie: /pay <gracz> <kwota>", NamedTextColor.RED));
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
            player.sendMessage(Component.text("Nie możesz przelać pieniędzy samemu sobie!", NamedTextColor.RED));
            return true;
        }

        double kwota;
        try {
            kwota = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Kwota musi być liczbą.", NamedTextColor.RED));
            return true;
        }
        if (kwota <= 0) {
            player.sendMessage(Component.text("Kwota musi być większa od zera.", NamedTextColor.RED));
            return true;
        }

        // Od razu na grosze - unikamy drugiego zaokrąglenia i pracujemy w dokładnej
        // jednostce, w której faktycznie liczy EconomyManager.
        long grosze = Math.round(kwota * 100);
        if (grosze <= 0) {
            player.sendMessage(Component.text("Kwota musi być większa od zera.", NamedTextColor.RED));
            return true;
        }

        // pobierzGrosze sprawdza saldo i pobiera w jednym kroku, więc nie da się
        // między sprawdzeniem a pobraniem wcisnąć drugiego /pay tego samego gracza.
        if (!economyService.pobierzGrosze(player.getUniqueId(), grosze)) {
            player.sendMessage(Component.text("Nie masz wystarczająco pieniędzy!", NamedTextColor.RED));
            return true;
        }

        economyService.dodajGrosze(target.getUniqueId(), grosze);

        String kwotaTekst = formatujGrosze(grosze);
        player.sendMessage(Component.text("Przelano " + kwotaTekst + "$ dla " + targetName + ".", NamedTextColor.GREEN));
        if (online != null) {
            online.sendMessage(Component.text("Otrzymałeś " + kwotaTekst + "$ od " + player.getName() + "!", NamedTextColor.GREEN));
        }
        return true;
    }

    /** "10000" -> "100", "10050" -> "100.50" - bez ".0" na okrągłych kwotach. */
    private static String formatujGrosze(long grosze) {
        long jednostki = grosze / 100;
        long reszta = grosze % 100;
        return reszta == 0 ? String.valueOf(jednostki) : String.format("%d.%02d", jednostki, reszta);
    }
}