package elo.mainplugins.skyblock.config;

import elo.mainplugins.core.api.Reward;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * Cała liczbowa/danych konfiguracja systemu wysp wczytana z wyspy-config.yml (patrz
 * IslandConfigLoader) - jeden niemutowalny snapshot, podmieniany w całości przy
 * /@reloadwyspy. Układ GUI (sloty/ikony/teksty) żyje osobno - patrz gui.IslandGuiContent.
 */
public record IslandTuning(
        int domyslnyRozmiarWyspy,
        List<CooldownProg> cooldownProb,
        int borderPrzyrostNaUlepszenie,
        int borderKosztPierwszego,
        double borderWzrostKosztuProcent,
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
        String nazwaSwiata,
        int wysokoscWyspy,
        long czasPogodyTicks,
        UstawieniaNowejWyspy nowaWyspa,
        int limitCzlonkow,
        List<Reward> nagrodyNaStart,
        boolean napisPrzyWejsciu,
        List<WzorWyspy> wzoryWysp,
        Map<Material, Integer> limityBlokow,
        int limitZwierzat,
        boolean mobyPotwory,
        boolean mobyZwierzeta,
        boolean powrotZPustki
) {
    /** Wzór wyspy do wyboru przy zakładaniu - pliki islands/<id>.nbt/.yml zapisuje /@islandtemplate save <id>. */
    public record WzorWyspy(String id, String nazwa, Material ikona, List<String> opis, List<org.bukkit.inventory.ItemStack> skrzynia) {}

    /** 0 = bez limitu dla tego bloku. */
    public int limitBloku(Material material) {
        return limityBlokow.getOrDefault(material, 0);
    }

    /** Ustawienia, z którymi startuje każda nowa wyspa (właściciel potem zmienia je w Ustawieniach/Permisjach Wyspy). */
    public record UstawieniaNowejWyspy(boolean pvp, boolean budowanieGosci, boolean wizualnyBorder,
                                       boolean zabijanieMobowGosci, boolean zabieranieItemowGosci, boolean skrzynieGosci,
                                       boolean interakcjeGosci, boolean zablokowanaPogoda, boolean ogien,
                                       boolean otwartaDlaOdwiedzajacych, boolean rolnictwoGosci, boolean wiadraGosci,
                                       boolean czlonkowieBudowanie, boolean czlonkowieSkrzynie,
                                       boolean czlonkowieZapraszanie, boolean czlonkowieBankIUlepszenia) {}

    /** Od próby "odProby" (włącznie) w górę obowiązuje "milisekundy" - lista MUSI być posortowana rosnąco po odProby. */
    public record CooldownProg(int odProby, long milisekundy) {}

    /**
     * Koszt powiększenia wyspy o obecnym promieniu: pierwsze powiększenie (od rozmiaru startowego)
     * kosztuje borderKosztPierwszego, każde kolejne o borderWzrostKosztuProcent % więcej.
     */
    public int kosztPowiekszenia(int obecnyRozmiar) {
        return kosztPowiekszenia(borderKosztPierwszego, borderWzrostKosztuProcent, domyslnyRozmiarWyspy, borderPrzyrostNaUlepszenie, obecnyRozmiar);
    }

    /** Wzór kosztu osobno, do testów. Które to powiększenie liczy się z promienia; wynik zaokrąglony do dziesiątek. */
    static int kosztPowiekszenia(int pierwszy, double wzrostProcent, int start, int krok, int obecnyRozmiar) {
        int ktore = Math.max(0, (int) Math.round((obecnyRozmiar - start) / (double) Math.max(1, krok)));
        double koszt = pierwszy * Math.pow(1 + wzrostProcent / 100.0, ktore);
        return (int) Math.min(Integer.MAX_VALUE, Math.round(koszt / 10.0) * 10);
    }

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
