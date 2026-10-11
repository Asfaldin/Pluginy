package elo.mainplugins.coreminer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Szukanie żyły: BFS od pierwszego bloku po sąsiadach (6 albo 26 z przekątnymi), najbliższe najpierw.
 * Czysta logika bez Bukkita - świat dochodzi przez predykat "czy ten blok należy do żyły" (testowalne).
 */
public final class VeinFinder {

    private VeinFinder() {}

    /** Punkt w świecie (współrzędne bloku). */
    public record Pos(int x, int y, int z) {
        int odleglosc2(Pos o) {
            int dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private static final int[][] SCIANY = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] WSZYSTKIE;

    static {
        List<int[]> l = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    if (dx != 0 || dy != 0 || dz != 0) l.add(new int[]{dx, dy, dz});
        WSZYSTKIE = l.toArray(new int[0][]);
    }

    /**
     * Bloki żyły BEZ bloku startowego (ten kopie gracz zwyczajnie), najwyżej max sztuk,
     * nie dalej niż maxOdleglosc od startu. Kolejność: od najbliższych - przy limicie zostaje zwarta żyła.
     */
    public static List<Pos> znajdz(Pos start, int max, int maxOdleglosc, boolean poPrzekatnej, Predicate<Pos> nalezy) {
        List<Pos> out = new ArrayList<>();
        if (max <= 0) return out;
        int[][] kroki = poPrzekatnej ? WSZYSTKIE : SCIANY;
        int r2 = maxOdleglosc * maxOdleglosc;
        Set<Pos> odwiedzone = new HashSet<>();
        ArrayDeque<Pos> kolejka = new ArrayDeque<>();
        odwiedzone.add(start);
        kolejka.add(start);
        while (!kolejka.isEmpty() && out.size() < max) {
            Pos p = kolejka.poll();
            for (int[] k : kroki) {
                Pos n = new Pos(p.x + k[0], p.y + k[1], p.z + k[2]);
                if (!odwiedzone.add(n) || n.odleglosc2(start) > r2) continue;
                if (!nalezy.test(n)) continue;
                out.add(n);
                kolejka.add(n);
                if (out.size() >= max) break;
            }
        }
        return out;
    }
}
