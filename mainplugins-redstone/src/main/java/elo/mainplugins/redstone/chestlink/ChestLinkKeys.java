package elo.mainplugins.redstone.chestlink;

import org.bukkit.NamespacedKey;

/** Klucz PDC trzymany na TileState prawdziwej Skrzynki (nie na fake-blocku - skrzynka sama w sobie już persystuje). */
public final class ChestLinkKeys {

    private ChestLinkKeys() {}

    /** Wartość: "world;x;y;z" skrzynki docelowej, ustawiane przez Łącznik Skrzynek. */
    public static final NamespacedKey LINK_TARGET = new NamespacedKey("mainplugins-redstone", "chestlink-target");
}
