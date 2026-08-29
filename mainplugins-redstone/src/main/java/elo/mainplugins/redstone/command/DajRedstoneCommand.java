package elo.mainplugins.redstone.command;

import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.redstone.item.RedstoneItemManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** /@dajredstone <id> [gracz] [ilość] - wydawanie/testowanie redstone-itemów, wzorowane na Core'owym DajCustomCommand, scope'owane do RedstoneItemManager. */
public final class DajRedstoneCommand implements CommandExecutor, TabCompleter {

    private final RedstoneItemManager items;

    public DajRedstoneCommand(RedstoneItemManager items) {
        this.items = items;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("Użycie: /@dajredstone <id> [gracz] [ilość]", NamedTextColor.RED));
            return true;
        }

        String id = args[0].toUpperCase(Locale.ROOT);
        if (!items.exists(id)) {
            sender.sendMessage(Component.text("Nieznany redstone-item: " + id + ". Dostępne: " + String.join(", ", items.ids()), NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Nie znaleziono gracza o nicku " + args[1] + ".", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Użycie z konsoli: /@dajredstone <id> <gracz> [ilość]", NamedTextColor.RED));
            return true;
        }

        int ilosc = 1;
        if (args.length >= 3) {
            try {
                ilosc = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Ilość musi być liczbą.", NamedTextColor.RED));
                return true;
            }
        }

        ItemStack item = items.create(id, ilosc);
        var leftover = target.getInventory().addItem(item);
        leftover.values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));

        sender.sendMessage(Component.text("Wydano " + ilosc + "x " + id + " dla " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return TabCompleteUtils.dopasuj(args[0].toUpperCase(Locale.ROOT), items.ids());
        if (args.length == 2) return TabCompleteUtils.dopasujGraczy(args[1]);
        return TabCompleteUtils.PUSTA;
    }
}
