package elo.mainplugins.shop.model;

import java.util.List;

/**
 * Kategoria z pliku categories/<id>.yml. rotation = null, gdy kategoria nie rotuje.
 * layout = własny układ strony tej kategorii; null = wspólny układ z shop.yml (menus.category-page).
 */
public record Category(String id, String name, String iconMaterial, String iconCustom, List<ShopItem> items, Rotation rotation,
                       MenuScreen layout) {}
