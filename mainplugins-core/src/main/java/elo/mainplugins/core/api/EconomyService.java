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

    double getKasa(UUID uuid);

    void setKasa(UUID uuid, double ilosc);

    void dodajKase(UUID uuid, double ilosc);

    void odejmijKase(UUID uuid, double ilosc);

    boolean maWystarczajaco(UUID uuid, double ilosc);

    /** Najbogatsi gracze posortowani malejąco wg stanu konta (pomija graczy z kasą <= 0). */
    List<TopGracz> getTop(int limit);
}