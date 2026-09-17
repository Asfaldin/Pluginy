package elo.mainplugins.shop.model;

/** Ceny dynamiczne skupu (shop.yml dynamic-prices). announceEvents = ogłoszenie eventu na czacie. */
public record DynamicSettings(boolean enabled, int cycleMinutes, double minMultiplier, double maxMultiplier,
                              int resetDays, double maxSellShare, boolean announceEvents) {

    public static DynamicSettings defaults() {
        return new DynamicSettings(true, 60, 0.5, 1.5, 14, 0.9, true);
    }
}
