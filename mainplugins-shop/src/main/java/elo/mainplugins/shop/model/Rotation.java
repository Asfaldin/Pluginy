package elo.mainplugins.shop.model;

import java.util.List;

/** Kategoria rotująca: co everyDays losuje show pozycji z pool. */
public record Rotation(boolean enabled, int show, int everyDays, List<ShopItem> pool) {}
