package elo.mainplugins.market;

import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.util.MenuBridge;
import elo.mainplugins.core.util.MoneyFormat;
import elo.mainplugins.core.util.TabCompleteUtils;
import elo.mainplugins.market.model.Listing;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Gracz: /market [sell|wystaw <cena>]. Admin: /@market reload | list <gracz> | remove <gracz>. */
final class MarketCommand {

    private MarketCommand() {}

    static CommandExecutor player(Plugin plugin, MarketManager market, LangService lang) {
        return (sender, command, label, args) -> {
            if (!(sender instanceof Player player)) {
                lang.send(sender, plugin, "admin.players-only");
                return true;
            }
            String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
            if (sub.equals("sell") || sub.equals("wystaw")) {
                if (args.length < 2) lang.send(player, plugin, "sell.usage");
                else market.sell(player, args[1]);
            } else {
                market.openMain(player, 0, MenuBridge.isZMenu(args));
            }
            return true;
        };
    }

    static TabCompleter playerTab() {
        return (sender, command, alias, args) ->
                args.length == 1 ? TabCompleteUtils.dopasuj(args[0], List.of("sell")) : TabCompleteUtils.PUSTA;
    }

    static final class Admin implements CommandExecutor, TabCompleter {

        private final Plugin plugin;
        private final MarketManager market;
        private final LangService lang;

        Admin(Plugin plugin, MarketManager market, LangService lang) {
            this.plugin = plugin;
            this.market = market;
            this.lang = lang;
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
            String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
            switch (sub) {
                case "reload" -> {
                    market.reload();
                    lang.send(sender, plugin, "admin.reloaded", Map.of("offers", String.valueOf(market.store().listings().size())));
                }
                case "list", "remove" -> {
                    if (args.length < 2) {
                        lang.send(sender, plugin, "admin.usage");
                        return true;
                    }
                    OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[1]);
                    if (target == null) {
                        lang.send(sender, plugin, "admin.player-not-found", Map.of("player", args[1]));
                        return true;
                    }
                    String name = target.getName() != null ? target.getName() : args[1];
                    if (sub.equals("remove")) {
                        int n = market.removeAll(target.getUniqueId());
                        lang.send(sender, plugin, "admin.removed", Map.of("count", String.valueOf(n), "player", name));
                    } else {
                        list(sender, target, name);
                    }
                }
                default -> lang.send(sender, plugin, "admin.usage");
            }
            return true;
        }

        private void list(CommandSender sender, OfflinePlayer target, String name) {
            List<Listing> own = market.store().listings().stream().filter(l -> l.seller().equals(target.getUniqueId())).toList();
            if (own.isEmpty()) {
                lang.send(sender, plugin, "admin.list-empty", Map.of("player", name));
                return;
            }
            lang.send(sender, plugin, "admin.list-header", Map.of("player", name, "count", String.valueOf(own.size())));
            for (Listing l : own) {
                ItemStack item = MarketManager.decode(l.item());
                lang.send(sender, plugin, "admin.list-line", Map.of(
                        "item", item == null ? "?" : MarketManager.itemName(item),
                        "price", MoneyFormat.pelna(l.price()), "id", l.id()));
            }
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
            if (args.length == 1) return TabCompleteUtils.dopasuj(args[0], List.of("reload", "list", "remove"));
            if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) return TabCompleteUtils.dopasujGraczy(args[1]);
            return TabCompleteUtils.PUSTA;
        }
    }
}
