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
 * /moneyundo [gracz] - zabiera CAŁĄ kasę gracza (zeruje portfel), odwrotność
 * /moneyadd do testów. Bez podanego gracza zeruje nadawcy, jeśli jest graczem.
 */
public class MoneyUndoCommand implements CommandExecutor {

    private final EconomyService economyService;

    public MoneyUndoCommand(EconomyService economyService) {
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String targetName;
        if (args.length >= 1) {
            targetName = args[0];
        } else if (sender instanceof Player self) {
            targetName = self.getName();
        } else {
            sender.sendMessage(Component.text("Użycie: /moneyundo [gracz]", NamedTextColor.RED));
            return true;
        }

        Player online = Bukkit.getPlayerExact(targetName);
        @SuppressWarnings("deprecation")
        OfflinePlayer target = online != null ? online : Bukkit.getOfflinePlayer(targetName);
        if (online == null && !target.hasPlayedBefore()) {
            sender.sendMessage(Component.text("Nie znaleziono gracza o nicku " + targetName + ".", NamedTextColor.RED));
            return true;
        }

        economyService.setKasa(target.getUniqueId(), 0);
        sender.sendMessage(Component.text("Wyzerowano kasę gracza " + targetName + ".", NamedTextColor.GREEN));
        return true;
    }
}