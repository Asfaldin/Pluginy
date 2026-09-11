package elo.mainplugins.skyblock.template;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.util.Random;

/**
 * Szablon wyspy startowej jako wbudowana struktura Minecrafta (plik .nbt, ten sam format
 * co blok struktury) - zamiast schematu WorldEdit, więc Skyblock nie potrzebuje żadnego
 * dodatkowego pluginu. Pliki: islands/default.nbt (bloki) + islands/default.yml (origin).
 * Przy pierwszym starcie kopiowany jest gotowy szablon z jara, jeśli jar go zawiera.
 */
public final class IslandTemplate {

    private final Plugin plugin;
    private final File nbtFile;
    private final File metaFile;

    public IslandTemplate(Plugin plugin) {
        this.plugin = plugin;
        File folder = new File(plugin.getDataFolder(), "islands");
        this.nbtFile = new File(folder, "default.nbt");
        this.metaFile = new File(folder, "default.yml");
        if (!nbtFile.exists() && plugin.getResource("islands/default.nbt") != null
                && plugin.getResource("islands/default.yml") != null) {
            plugin.saveResource("islands/default.nbt", false);
            plugin.saveResource("islands/default.yml", false);
        }
    }

    public boolean exists() {
        return nbtFile.exists() && metaFile.exists();
    }

    /** Wkleja szablon tak, żeby jego origin wypadł na (x, y, z). Wątek główny serwera. */
    public void paste(World world, int x, int y, int z) throws IOException {
        Structure structure = Bukkit.getStructureManager().loadStructure(nbtFile);
        YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile);
        TemplateMath.Pos offset = new TemplateMath.Pos(
                meta.getInt("origin-offset.x"), meta.getInt("origin-offset.y"), meta.getInt("origin-offset.z"));
        TemplateMath.Pos corner = TemplateMath.pasteCorner(new TemplateMath.Pos(x, y, z), offset);
        structure.place(new Location(world, corner.x(), corner.y(), corner.z()), false,
                StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
    }

    /** Zapisuje obszar między narożnikami (włącznie); origin = gdzie ląduje środek nowej wyspy. */
    public TemplateMath.Pos save(Location corner1, Location corner2, Location origin) throws IOException {
        TemplateMath.Pos a = pos(corner1);
        TemplateMath.Pos b = pos(corner2);
        TemplateMath.Pos min = TemplateMath.min(a, b);
        TemplateMath.Pos size = TemplateMath.size(a, b);
        TemplateMath.Pos offset = TemplateMath.offset(pos(origin), min);

        Structure structure = Bukkit.getStructureManager().createStructure();
        structure.fill(new Location(corner1.getWorld(), min.x(), min.y(), min.z()),
                new BlockVector(size.x(), size.y(), size.z()), false);
        nbtFile.getParentFile().mkdirs();
        Bukkit.getStructureManager().saveStructure(nbtFile, structure);

        YamlConfiguration meta = new YamlConfiguration();
        meta.set("origin-offset.x", offset.x());
        meta.set("origin-offset.y", offset.y());
        meta.set("origin-offset.z", offset.z());
        meta.save(metaFile);
        plugin.getLogger().info("Island template saved: " + size.x() + "x" + size.y() + "x" + size.z() + " blocks.");
        return size;
    }

    private static TemplateMath.Pos pos(Location l) {
        return new TemplateMath.Pos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
