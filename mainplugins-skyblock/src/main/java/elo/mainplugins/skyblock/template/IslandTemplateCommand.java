package elo.mainplugins.skyblock.template;

import elo.mainplugins.core.api.LangService;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /@islandtemplate pos1 | pos2 | save - admin buduje wyspę w grze, zaznacza dwa narożniki
 * (blok pod stopami) i zapisuje; miejsce, w którym stoi przy "save", to punkt, na który
 * trafia gracz na nowej wyspie.
 */
public final class IslandTemplateCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final IslandTemplate template;
    private final LangService lang;
    private final Map<UUID, Location[]> corners = new HashMap<>();

    public IslandTemplateCommand(Plugin plugin, IslandTemplate template, LangService lang) {
        this.plugin = plugin;
        this.template = template;
        this.lang = lang;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player) || args.length < 1) {
            lang.send(sender, plugin, "template.usage");
            return true;
        }
        Location[] sel = corners.computeIfAbsent(player.getUniqueId(), k -> new Location[2]);
        Location here = player.getLocation().getBlock().getLocation().subtract(0, 1, 0);
        switch (args[0].toLowerCase()) {
            case "pos1", "pos2" -> {
                int n = args[0].endsWith("1") ? 1 : 2;
                sel[n - 1] = here;
                lang.send(player, plugin, "template.pos-set", Map.of("n", String.valueOf(n),
                        "x", String.valueOf(here.getBlockX()), "y", String.valueOf(here.getBlockY()), "z", String.valueOf(here.getBlockZ())));
            }
            case "save" -> {
                if (sel[0] == null || sel[1] == null) {
                    lang.send(player, plugin, "template.need-corners");
                    return true;
                }
                if (!Objects.equals(sel[0].getWorld(), sel[1].getWorld()) || !Objects.equals(sel[0].getWorld(), here.getWorld())) {
                    lang.send(player, plugin, "template.other-world");
                    return true;
                }
                try {
                    TemplateMath.Pos size = template.save(sel[0], sel[1], here);
                    lang.send(player, plugin, "template.saved", Map.of("sx", String.valueOf(size.x()),
                            "sy", String.valueOf(size.y()), "sz", String.valueOf(size.z())));
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not save the island template.", e);
                    lang.send(player, plugin, "template.save-failed");
                }
            }
            default -> lang.send(player, plugin, "template.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        return args.length == 1 ? List.of("pos1", "pos2", "save") : List.of();
    }
}
