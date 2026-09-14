package elo.mainplugins.market;

import java.util.Collection;

/** Proste zasady Targu, bez serwera - łatwe do testów. */
public final class MarketRules {

    private static final String LIMIT_PREFIX = "mainplugins.market.limit.";
    private static final long DAY_MS = 86_400_000L;

    private MarketRules() {}

    /** Ile dostaje sprzedający: cena minus podatek, w dół. Podatek przycinany do 0-100. */
    public static long payout(long price, int taxPercent) {
        int tax = Math.max(0, Math.min(100, taxPercent));
        return price * (100 - tax) / 100;
    }

    /** Czy oferta wygasła. expireDays <= 0 = nigdy. */
    public static boolean expired(long listedAt, long now, int expireDays) {
        return expireDays > 0 && now - listedAt >= expireDays * DAY_MS;
    }

    /** Limit ofert: najwyższe z domyślnego i uprawnień mainplugins.market.limit.<liczba>. */
    public static int limitFor(int defaultLimit, Collection<String> permissions) {
        int best = defaultLimit;
        for (String p : permissions) {
            if (!p.startsWith(LIMIT_PREFIX)) continue;
            try {
                best = Math.max(best, Integer.parseInt(p.substring(LIMIT_PREFIX.length())));
            } catch (NumberFormatException ignored) {
                // np. ".limit.x" - pomijamy
            }
        }
        return best;
    }
}
