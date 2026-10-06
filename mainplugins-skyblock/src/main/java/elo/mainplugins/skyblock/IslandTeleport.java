package elo.mainplugins.skyblock;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.core.util.MoneyFormat;
import org.bukkit.command.CommandSender;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.SpawnService;
import elo.mainplugins.core.world.VoidGenerator;
import elo.mainplugins.skyblock.config.IslandConfigLoader;
import elo.mainplugins.skyblock.config.IslandTuning;
import elo.mainplugins.skyblock.event.IslandBankDepositEvent;
import elo.mainplugins.skyblock.event.IslandCreatedEvent;
import elo.mainplugins.skyblock.event.IslandMemberJoinedEvent;
import elo.mainplugins.skyblock.event.IslandUpgradeEvent;
import elo.mainplugins.skyblock.gui.IslandGuiButton;
import elo.mainplugins.skyblock.gui.IslandGuiContent;
import elo.mainplugins.skyblock.gui.IslandGuiHolder;
import elo.mainplugins.skyblock.gui.IslandGuiLoader;
import elo.mainplugins.skyblock.gui.IslandScreen;
import elo.mainplugins.skyblock.template.IslandTemplate;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bezpieczny punkt teleportu na wyspie - szukanie gruntu, żeby gracz nie wpadł w pustkę. */
final class IslandTeleport {

    private IslandTeleport() {}

    /**
     * Gracz mógł wyczyścić cały teren pod punktem teleportu (ręcznie albo np. wybuchem
     * TNT), albo grunt wyspy po prostu leży niżej niż spodziewane (wysokość wyspy z configu + 1) - bez tego
     * gracz teleportowałby się w pustkę i spadał aż do obrażeń od "pustki" (void).
     * Kolejność prób: 1) grunt prosto pod celem (do tuning.maxGlebokoscSzukaniaWDol bloków
     * w dół) - typowy przypadek, najtańszy; 2) najbliższy solidny blok w promieniu
     * tuning.promienSzukaniaObok dookoła, gdy w dół jest za głęboko; 3) w ostateczności -
     * postaw ziemię dokładnie w oryginalnym miejscu.
     */
    static void zabezpieczPunktSpawnu(Location loc, int maxGlebokoscWDol, int promienObok) {
        Location wDol = szukajGruntuWDol(loc, maxGlebokoscWDol);
        if (wDol != null) {
            przeniesXYZ(loc, wDol);
            return;
        }

        Location obok = szukajNajblizszegoGruntu(loc, promienObok);
        if (obok != null) {
            przeniesXYZ(loc, obok);
            return;
        }

        loc.clone().subtract(0, 1, 0).getBlock().setType(Material.DIRT);
    }

    /** Nadpisuje X/Y/Z celu znalezioną lokalizacją, zachowując oryginalny kierunek patrzenia (yaw/pitch). */
    private static void przeniesXYZ(Location cel, Location znaleziona) {
        cel.setX(znaleziona.getX());
        cel.setY(znaleziona.getY());
        cel.setZ(znaleziona.getZ());
    }

    /** Pierwszy solidny blok prosto pod `loc`, maks. `maxGlebokosc` bloków w dół - albo null, jeśli nic nie ma. */
    private static Location szukajGruntuWDol(Location loc, int maxGlebokosc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int startY = loc.getBlockY();

        for (int dy = 1; dy <= maxGlebokosc; dy++) {
            int y = startY - dy;
            if (y < world.getMinHeight()) break;
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }

    /** Najbliższy (w linii prostej) solidny blok z dwoma wolnymi kratkami nad sobą, w promieniu `promien` we wszystkich kierunkach - albo null. */
    private static Location szukajNajblizszegoGruntu(Location loc, int promien) {
        World world = loc.getWorld();
        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();

        Location najlepsza = null;
        int najlepszyDystansKw = Integer.MAX_VALUE;

        for (int dx = -promien; dx <= promien; dx++) {
            for (int dy = -promien; dy <= promien; dy++) {
                for (int dz = -promien; dz <= promien; dz++) {
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (y < world.getMinHeight() || y + 2 >= world.getMaxHeight()) continue;
                    if (!world.getBlockAt(x, y, z).getType().isSolid()) continue;
                    if (!world.getBlockAt(x, y + 1, z).getType().isAir()) continue;
                    if (!world.getBlockAt(x, y + 2, z).getType().isAir()) continue;

                    int dystansKw = dx * dx + dy * dy + dz * dz;
                    if (dystansKw < najlepszyDystansKw) {
                        najlepszyDystansKw = dystansKw;
                        najlepsza = new Location(world, x + 0.5, y + 1, z + 0.5);
                    }
                }
            }
        }
        return najlepsza;
    }
}
