package elo.mainplugins.market.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wszystko z market.yml. Przycisk, którego nie ma w mapie, po prostu się nie pokazuje. */
public record MarketSettings(int defaultLimit, long minPrice, long maxPrice, int expireDays,
                             boolean mailbox, int taxPercent, String title, String background,
                             Map<String, ButtonDef> buttons) {

    /** Miejsca na oferty - blok 7x3 w środku okna 54. */
    public static final List<Integer> OFFER_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34);

    public static final List<String> BUTTON_IDS = List.of("prev", "next", "mine", "search", "close", "sort", "mailbox");

    public static Map<String, ButtonDef> defaultButtons() {
        Map<String, ButtonDef> b = new LinkedHashMap<>();
        b.put("prev", new ButtonDef(45, "SPECTRAL_ARROW"));
        b.put("next", new ButtonDef(53, "SPECTRAL_ARROW"));
        b.put("mine", new ButtonDef(47, "HOPPER"));
        b.put("search", new ButtonDef(46, "OAK_SIGN"));
        b.put("close", new ButtonDef(49, "BARRIER"));
        b.put("sort", new ButtonDef(51, "COMPARATOR"));
        b.put("mailbox", new ButtonDef(52, "CHEST"));
        return b;
    }

    public static MarketSettings defaults() {
        return new MarketSettings(10, 1, 10_000_000L, 7, true, 0, "", "GRAY_STAINED_GLASS_PANE", Map.copyOf(defaultButtons()));
    }
}
