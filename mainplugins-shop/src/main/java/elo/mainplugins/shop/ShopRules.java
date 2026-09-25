package elo.mainplugins.shop;

import elo.mainplugins.shop.model.Category;
import elo.mainplugins.shop.model.Rounding;
import elo.mainplugins.shop.model.ShopConfig;
import elo.mainplugins.shop.model.ShopItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Liczenie cen sklepu, bez serwera. Liczone na BigDecimal - bez błędów typu 0.16*64 = 10.2400001. */
public final class ShopRules {

    /** Wynik skupu: ile pełnych paczek, ile sztuk zabrać, ile pieniędzy. */
    public record SellResult(int lots, int pieces, double money) {}

    private ShopRules() {}

    private static int scale(Rounding r) {
        return r == Rounding.WHOLE ? 0 : 2;
    }

    private static BigDecimal minimum(Rounding r) {
        return r == Rounding.WHOLE ? BigDecimal.ONE : new BigDecimal("0.01");
    }

    /** Cena za dowolną ilość sztuk: proporcjonalnie z ceny paczki, w górę; minimum 1 / 0.01 (0 gdy przedmiot za darmo). */
    public static double buyPrice(ShopItem item, int pieces, Rounding rounding) {
        return buyPrice(item, pieces, rounding, 1.0);
    }

    /** Jak wyżej, z mnożnikiem ceny gracza (promocja, rabat rangi - patrz buyFactor). */
    public static double buyPrice(ShopItem item, int pieces, Rounding rounding, double factor) {
        if (item.buy() == null) return -1;
        if (item.buy() == 0) return 0;
        // Mnożnik przycięty do 6 miejsc - 0.8 * 0.9 to w double 0.7200000000000001, a to podbiłoby cenę o grosz.
        BigDecimal f = BigDecimal.valueOf(factor).setScale(6, RoundingMode.HALF_UP);
        BigDecimal price = BigDecimal.valueOf(item.buy()).multiply(BigDecimal.valueOf(pieces)).multiply(f)
                .divide(BigDecimal.valueOf(item.amount()), scale(rounding), RoundingMode.CEILING);
        return price.max(minimum(rounding)).doubleValue();
    }

    /** Promocja: procent zniżki na kupno i do kiedy (0 = bez końca). */
    public record Sale(double percent, long until) {}

    /** Klucze promocji: na przedmiot, na kategorię, na cały sklep. */
    public static String saleKeyItem(String itemKey) {
        return "item:" + itemKey;
    }

    public static String saleKeyCategory(String categoryId) {
        return "category:" + categoryId;
    }

    public static final String SALE_ALL = "all";

    /**
     * Zniżka dla przedmiotu w kategorii: najpierw promocja na sam przedmiot, potem na kategorię,
     * potem na cały sklep (węższa wygrywa). Wygasłe się nie liczą. 0 = brak promocji.
     */
    public static double salePercent(Map<String, Sale> sales, String itemKey, String categoryId, long now) {
        for (String k : new String[]{saleKeyItem(itemKey), categoryId == null ? null : saleKeyCategory(categoryId), SALE_ALL}) {
            if (k == null) continue;
            Sale s = sales.get(k);
            if (s != null && (s.until() == 0 || s.until() > now)) return s.percent();
        }
        return 0;
    }

    /** Mnożnik ceny kupna: promocja i rabat rangi sumują się (-20% i -10% = płaci 72%). */
    public static double buyFactor(double salePercent, double rankPercent) {
        return (1 - salePercent / 100.0) * (1 - rankPercent / 100.0);
    }

    /** Mnożnik skupu z premią rangi (1 = +1%). Sufit skupu (max-sell-share) i tak obowiązuje. */
    public static double sellFactor(double bonusPercent) {
        return 1 + bonusPercent / 100.0;
    }

    /** Największa premia spośród rang gracza (np. VIP 2%, SVIP 5% -> 5%). */
    public static double bestBonus(java.util.Collection<Double> bonuses) {
        double best = 0;
        for (double b : bonuses) best = Math.max(best, b);
        return best;
    }

    /**
     * Cena skupu jednej paczki po mnożniku - 1:1 jak dawne DynamicPriceManager.policzCeneSkupu:
     * zaokrąglenie do najbliższej (połówki w górę), sufit = cena kupna tej paczki * maxSellShare (w dół), minimum 1 / 0.01.
     */
    public static double sellPerLot(ShopItem item, double multiplier, double maxSellShare, Rounding rounding) {
        int scale = scale(rounding);
        BigDecimal price = BigDecimal.valueOf(item.sell()).multiply(BigDecimal.valueOf(multiplier)).setScale(scale, RoundingMode.HALF_UP);
        if (item.buy() != null) {
            BigDecimal buyPerSellLot = BigDecimal.valueOf(item.buy()).multiply(BigDecimal.valueOf(item.sellAmount()))
                    .divide(BigDecimal.valueOf(item.amount()), scale, RoundingMode.HALF_UP);
            if (buyPerSellLot.signum() > 0) {
                BigDecimal cap = buyPerSellLot.multiply(BigDecimal.valueOf(maxSellShare)).setScale(scale, RoundingMode.FLOOR);
                if (price.compareTo(cap) > 0) price = cap;
            }
        }
        return price.max(minimum(rounding)).doubleValue();
    }

