package elo.mainplugins.shop.model;

import java.util.List;

/** Kategoria rotująca: co everyDays losuje show pozycji z pool. announce = ogłoszenie na czacie przy zmianie oferty. */
public record Rotation(boolean enabled, int show, int everyDays, boolean announce, List<ShopItem> pool) {}
