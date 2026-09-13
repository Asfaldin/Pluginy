package elo.mainplugins.crates;

import elo.mainplugins.core.api.LangService;
import elo.mainplugins.crates.model.CrateConfig;
import elo.mainplugins.crates.model.CrateDef;
import elo.mainplugins.crates.model.PlacedCrate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Skrzynki postawione w świecie (np. na spawnie): zapis w placed.yml, napis nad blokiem
 * (TextDisplay, nie zapisywany do świata - odtwarzany przy starcie i wczytaniu chunka)
 * i ochrona bloku przed zniszczeniem, wybuchem i tłokami.
 */
final class PlacedCrates implements Listener {

    private final Plugin plugin;
    private final LangService lang;
    private final Supplier<CrateConfig> config;
    private final List<PlacedCrate> placed = new ArrayList<>();
    private final Map<PlacedCrate, TextDisplay> holograms = new HashMap<>();

    PlacedCrates(Plugin plugin, LangService lang, Supplier<CrateConfig> config) {
        this.plugin = plugin;
        this.lang = lang;
        this.config = config;
    }

    private File file() {
        return new File(plugin.getDataFolder(), "placed.yml");
    }

    /** Czyta placed.yml od nowa i odświeża napisy (start pluginu i /@crate reload). */
    void load() {
        removeHolograms();
        placed.clear();
        placed.addAll(PlacedCrateStore.parse(YamlConfiguration.loadConfiguration(file()).getList("placed"),
                plugin.getLogger()::warning));
        for (PlacedCrate p : placed) {
            if (!config.get().crates().containsKey(p.crate())) {
                plugin.getLogger().warning("placed.yml: crate '" + p.crate() + "' at " + p.world() + " " + p.x() + " "
                        + p.y() + " " + p.z() + " is not in crates.yml - the block does nothing until you add it back.");
            }
            spawnHologram(p);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Crates placed in the world. Use /@crate place <crate> and /@crate remove in game."));
        y.set("placed", PlacedCrateStore.toYaml(placed));
        try {
            y.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save placed.yml: " + e.getMessage());
        }
    }

    PlacedCrate at(Block b) {
        for (PlacedCrate p : placed) {
            if (p.at(b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) return p;
        }
        return null;
    }

    List<PlacedCrate> all() {
        return List.copyOf(placed);
    }

    /** false = na tym bloku już stoi skrzynka. */
    boolean place(Block b, String crateId) {
        if (at(b) != null) return false;
        PlacedCrate p = new PlacedCrate(crateId, b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        placed.add(p);
        save();
        spawnHologram(p);
        return true;
    }

    /** Usuwa skrzynkę z bloku; null = tu nie było skrzynki. */
    PlacedCrate remove(Block b) {
        PlacedCrate p = at(b);
        if (p == null) return null;
        placed.remove(p);
        TextDisplay td = holograms.remove(p);
        if (td != null) td.remove();
        save();
        return p;
    }

    void removeHolograms() {
        holograms.values().forEach(TextDisplay::remove);
        holograms.clear();
    }

    private void spawnHologram(PlacedCrate p) {
        TextDisplay old = holograms.remove(p);
        if (old != null) old.remove();
        CrateDef crate = config.get().crates().get(p.crate());
        World world = Bukkit.getWorld(p.world());
        if (crate == null || world == null || !world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) return;

        List<Component> lines = new ArrayList<>();
        if (crate.hologram().isEmpty()) {
            lines.add(CrateItems.text(crate.name()));
            lines.add(lang.msg(plugin, "placed.hint"));
        } else {
            for (String l : crate.hologram()) lines.add(CrateItems.text(l));
        }
        Location loc = new Location(world, p.x() + 0.5, p.y() + 1 + config.get().hologramHeight(), p.z() + 0.5);
        TextDisplay td = world.spawn(loc, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.text(Component.join(JoinConfiguration.newlines(), lines));
        });
        holograms.put(p, td);
    }

    // Napisy nie są zapisywane do świata - po wczytaniu chunka stawiamy je od nowa.
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        String world = event.getWorld().getName();
        for (PlacedCrate p : placed) {
            if (!p.world().equals(world) || p.x() >> 4 != event.getChunk().getX() || p.z() >> 4 != event.getChunk().getZ()) continue;
            TextDisplay td = holograms.get(p);
            if (td == null || !td.isValid()) spawnHologram(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent event) {
        if (at(event.getBlock()) == null) return;
        event.setCancelled(true);
        lang.send(event.getPlayer(), plugin, "placed.protected");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> at(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> at(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> at(b) != null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(b -> at(b) != null)) event.setCancelled(true);
    }
}
