package elo.mainplugins.redstone.block;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Pozycja prawdziwego bloku (świat + całkowite x/y/z) - klucz w {@link DeviceStore}.
 * Zastępuje dawny Anchor (blok + ściana) z czasów fake-blocków: urządzenia to teraz
 * postawione bloki, nie encje przyczepione do boku czegoś.
 */
public record BlockKey(String world, int x, int y, int z) {

    public static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockKey of(Location loc) {
        return new BlockKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /** null, jeśli świat nie jest wczytany. NIE wymusza wczytania chunka samą tą metodą. */
    public Block toBlock() {
        World w = Bukkit.getWorld(world);
        return w != null ? w.getBlockAt(x, y, z) : null;
    }

    public boolean chunkZaladowany() {
        World w = Bukkit.getWorld(world);
        return w != null && w.isChunkLoaded(x >> 4, z >> 4);
    }

    public BlockKey przesun(int dx, int dy, int dz) {
        return new BlockKey(world, x + dx, y + dy, z + dz);
    }
}
