package elo.mainplugins.spawn;

import org.bukkit.Location;

/**
 * Jeden nazwany, prostopadłościenny obszar chroniony - dwa rogi ("od bloku do bloku"),
 * z osobnymi przełącznikami spawnu mobów pasywnych/agresywnych. Spawn to po prostu
 * pierwszy taki obszar (nazwa "spawn" nie ma żadnego specjalnego znaczenia w kodzie) -
 * ten sam mechanizm ma docelowo obsługiwać też tereny przyszłych warpów (patrz ObszarManager).
 */
class Obszar {

    Location rog1;
    Location rog2;
    boolean mobyPasywneDozwolone = true;
    boolean mobyAgresywneDozwolone = false;
    // Patrz ObszarManager#jestLowiskiem / ObszarService - łowienie specjalnych ryb
    // (mainplugins-fishing) działa WYŁĄCZNIE wewnątrz obszarów z tą flagą włączoną.
    boolean rybyDozwolone = false;

    boolean maObaRogi() {
        return rog1 != null && rog2 != null;
    }

    boolean zawiera(Location loc) {
        if (!maObaRogi() || loc.getWorld() == null || !loc.getWorld().equals(rog1.getWorld())) return false;

        int minX = Math.min(rog1.getBlockX(), rog2.getBlockX());
        int maxX = Math.max(rog1.getBlockX(), rog2.getBlockX());
        int minY = Math.min(rog1.getBlockY(), rog2.getBlockY());
        int maxY = Math.max(rog1.getBlockY(), rog2.getBlockY());
        int minZ = Math.min(rog1.getBlockZ(), rog2.getBlockZ());
        int maxZ = Math.max(rog1.getBlockZ(), rog2.getBlockZ());

        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    String opisRozmiaru() {
        if (!maObaRogi()) return "brak (zaznacz różdżką)";
        int dx = Math.abs(rog1.getBlockX() - rog2.getBlockX()) + 1;
        int dy = Math.abs(rog1.getBlockY() - rog2.getBlockY()) + 1;
        int dz = Math.abs(rog1.getBlockZ() - rog2.getBlockZ()) + 1;
        return dx + "x" + dy + "x" + dz;
    }
}