    /** Skup z posiadanych sztuk - tylko pełne paczki, reszta zostaje u gracza. */
    public static SellResult sell(ShopItem item, int ownedPieces, double multiplier, double maxSellShare, Rounding rounding) {
        int lots = ownedPieces / item.sellAmount();
        if (lots == 0) return new SellResult(0, 0, 0);
        double perLot = sellPerLot(item, multiplier, maxSellShare, rounding);
        double money = BigDecimal.valueOf(perLot).multiply(BigDecimal.valueOf(lots)).doubleValue();
        return new SellResult(lots, lots * item.sellAmount(), money);
    }

    /** Pierwsza sprzedawalna pozycja o danym kluczu (kategorie po kolei, w każdej: stałe + aktualnie rotujące). */
    public static ShopItem sellOffer(ShopConfig cfg, Map<String, List<ShopItem>> rotationActive, String key) {
        for (Category c : cfg.categories().values()) {
            for (ShopItem it : c.items()) if (it.sellable() && it.key().equals(key)) return it;
            for (ShopItem it : rotationActive.getOrDefault(c.id(), List.of())) if (it.sellable() && it.key().equals(key)) return it;
        }
        return null;
    }

    /** Ile sztuk da się kupić za pieniądze i na ile starczy miejsca (jak dawne kupMaksymalnaIlosc). */
    public static int maxBuyPieces(ShopItem item, double money, int freeSpacePieces, Rounding rounding) {
        return maxBuyPieces(item, money, freeSpacePieces, rounding, 1.0);
    }

    public static int maxBuyPieces(ShopItem item, double money, int freeSpacePieces, Rounding rounding, double factor) {
        if (item.buy() == null || freeSpacePieces <= 0) return 0;
        if (item.buy() == 0) return freeSpacePieces;
        long guess = (long) Math.floor(money * item.amount() / (item.buy() * Math.max(0.0001, factor)));
        int n = (int) Math.max(0, Math.min(guess, freeSpacePieces));
        while (n > 0 && buyPrice(item, n, rounding, factor) > money) n--;
        while (n < freeSpacePieces && buyPrice(item, n + 1, rounding, factor) <= money) n++;
        return n;
    }

    // =========================================================================
    //  KATEGORIA Z KOMENDY (/sklep bloki, /@shop open Kasia "Rudy i Minerały")
    // =========================================================================

    /**
     * Kategoria z tekstu gracza: id ("bloki") albo nazwa bez kolorów ("Rudy i Minerały"),
     * bez znaczenia wielkie litery i polskie znaki. names = id -> nazwa z kolorami &. Null = nie ma takiej.
     */
    public static String matchCategory(String raw, Map<String, String> names) {
        String want = plain(raw);
        if (want.isEmpty()) return null;
        for (String id : names.keySet()) if (plain(id).equals(want)) return id;
        for (Map.Entry<String, String> e : names.entrySet()) if (plain(e.getValue()).equals(want)) return e.getKey();
        return null;
    }

    /** "&e&lRudy i Minerały" -> "rudy i mineraly". */
    static String plain(String s) {
        String t = s.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").trim().toLowerCase(Locale.ROOT).replace('ł', 'l');
        return java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    // =========================================================================
    //  EVENTY: procenty i czas trwania
    // =========================================================================

    /**
     * "+50", "-20", "50", "50%" -> mnożnik (1.5, 0.8, 1.5). Null, gdy to nie liczba.
     * Admin podaje, o ile procent cena ma się zmienić, a nie surowy mnożnik.
     */
    public static Double percentToMultiplier(String raw) {
        String t = raw.trim().replace("%", "").replace(',', '.');
        if (t.startsWith("+")) t = t.substring(1);
        try {
            return 1.0 + Double.parseDouble(t) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Mnożnik -> procent zmiany (1.5 -> 50, 0.8 -> -20). */
    public static int multiplierToPercent(double multiplier) {
        return (int) Math.round((multiplier - 1.0) * 100);
    }

    /** "30m", "2h", "3d" (albo samo "2" = godziny) -> ms. Null, gdy zapis jest zły; 0 nie jest dozwolone. */
    public static Long parseDuration(String raw) {
        String t = raw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        char unit = t.charAt(t.length() - 1);
        long perUnit = switch (unit) {
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            default -> Character.isDigit(unit) ? 3_600_000L : -1L;
        };
        if (perUnit < 0) return null;
        String number = Character.isDigit(unit) ? t : t.substring(0, t.length() - 1);
        try {
            long n = Long.parseLong(number);
            return n > 0 ? n * perUnit : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ms -> "2d 3h", "1h 20m", "45m", "30s" - krótko, najwyżej dwie jednostki. */
    public static String formatDuration(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long d = total / 86_400L;
        long h = total % 86_400L / 3_600L;
        long m = total % 3_600L / 60L;
        long sec = total % 60L;
        if (d > 0) return h > 0 ? d + "d " + h + "h" : d + "d";
        if (h > 0) return m > 0 ? h + "h " + m + "m" : h + "h";
        if (m > 0) return m + "m";
        return sec + "s";
    }
}
