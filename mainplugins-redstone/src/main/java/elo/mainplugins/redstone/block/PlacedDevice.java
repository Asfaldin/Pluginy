package elo.mainplugins.redstone.block;

import elo.mainplugins.redstone.item.RedstoneItemKind;
import org.bukkit.Material;

/**
 * Jedno postawione urządzenie (Sadzarka / Stacja Drona / Stacja Zbierania / Stacja
 * Zbiorcza) - prawdziwy blok w świecie, którego tożsamość i stan trzyma {@link DeviceStore}
 * (bloki nie mają PDC, więc potrzebny osobny plik: redstone-urzadzenia.yml).
 *
 * Mutowalne pola dotyczą TYLKO Sadzarki (nasiona + własne pole). Reszta urządzeń używa
 * jedynie {@code key/kind/itemId}.
 */
public final class PlacedDevice {

    public final BlockKey key;
    public final RedstoneItemKind kind;
    public final String itemId;

    /** Sadzarka: pozycja jej WŁASNEGO pola Farmland (sąsiad w chwili postawienia). */
    public BlockKey farmland;
    /** Sadzarka: materiał nasiona w slocie (null = pusto). */
    public Material seed;
    /** Sadzarka: ile nasion zostało. */
    public int seedCount;

    public PlacedDevice(BlockKey key, RedstoneItemKind kind, String itemId) {
        this.key = key;
        this.kind = kind;
        this.itemId = itemId;
    }
}
