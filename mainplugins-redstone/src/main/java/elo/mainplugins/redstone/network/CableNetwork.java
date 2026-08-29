package elo.mainplugins.redstone.network;

import elo.mainplugins.redstone.block.BlockKey;
import elo.mainplugins.redstone.block.DeviceStore;
import elo.mainplugins.redstone.block.PlacedDevice;
import elo.mainplugins.redstone.item.RedstoneItemKind;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * "Czy istnieje nieprzerwany łańcuch Kabla Przesyłowego" od stacji do celu - zwykła
 * osiągalność (tak/nie), bez zaniku siły. Kabel to teraz prawdziwy cienki blok na
 * wierzchu innych bloków; łączy się jak wanilijski pył redstone: krok o 1 w poziomie,
 * z tolerancją ±1 w pionie (schodek). Urządzenie/skrzynka "dotyka" kabla, gdy stoi
 * ortogonalnie obok jego bloku (w tym kabel na wierzchu urządzenia).
 */
public final class CableNetwork {

    private static final int[][] POZIOME = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private CableNetwork() {}

    public static PlacedDevice findReachablePlanter(PlacedDevice station, DeviceStore store) {
        for (BlockKey cable : osiagalneKable(station.key, store)) {
            for (int[] d : POZIOME) {
                for (int dy = -1; dy <= 1; dy++) {
                    PlacedDevice dev = store.getDevice(cable.przesun(d[0], dy, d[1]));
                    if (dev != null && dev.kind == RedstoneItemKind.PLANTER) return dev;
                }
            }
            // kabel wprost NAD urządzeniem
            PlacedDevice pod = store.getDevice(cable.przesun(0, -1, 0));
            if (pod != null && pod.kind == RedstoneItemKind.PLANTER) return pod;
        }
        return null;
    }

    public static Block findReachableChest(PlacedDevice harvester, DeviceStore store) {
        for (BlockKey cable : osiagalneKable(harvester.key, store)) {
            Block b = cable.toBlock();
            if (b == null) continue;
            for (int[] d : POZIOME) {
                Block sasiad = b.getRelative(d[0], 0, d[1]);
                if (jestSkrzynka(sasiad)) return sasiad;
            }
            if (jestSkrzynka(b.getRelative(0, -1, 0))) return b.getRelative(0, -1, 0);
        }
        return null;
    }

    /** BFS po połączonych kablach, zaczynając od kabli dotykających bloku urządzenia. */
    private static Set<BlockKey> osiagalneKable(BlockKey urzadzenie, DeviceStore store) {
        Set<BlockKey> odwiedzone = new HashSet<>();
        Deque<BlockKey> kolejka = new ArrayDeque<>();

        for (BlockKey start : kableDotykajace(urzadzenie, store)) {
            if (odwiedzone.add(start)) kolejka.add(start);
        }

        while (!kolejka.isEmpty()) {
            BlockKey obecny = kolejka.poll();
            for (int[] d : POZIOME) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockKey sasiad = obecny.przesun(d[0], dy, d[1]);
                    if (store.isCable(sasiad) && odwiedzone.add(sasiad)) {
                        kolejka.add(sasiad);
                    }
                }
            }
        }
        return odwiedzone;
    }

    private static Set<BlockKey> kableDotykajace(BlockKey blok, DeviceStore store) {
        Set<BlockKey> wynik = new HashSet<>();
        int[][] szesc = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : szesc) {
            BlockKey k = blok.przesun(d[0], d[1], d[2]);
            if (store.isCable(k)) wynik.add(k);
        }
        return wynik;
    }

    private static boolean jestSkrzynka(Block b) {
        return b.getType() == Material.CHEST || b.getType() == Material.TRAPPED_CHEST;
    }
}
