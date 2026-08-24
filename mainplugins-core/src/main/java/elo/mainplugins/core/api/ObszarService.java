package elo.mainplugins.core.api;

import org.bukkit.Location;

/**
 * Dostęp z zewnątrz do obszarów zdefiniowanych w mainplugins-spawn (patrz ObszarManager) -
 * na razie tylko jedno pytanie: czy dana lokalizacja leży w obszarze oznaczonym jako
 * "łowisko" (flaga ryby-dozwolone, włączana komendą /@obszar ryby &lt;nazwa&gt; &lt;on|off&gt;).
 * Używane przez mainplugins-fishing, żeby specjalne ryby łapały się WYŁĄCZNIE w
 * wyznaczonych miejscach, a nie wszędzie.
 *
 * Opcjonalny jak {@link IslandService} - implementację dostarcza wyłącznie mainplugins-spawn;
 * {@link elo.mainplugins.core.CoreAPI#getObszarService()} zwraca null, jeśli ten plugin nie
 * jest wgrany/włączony - wołający musi mieć na to sensowny fallback (dla łowienia: po prostu
 * żadna lokalizacja nie jest wtedy łowiskiem, wanilijskie łowienie zostaje nietknięte).
 */
public interface ObszarService {

    /** Czy podana lokalizacja leży wewnątrz obszaru z włączoną flagą ryby-dozwolone. */
    boolean jestLowiskiem(Location loc);
}
