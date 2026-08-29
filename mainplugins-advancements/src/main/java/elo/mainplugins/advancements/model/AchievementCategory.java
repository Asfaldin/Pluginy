package elo.mainplugins.advancements.model;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

/**
 * Zakładka w panelu osiągnięć (np. "Eksploracja", "Walka", "Ekonomia").
 * Grupuje {@link AchievementDef} w GUI, a przy włączonym datapacku staje się
 * osobnym drzewkiem (zakładką) w natywnym ekranie ESC → Postępy.
 * {@code kolejnosc} ustala pozycję zakładki (rosnąco).
 *
 * @param id         stabilny klucz z osiagniecia.yml (sekcja pod {@code kategorie:})
 * @param nazwa      wyświetlana nazwa (już zdeserializowany Component, bez kursywy)
 * @param ikona      materiał ikony zakładki
 * @param kolejnosc  pozycja zakładki wśród pozostałych (mniejsza = wcześniej)
 * @param tloZakladki ścieżka tekstury tła drzewka w natywnym ekranie (tylko datapack),
 *                    np. {@code minecraft:textures/block/stone.png}; null = domyślne
 */
public record AchievementCategory(String id, Component nazwa, Material ikona, int kolejnosc, String tloZakladki) {}
