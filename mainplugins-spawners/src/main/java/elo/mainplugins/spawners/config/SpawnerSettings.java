package elo.mainplugins.spawners.config;

import org.bukkit.Material;

/**
 * Ustawienia gospodarcze customowych spawnerów wczytane z spawnery-typy.yml (patrz
 * SpawnerConfigLoader) - wspólne dla wszystkich typów, nie per-typ. Zastępuje dawne
 * hardkodowane stałe z SpawnerManager (PROMIEN_AKTYWNOSCI_GRACZA, LIMIT_SPAWNEROW_NA_WYSPE,
 * NARZEDZIE_ZBIERANIA) i z SpawnerType (LIMIT_KOLEJKI, MAX_LEVEL, interwalSekund/iloscNaCykl).
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
        Material narzedzieZbierania
) {
    /** Sekundy między cyklami spawnu przy danym poziomie - im wyższy poziom, tym częściej (przy domyślnych wartościach). */
    public int interwalSekund(int poziom) {
        return Math.max(1, interwalSekundBazowy + interwalSekundNaPoziom * poziom);
    }

    /** Ile mobków dorzuca do kolejki jeden cykl spawnu przy danym poziomie. */
    public int iloscNaCykl(int poziom) {
        return Math.max(0, iloscNaCyklBazowa + iloscNaCyklNaPoziom * poziom);
    }
}
