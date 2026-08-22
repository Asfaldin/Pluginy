package elo.mainplugins.skyblock.config;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cała liczbowa/danych konfiguracja systemu wysp wczytana z wyspy-config.yml (patrz
 * IslandConfigLoader) - jeden niemutowalny snapshot, podmieniany w całości przy
 * /@reloadwyspy. Układ GUI (sloty/ikony/teksty) żyje osobno - patrz gui.IslandGuiContent.
 */
public record IslandTuning(
        int domyslnyRozmiarWyspy,
        List<CooldownProg> cooldownProb,
        int borderPrzyrostNaUlepszenie,
        int borderKosztZaBlok,
        int borderMaxRozmiar,
        int odstepSiatkiWysp,
        int maxGlebokoscSzukaniaWDol,
        int promienSzukaniaObok,
        long timeoutPotwierdzeniaTicks,
        long timeoutZaproszeniaTicks,
        long maxLotPerlyTicks,
        int maxDlugoscNazwyWyspy,
        int zapasNaSchemat,
        int chunkiNaTick,
        Map<Material, Double> wartosciBlokow,
        int spawnerMaxPoziom,
        List<SpawnerTyp> spawnerTypy,
        Map<Integer, Integer> kosztBazowyIloscPoziomy,
        int kosztBazowyIloscDomyslny,
        Map<Integer, Integer> kosztBazowySzybkoscPoziomy,
        int kosztBazowySzybkoscDomyslny,
        int snifferPromienZbioru,
        int snifferWysokoscZbioru,
        int snifferPromienSzukaniaSkrzyni,
        int snifferPromienWedrowania,
        long snifferSkanOdstepTicks,
        Set<Material> snifferUprawy
) {
    /** Od próby "odProby" (włącznie) w górę obowiązuje "milisekundy" - lista MUSI być posortowana rosnąco po odProby. */
    public record CooldownProg(int odProby, long milisekundy) {}

    /** Zwraca 0 dla materiałów spoza wartosciBlokow. */
    public double wartoscBloku(Material material) {
        return wartosciBlokow.getOrDefault(material, 0.0);
    }

    /** Cooldown przed N-tą próbą utworzenia wyspy w życiu gracza - patrz komentarz przy CooldownProg. */
    public long cooldownDlaProby(int numerProby) {
        long wynik = 0L;
        for (CooldownProg prog : cooldownProb) {
            if (prog.odProby() <= numerProby) wynik = prog.milisekundy();
        }
        return wynik;
    }

    public SpawnerTyp spawnerTyp(String id) {
        for (SpawnerTyp typ : spawnerTypy) {
            if (typ.id().equals(id)) return typ;
        }
        return null;
    }

    /** Najtańszy spawner w sklepie wyznacza skalę - jego mnożnik kosztu wynosi 1.0. */
    private int cenaBazowegoSpawnera() {
        int min = Integer.MAX_VALUE;
        for (SpawnerTyp typ : spawnerTypy) min = Math.min(min, typ.cenaWSklepie());
        return min == Integer.MAX_VALUE ? 1 : min;
    }

    /** Ile razy drożej ulepsza się dany spawner względem najtańszego - pierwiastek spłaszcza różnicę cen. */
    public double mnoznikKosztu(String typId) {
        SpawnerTyp typ = spawnerTyp(typId);
        int cena = typ != null ? typ.cenaWSklepie() : cenaBazowegoSpawnera();
        return Math.sqrt((double) cena / cenaBazowegoSpawnera());
    }

    /** Koszt ulepszenia z obecnyPoziom na kolejny, zaokrąglony do pełnych setek (jak dawniej). */
    public int kosztUlepszeniaSpawnera(String typId, boolean ilosc, int obecnyPoziom) {
        Map<Integer, Integer> poziomy = ilosc ? kosztBazowyIloscPoziomy : kosztBazowySzybkoscPoziomy;
        int domyslny = ilosc ? kosztBazowyIloscDomyslny : kosztBazowySzybkoscDomyslny;
        int baza = poziomy.getOrDefault(obecnyPoziom, domyslny);
        return (int) (Math.round(baza * mnoznikKosztu(typId) / 100.0) * 100);
    }
}
