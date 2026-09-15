package elo.mainplugins.shop.model;

/** Ceny dynamiczne skupu (shop.yml dynamic-prices). */
public record DynamicSettings(boolean enabled, int cycleMinutes, double minMultiplier, double maxMultiplier,
                              int resetDays, double maxSellShare) {

    public static DynamicSettings defaults() {
        return new DynamicSettings(true, 60, 0.5, 1.5, 14, 0.9);
    }
}
