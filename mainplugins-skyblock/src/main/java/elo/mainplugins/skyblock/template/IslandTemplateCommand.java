package elo.mainplugins.skyblock.template;

import elo.mainplugins.core.api.LangService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
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
 * /@islandtemplate rozdzka | pos1 | pos2 | save [nazwa] - admin buduje wyspę w grze, zaznacza dwa
 * przeciwległe narożniki (różdżką: LPM/PPM na bloku, jak różdżka obszarów, albo pos1/pos2 tam, gdzie
 * lata) i zapisuje; blok, na którym stoi przy "save", to punkt, na który trafia gracz na nowej wyspie.
 */
public final class IslandTemplateCommand implements CommandExecutor, TabCompleter, Listener {

    private final Plugin plugin;
    private final IslandTemplate template;
    private final LangService lang;
    private final Map<UUID, Location[]> corners = new HashMap<>();
    private final NamespacedKey wandKey;

    public IslandTemplateCommand(Plugin plugin, IslandTemplate template, LangService lang) {
        this.plugin = plugin;
        this.template = template;
        this.lang = lang;
        this.wandKey = new NamespacedKey(plugin, "island_template_wand");
    }

    private ItemStack rozdzka() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(lang.msg(plugin, "template.wand-name").decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                lang.msg(plugin, "template.wand-lore-1").decoration(TextDecoration.ITALIC, false),
                lang.msg(plugin, "template.wand-lore-2").decoration(TextDecoration.ITALIC, false),
                lang.msg(plugin, "template.wand-lore-3").decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean jestRozdzka(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    /** Różdżka wzoru: LPM na bloku = róg 1, PPM = róg 2 (jak różdżka obszarów w pluginie spawnu). */
    @EventHandler
    public void onWandClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (!jestRozdzka(player.getInventory().getItemInMainHand())) return;
        event.setCancelled(true); // różdżka nie kopie i nie stawia bloków
        if (!player.hasPermission("mainplugins.skyblock.template") || event.getClickedBlock() == null) return;

        Location[] sel = corners.computeIfAbsent(player.getUniqueId(), k -> new Location[2]);
        int n = event.getAction() == Action.LEFT_CLICK_BLOCK ? 1 : 2;
        Location blok = event.getClickedBlock().getLocation();
        sel[n - 1] = blok;
        lang.send(player, plugin, "template.pos-set", Map.of("n", String.valueOf(n),
                "x", String.valueOf(blok.getBlockX()), "y", String.valueOf(blok.getBlockY()), "z", String.valueOf(blok.getBlockZ())));
        if (sel[0] != null && sel[1] != null && Objects.equals(sel[0].getWorld(), sel[1].getWorld())) {
            lang.send(player, plugin, "template.selected", Map.of(
                    "sx", String.valueOf(Math.abs(sel[0].getBlockX() - sel[1].getBlockX()) + 1),
                    "sy", String.valueOf(Math.abs(sel[0].getBlockY() - sel[1].getBlockY()) + 1),
                    "sz", String.valueOf(Math.abs(sel[0].getBlockZ() - sel[1].getBlockZ()) + 1)));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player player) || args.length < 1) {
            lang.send(sender, plugin, "template.usage");
            return true;
        }
        Location[] sel = corners.computeIfAbsent(player.getUniqueId(), k -> new Location[2]);
        // Narożniki = blok, w którym admin jest (lata w kreatywnym, bez pomocniczych bloków);
        // origin przy "save" = blok pod stopami (stoi na wyspie tam, gdzie ma lądować gracz).
        Location feet = player.getLocation().getBlock().getLocation();
        Location here = feet.clone().subtract(0, 1, 0);
        switch (args[0].toLowerCase()) {
            case "rozdzka", "wand" -> {
                player.getInventory().addItem(rozdzka());
                lang.send(player, plugin, "template.wand-given");
            }
            case "pos1", "pos2" -> {
                int n = args[0].endsWith("1") ? 1 : 2;
                sel[n - 1] = feet;
                lang.send(player, plugin, "template.pos-set", Map.of("n", String.valueOf(n),
                        "x", String.valueOf(feet.getBlockX()), "y", String.valueOf(feet.getBlockY()), "z", String.valueOf(feet.getBlockZ())));
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
                String id = IslandTemplate.czysteId(args.length > 1 ? args[1] : IslandTemplate.DOMYSLNY);
                try {
                    TemplateMath.Pos size = template.save(id, sel[0], sel[1], here);
                    lang.send(player, plugin, "template.saved", Map.of("sx", String.valueOf(size.x()),
                            "sy", String.valueOf(size.y()), "sz", String.valueOf(size.z()), "id", id));
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
        if (args.length == 1) return List.of("rozdzka", "pos1", "pos2", "save");
        if (args.length == 2 && args[0].equalsIgnoreCase("save")) return template.zapisane();
        return List.of();
    }
}
