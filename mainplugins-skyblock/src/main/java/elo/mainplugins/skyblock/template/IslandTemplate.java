package elo.mainplugins.skyblock.template;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Wzory wyspy startowej jako wbudowane struktury Minecrafta (pliki .nbt, ten sam format co blok
 * struktury) - bez WorldEdita. Każdy wzór to para plików islands/<id>.nbt (bloki) + islands/<id>.yml
 * (origin). Wzór "default" jest przy pierwszym starcie kopiowany z jara, jeśli jar go zawiera.
 */
public final class IslandTemplate {

    public static final String DOMYSLNY = "default";
    /** Gotowe wzory w jarze - kopiowane do islands/ przy pierwszym starcie, jeśli ich tam nie ma. */
    private static final String[] W_JARZE = {"default", "farmerska", "pustynna", "zimowa"};

    private final Plugin plugin;
    private final File folder;

    public IslandTemplate(Plugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "islands");
        for (String id : W_JARZE) {
            if (!nbt(id).exists() && plugin.getResource("islands/" + id + ".nbt") != null
                    && plugin.getResource("islands/" + id + ".yml") != null) {
                plugin.saveResource("islands/" + id + ".nbt", false);
                plugin.saveResource("islands/" + id + ".yml", false);
            }
        }
    }

    /** Id wzoru: małe litery, cyfry, "-" i "_" (bezpieczna nazwa pliku). */
    public static String czysteId(String id) {
        String s = id == null ? "" : id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return s.isEmpty() ? DOMYSLNY : s;
    }

    private File nbt(String id) {
        return new File(folder, czysteId(id) + ".nbt");
    }

    private File meta(String id) {
        return new File(folder, czysteId(id) + ".yml");
    }

    public boolean exists(String id) {
        return nbt(id).exists() && meta(id).exists();
    }

    /** Wszystkie zapisane wzory (id), "default" pierwszy. */
    public List<String> zapisane() {
        List<String> out = new ArrayList<>();
        File[] pliki = folder.listFiles((d, n) -> n.endsWith(".nbt"));
        if (pliki == null) return out;
        for (File f : pliki) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            if (exists(id)) out.add(id);
        }
        out.sort((a, b) -> a.equals(DOMYSLNY) ? -1 : b.equals(DOMYSLNY) ? 1 : a.compareTo(b));
        return out;
    }

    /**
     * Wkleja wzór tak, żeby jego origin wypadł na (x, y, z). Wątek główny serwera.
     * Jeśli podano zawartość skrzyni, pierwsza skrzynia we wzorze dostaje dokładnie te przedmioty.
     */
    public void paste(String id, World world, int x, int y, int z, List<ItemStack> skrzynia) throws IOException {
        Structure structure = Bukkit.getStructureManager().loadStructure(nbt(id));
        YamlConfiguration meta = YamlConfiguration.loadConfiguration(meta(id));
        TemplateMath.Pos offset = new TemplateMath.Pos(
                meta.getInt("origin-offset.x"), meta.getInt("origin-offset.y"), meta.getInt("origin-offset.z"));
        TemplateMath.Pos corner = TemplateMath.pasteCorner(new TemplateMath.Pos(x, y, z), offset);
        structure.place(new Location(world, corner.x(), corner.y(), corner.z()), false,
                StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
        if (skrzynia == null || skrzynia.isEmpty()) return;
        BlockVector size = structure.getSize();
        for (int by = 0; by < size.getBlockY(); by++)
            for (int bx = 0; bx < size.getBlockX(); bx++)
                for (int bz = 0; bz < size.getBlockZ(); bz++) {
                    if (world.getBlockAt(corner.x() + bx, corner.y() + by, corner.z() + bz).getState() instanceof Chest chest) {
                        chest.getBlockInventory().clear();
                        for (ItemStack item : skrzynia) chest.getBlockInventory().addItem(item.clone());
                        return;
                    }
                }
    }

    /** Zapisuje obszar między narożnikami (włącznie) jako wzór o danym id; origin = gdzie ląduje środek nowej wyspy. */
    public TemplateMath.Pos save(String id, Location corner1, Location corner2, Location origin) throws IOException {
        TemplateMath.Pos a = pos(corner1);
        TemplateMath.Pos b = pos(corner2);
        TemplateMath.Pos min = TemplateMath.min(a, b);
        TemplateMath.Pos size = TemplateMath.size(a, b);
        TemplateMath.Pos offset = TemplateMath.offset(pos(origin), min);

        Structure structure = Bukkit.getStructureManager().createStructure();
        structure.fill(new Location(corner1.getWorld(), min.x(), min.y(), min.z()),
                new BlockVector(size.x(), size.y(), size.z()), false);
        folder.mkdirs();
        Bukkit.getStructureManager().saveStructure(nbt(id), structure);

        YamlConfiguration meta = new YamlConfiguration();
        meta.set("origin-offset.x", offset.x());
        meta.set("origin-offset.y", offset.y());
        meta.set("origin-offset.z", offset.z());
        meta.save(meta(id));
        plugin.getLogger().info("Island template '" + czysteId(id) + "' saved: " + size.x() + "x" + size.y() + "x" + size.z() + " blocks.");
        return size;
    }

    private static TemplateMath.Pos pos(Location l) {
        return new TemplateMath.Pos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }
}
