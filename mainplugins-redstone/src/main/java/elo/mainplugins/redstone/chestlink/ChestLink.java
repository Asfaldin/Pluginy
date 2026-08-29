package elo.mainplugins.redstone.chestlink;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataType;

/**
 * Łącze "skrzynka źródłowa -> skrzynka docelowa" trzymane wprost w PDC TileState
 * skrzynki źródłowej - żaden osobny plik/rejestr, persystuje naturalnie razem z
 * blokiem (jak wszystko inne w tym module co da się oprzeć na prawdziwym NBT).
 */
public final class ChestLink {

    private ChestLink() {}

    public static boolean link(Block source, Block target) {
        if (!(source.getState() instanceof TileState state)) return false;
        state.getPersistentDataContainer().set(ChestLinkKeys.LINK_TARGET, PersistentDataType.STRING, encode(target.getLocation()));
        state.update();
        return true;
    }

    public static Block target(Block source) {
        if (!(source.getState() instanceof TileState state)) return null;
        String raw = state.getPersistentDataContainer().get(ChestLinkKeys.LINK_TARGET, PersistentDataType.STRING);
        if (raw == null) return null;
        Location loc = decode(raw);
        return loc != null ? loc.getBlock() : null;
    }

    private static String encode(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private static Location decode(String raw) {
        String[] czesci = raw.split(";");
        if (czesci.length != 4) return null;
        World world = Bukkit.getWorld(czesci[0]);
        if (world == null) return null;
        try {
            return new Location(world, Integer.parseInt(czesci[1]), Integer.parseInt(czesci[2]), Integer.parseInt(czesci[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
