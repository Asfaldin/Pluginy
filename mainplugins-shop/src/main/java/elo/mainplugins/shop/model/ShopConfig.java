package elo.mainplugins.shop.model;

import java.util.Map;

/** Cały sklep: ustawienia + kategorie (w kolejności z shop.yml, potem reszta). */
public record ShopConfig(ShopSettings settings, Map<String, Category> categories) {}
