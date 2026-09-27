package elo.mainplugins.shop.model;

import java.util.Map;

/**
 * Dodatki z shop.yml: premie rang, ogłaszanie promocji, ile dni historii sprzedaży (stats.history-days - historia
 * zbiera się razem ze statystykami, stats.enabled).
 * rankBonuses: nazwa rangi -> premia; gracz ma rangę, gdy ma uprawnienie mainplugins.shop.rank.<nazwa>.
 */
public record ShopExtras(Map<String, RankBonus> rankBonuses, boolean announceSales, int historyDays) {

    /** Rabat na kupno i premia do skupu w procentach (np. 2 = kupno 2% taniej, 1 = skup 1% drożej). */
    public record RankBonus(double buyDiscount, double sellBonus) {}

    public static ShopExtras defaults() {
        return new ShopExtras(Map.of(), true, 30);
    }
}
