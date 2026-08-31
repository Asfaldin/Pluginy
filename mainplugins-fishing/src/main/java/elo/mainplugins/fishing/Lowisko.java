package elo.mainplugins.fishing;

import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jedna nazwana, prostopadłościenna strefa łowiska - dwa rogi ("od bloku do bloku"), plus
 * WŁASNA lista dozwolonych gatunków z wagami (custom-id z ryby.yml -> waga). Ten sam
 * mechanizm zaznaczania co Obszar w mainplugins-spawn (patrz LowiskoManager, świadomie
 * zduplikowany, NIE reużywany stamtąd - mainplugins-spawn idzie na sprzedaż, więc nie
 * powinien nic wiedzieć o rybach, patrz javadoc LowiskoManager) - tu jednak DOCHODZI lista
 * gatunków, bo w odróżnieniu od zwykłego obszaru ochronnego łowisko ma też decydować CO się
 * w nim łowi, nie tylko GDZIE wolno łowić.
 *
 * Puste/brakujące gatunki = "domyślne" (patrz LowiskoManager.gatunkiDlaLowiska) - pełna pula
 * wszystkich zarejestrowanych gatunków w ryby.yml, dokładnie tak jak działało łowienie przed
 * wprowadzeniem stref (user 2026-08-31c: nowy admin może zapomnieć skonfigurować listę,
 * niczego to nie psuje zamiast zablokować łowienie w tym miejscu).
 */
class Lowisko {

    Location rog1;
    Location rog2;
    // LinkedHashMap - kolejność wpisów w pliku zachowana przy zapisie (czytelniejsze niż
    // przypadkowa kolejność HashMap przy ręcznej edycji lowiska.yml).
    final Map<String, Integer> gatunki = new LinkedHashMap<>();

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
