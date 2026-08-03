package elo.mainplugins.core.util;

import org.bukkit.NamespacedKey;

/**
 * Klucze PersistentDataContainer współdzielone między modułami, które same
 * ze sobą nie sąsiadują zależnościami (np. mainplugins-shop i mainplugins-spawners).
 * Namespace jest na sztywno "mainplugins" (nie nazwą konkretnego pluginu), żeby
 * odczyt działał niezależnie od tego, który moduł zapisał tag.
 */
public final class CustomItemKeys {

    private CustomItemKeys() {}

    /**
     * Wartość: identyfikator typu customowego itemu ze sklepu (np. "PIGLIN" dla spawnera,
     * "NISZCZYCIEL" dla specjalnego kilofa) - ustawiana przez ShopManager przy zakupie,
     * odczytywana przez moduł, który nadaje itemowi realne zachowanie (spawners, tools...).
     */
    public static final NamespacedKey CUSTOM_ITEM_ID = new NamespacedKey("mainplugins", "custom-item-id");

    /** Wartość: nazwa enuma SpawnerType - ustawiana na encji przy spawnie, żeby rozpoznać ją przy śmierci (np. custom dropy). */
    public static final NamespacedKey SPAWNER_MOB_SOURCE = new NamespacedKey("mainplugins", "spawner-mob-source");
}