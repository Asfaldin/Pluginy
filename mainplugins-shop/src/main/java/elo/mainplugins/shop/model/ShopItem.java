package elo.mainplugins.shop.model;

import java.util.List;
import java.util.Locale;

/**
 * Pozycja sklepu. buy/sell = cena za porcję (amount / sellAmount sztuk); null = nie do kupienia / sprzedania.
 * material albo customId (przedmiot z katalogu core) - dokładnie jedno z nich.
 * dynamic = czy cena skupu ma się wahać; false to cena stała (np. dla rzeczy, które da się farmić
 * bez końca - te i tak osiadłyby na dnie, bo farma sprzedaje niezależnie od ceny).
 */
public record ShopItem(String material, String customId, Double buy, Double sell, int amount, int sellAmount,
                       String name, List<String> lore, String instrument, boolean dynamic) {

    /** "DIAMOND" albo "custom:spawner_zombie" - klucz cen dynamicznych i statystyk. */
    public String key() {
        return customId != null ? "custom:" + customId.toLowerCase(Locale.ROOT) : material;
    }

    public boolean buyable() {
        return buy != null;
    }

    public boolean sellable() {
        return sell != null;
    }
}
