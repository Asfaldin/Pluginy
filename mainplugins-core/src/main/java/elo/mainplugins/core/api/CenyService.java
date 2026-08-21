package elo.mainplugins.core.api;

import java.util.List;

/**
 * Cienki interfejs nad dynamicznymi cenami sklepu (patrz mainplugins-shop /
 * DynamicPriceManager) - żeby inne moduły (HUD, ewentualnie questy) mogły
 * czytać stan cen bez twardej zależności od modułu shop.
 */
public interface CenyService {

    /** Jedno odchylenie ceny od bazy - do wyświetlenia jako "co teraz warto sprzedać". */
    record Odchylenie(String nazwa, double mnoznik) {}

    /**
     * Item z największym bieżącym odchyleniem od ceny bazowej, w dowolną
     * stronę (może to być zarówno spadek, jak i wzrost - i tak ma sens
     * pokazać graczowi, gdzie dzieje się coś nietypowego).
     *
     * @return null, gdy brak danych albo wszystkie itemy są w normie (blisko 1.0)
     */
    Odchylenie najwiekszeOdchylenie();

    /** Ile dni zostało do najbliższego globalnego resetu cen. */
    int dniDoResetu();

    /** Nazwy wyświetlane itemów z aktywnym, zablokowanym mnożnikiem (eventy). */
    List<String> getZablokowaneNazwy();
}
