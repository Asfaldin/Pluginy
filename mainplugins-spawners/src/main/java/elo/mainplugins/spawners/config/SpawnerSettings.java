package elo.mainplugins.spawners.config;

import org.bukkit.Material;

import java.util.List;

/**
 * Ustawienia gospodarcze customowych spawnerów wczytane z spawnery-typy.yml (patrz
 * SpawnerConfigLoader) - wspólne dla wszystkich typów, nie per-typ. Zastępuje dawne
 * hardkodowane stałe z SpawnerManager (PROMIEN_AKTYWNOSCI_GRACZA, LIMIT_SPAWNEROW_NA_WYSPE,
 * NARZEDZIE_ZBIERANIA) i z SpawnerType (LIMIT_KOLEJKI, MAX_LEVEL, interwalSekund/iloscNaCykl).
 *
 * @param ulepszeniaWlaczone false = okno ulepszeń się nie otwiera, wszystkie spawnery działają na poziomie 1
 * @param cenyIlosc          cena podstawowa ulepszenia Ilości z poziomu 1, 2, 3... na kolejny (dalsze poziomy = ostatnia)
 * @param cenySzybkosc       to samo dla Szybkości
 */
public record SpawnerSettings(
        int maxPoziom,
        int limitKolejki,
        int interwalSekundBazowy,
        int interwalSekundNaPoziom,
        int iloscNaCyklBazowa,
        int iloscNaCyklNaPoziom,
        int limitSpawnerowNaWyspe,
        int promienAktywnosciGracza,
        Material narzedzieZbierania,
        boolean ulepszeniaWlaczone,
        List<Integer> cenyIlosc,
        List<Integer> cenySzybkosc
) {
    /** Sekundy między cyklami spawnu przy danym poziomie - im wyższy poziom, tym częściej (przy domyślnych wartościach). */
    public int interwalSekund(int poziom) {
        return Math.max(1, interwalSekundBazowy + interwalSekundNaPoziom * poziom);
    }

    /** Ile mobków dorzuca do kolejki jeden cykl spawnu przy danym poziomie. */
    public int iloscNaCykl(int poziom) {
        return Math.max(0, iloscNaCyklBazowa + iloscNaCyklNaPoziom * poziom);
    }

    /** Cena ulepszenia z obecnyPoziom na kolejny dla spawnera z danym mnożnikiem. */
    public int kosztUlepszenia(double mnoznik, boolean ilosc, int obecnyPoziom) {
        return kosztUlepszenia(ilosc ? cenyIlosc : cenySzybkosc, mnoznik, obecnyPoziom);
    }

    /**
     * Wzór osobno, do testów - ten sam co dawniej w Skyblocku: cena podstawowa poziomu × mnożnik spawnera,
     * zaokrąglona do pełnych setek. Poziomy dalej niż lista biorą ostatnią cenę.
     */
    public static int kosztUlepszenia(List<Integer> ceny, double mnoznik, int obecnyPoziom) {
        if (ceny.isEmpty()) return 0;
        int baza = ceny.get(Math.max(0, Math.min(obecnyPoziom - 1, ceny.size() - 1)));
        return (int) (Math.round(baza * mnoznik / 100.0) * 100);
    }
}
