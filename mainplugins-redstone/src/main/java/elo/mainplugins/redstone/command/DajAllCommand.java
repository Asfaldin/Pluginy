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

/** /@dajall [gracz] - wydaje po 1 sztuce KAŻDEGO zarejestrowanego redstone-itemu naraz (kabel + stacja + sadzarka) - wygodne do szybkiego testu całego zestawu. */
public final class DajAllCommand implements CommandExecutor, TabCompleter {

    private final RedstoneItemManager items;

    public DajAllCommand(RedstoneItemManager items) {
        this.items = items;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Nie znaleziono gracza o nicku " + args[0] + ".", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage(Component.text("Użycie z konsoli: /@dajall <gracz>", NamedTextColor.RED));
            return true;
        }

        int wydano = 0;
        for (String id : items.ids()) {
            ItemStack item = items.create(id, 1);
            if (item == null) continue;
            var leftover = target.getInventory().addItem(item);
            leftover.values().forEach(i -> target.getWorld().dropItemNaturally(target.getLocation(), i));
            wydano++;
        }

        sender.sendMessage(Component.text("Wydano cały zestaw (" + wydano + " itemów) dla " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return TabCompleteUtils.dopasujGraczy(args[0]);
        return TabCompleteUtils.PUSTA;
    }
}
