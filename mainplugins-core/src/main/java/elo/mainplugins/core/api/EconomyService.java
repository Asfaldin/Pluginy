package elo.mainplugins.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Wspólny kontrakt ekonomii dla całego ekosystemu Mainplugins.
 * Implementację (EconomyManager) dostarcza wyłącznie plugin MainpluginsCore,
 * rejestrując ją w Bukkit ServicesManager. Inne pluginy pobierają instancję
 * przez {@link elo.mainplugins.core.CoreAPI#getEconomyService()} zamiast
 * tworzyć własną - to jedyny sposób na dostęp do wspólnej kasy graczy.
 */
public interface EconomyService {

    // ---- dotychczasowe API, bez zmian ----
    double getKasa(UUID uuid);

    void setKasa(UUID uuid, double ilosc);

    void dodajKase(UUID uuid, double ilosc);

    void odejmijKase(UUID uuid, double ilosc);

    boolean maWystarczajaco(UUID uuid, double ilosc);

    /** Najbogatsi gracze posortowani malejąco wg stanu konta (pomija graczy z kasą <= 0). */
    List<TopGracz> getTop(int limit);

    /**
     * Pozycja gracza w pełnym rankingu bogactwa (1 = najbogatszy), niezależnie
     * od tego, ile pozycji zwraca getTop(). Gracze z kasą <= 0 nie są rankowani -
     * tak samo jak w getTop(), które ich pomija.
     *
     * @return pozycja (1-indeksowana) albo -1, gdy gracz ma kasę <= 0
     */
    int getPozycjaWRankingu(UUID uuid);

    // ---- nowe: dokładne operacje na groszach ----

    /** Saldo w groszach (1 zł = 100). Bez zaokrągleń i dryfu. */
    long getGrosze(UUID uuid);

    /** Ustawia saldo w groszach. Wartości ujemne są przycinane do zera. */
    void setGrosze(UUID uuid, long grosze);

    /** Dodaje grosze. Ujemna wartość odejmuje. */
    void dodajGrosze(UUID uuid, long grosze);

    /**
     * Pobiera grosze tylko jeśli gracza na to stać.
     * @return true gdy pobrano, false gdy zabrakło (saldo nietknięte)
     *
     * Tego używaj przy transakcjach zamiast pary maWystarczajaco()+odejmijKase() —
     * sprawdzenie i pobranie w jednym kroku, więc nie da się między nimi wcisnąć
     * drugiej transakcji tego samego gracza.
     */
    boolean pobierzGrosze(UUID uuid, long grosze);
}