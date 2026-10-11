package elo.mainplugins.spawners;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomMobService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.spawners.config.SpawnerConfigLoader;
import elo.mainplugins.spawners.config.SpawnerTypeDef;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /@spawner - komendy admina: give (spawner do ekwipunku, np. do testów albo nagród), list (typy),
 * info (spawner, na który patrzysz), reload (to samo co /@reloadspawnery).
 */
final class SpawnerAdminCommand implements TabExecutor {

    private final Plugin plugin;
    private final SpawnerManager manager;
    private final LangService lang;

    SpawnerAdminCommand(Plugin plugin, SpawnerManager manager, LangService lang) {
        this.plugin = plugin;
        this.manager = manager;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> give(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender);
            case "reload" -> {
                manager.aktualizujKonfiguracje(SpawnerConfigLoader.load(plugin));
                lang.send(sender, plugin, "reload.done");
            }
            default -> lang.send(sender, plugin, "admin.help");
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            lang.send(sender, plugin, "admin.help");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            lang.send(sender, plugin, "admin.no-player", Map.of("player", args[1]));
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException e) {
                lang.send(sender, plugin, "admin.help");
                return;
            }
        }
        ItemStack item = manager.createItem(args[2], amount);
        if (item == null) {
            lang.send(sender, plugin, "admin.no-type", Map.of("type", args[2]));
            return;
        }
        target.getInventory().addItem(item).values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
        SpawnerTypeDef typ = manager.typZTagu(args[2]);
        lang.send(sender, plugin, "admin.given", Map.of("player", target.getName(), "amount", String.valueOf(amount), "type", typ.nazwaOdmieniona()));
    }

    private void list(CommandSender sender) {
        CustomMobService moby = CoreAPI.getCustomMobService();
        lang.send(sender, plugin, "admin.list-head", Map.of("count", String.valueOf(manager.config().typy().size()), "placed", String.valueOf(manager.liczbaPostawionych())));
        for (SpawnerTypeDef t : manager.config().typy().values()) {
            String mob = t.custom()
                    ? "custom: " + t.customMob() + (moby == null || !moby.ids().contains(t.customMob()) ? " (!)" : "")
                    : t.entityType().name();
            lang.send(sender, plugin, "admin.list-row", Map.of("id", t.id(), "type", t.nazwaOdmieniona(), "mob", mob,
                    "seconds", String.valueOf(t.interwalSekund(1)), "amount", String.valueOf(t.iloscNaCykl(1)),
                    "mode", t.stackowanie() ? "stack" : "max " + t.maxNaRaz()));
        }
    }

    private void info(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            lang.send(sender, plugin, "admin.players-only");
            return;
        }
        Block block = player.getTargetBlockExact(8);
        SpawnerManager.Instancja i = block == null || block.getType() != Material.SPAWNER ? null : manager.instancjaW(block);
        if (i == null) {
            lang.send(player, plugin, "admin.not-spawner");
            return;
        }
        SpawnerTypeDef t = manager.aktualnyTyp(i);
        String owner = Bukkit.getOfflinePlayer(i.ownerUUID).getName();
        lang.send(player, plugin, "admin.info", Map.of(
                "type", t.nazwaOdmieniona(), "id", t.id(),
                "owner", owner == null ? i.ownerUUID.toString() : owner,
                "levels", manager.opisPoziomow(i),
                "stack", String.valueOf(t.stackowanie() ? i.rozmiarStosu : i.zywe.size()),
                "next", String.valueOf(manager.sekundDoSpawnu(i))));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("give", "list", "info", "reload")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            for (String id : manager.config().typy().keySet()) if (id.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(id);
        }
        return out;
    }
}
