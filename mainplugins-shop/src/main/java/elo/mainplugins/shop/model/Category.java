package elo.mainplugins.shop.model;

import java.util.List;

/** Kategoria z pliku categories/<id>.yml. rotation = null, gdy kategoria nie rotuje. */
public record Category(String id, String name, String iconMaterial, String iconCustom, List<ShopItem> items, Rotation rotation) {}
