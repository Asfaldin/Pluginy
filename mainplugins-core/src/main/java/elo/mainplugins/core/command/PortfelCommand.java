package elo.mainplugins.core.command;

import elo.mainplugins.core.api.EconomyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** /portfel (alias /p) - pokazuje graczowi ile ma kasy na koncie. */
public class PortfelCommand implements CommandExecutor {

    private final EconomyService economyService;

    public PortfelCommand(EconomyService economyService) {
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Tylko gracz moze uzyc tej komendy.");
            return true;
        }

        double kasa = economyService.getKasa(player.getUniqueId());
        player.sendMessage(Component.text("Masz " + String.format(Locale.US, "%.2f", kasa) + " $ w portfelu.", NamedTextColor.GOLD));
        return true;
    }
}
