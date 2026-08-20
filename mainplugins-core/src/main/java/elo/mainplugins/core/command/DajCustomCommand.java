package elo.mainplugins.core.command;

import elo.mainplugins.core.api.CustomItemService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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

/**
 * /@dajcustom <id> [gracz] [ilość] - wydaje/testuje custom item z rejestru
 * (patrz CustomItemManager, custom-items.yml). Id dopasowywane jest bez
 * względu na wielkość liter wpisaną w komendzie (samo w sobie zawsze
 * WIELKIMI LITERAMI, patrz komentarz w custom-items.yml) - wygodniej się wpisuje.
 */
public class DajCustomCommand implements CommandExecutor, TabCompleter {

    private final CustomItemService customItemService;

    public DajCustomCommand(CustomItemService customItemService) {
        this.customItemService = customItemService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("Użycie: /@dajcustom <id> [gracz] [ilość]", NamedTextColor.RED));
            return true;
        }

        String id = args[0].toUpperCase(Locale.ROOT);
        if (!customItemService.exists(id)) {
            sender.sendMessage(Component.text("Nieznany custom item: " + id + ". Dostępne: "
                    + String.join(", ", customItemService.ids()), NamedTextColor.RED));
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
            sender.sendMessage(Component.text("Użycie z konsoli: /@dajcustom <id> <gracz> [ilość]", NamedTextColor.RED));
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

        var item = customItemService.create(id, ilosc);
        var leftover = target.getInventory().addItem(item);
        leftover.values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));

        sender.sendMessage(Component.text("Wydano " + ilosc + "x " + id + " dla " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            List<String> wynik = new ArrayList<>();
            for (String id : customItemService.ids()) {
                if (id.startsWith(args[0].toUpperCase(Locale.ROOT))) wynik.add(id);
            }
            return wynik;
        }
        if (args.length == 2) {
            return null; // domyślna podpowiedź nicków online od Bukkita
        }
        return List.of();
    }
}
