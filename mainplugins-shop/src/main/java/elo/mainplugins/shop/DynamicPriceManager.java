package elo.mainplugins.shop;

import elo.mainplugins.core.util.AsyncConfigSaver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mnożniki cen skupu reagujące na obrót.
 *
 * Model: dla każdego itemu trzymamy mnożnik M oraz średnią kroczącą obrotu.
 * Co godzinę: obrót większy od średniej ściąga M w dół, mniejszy podnosi go
 * w górę, brak obrotu powoli przywraca M do 1.0.
 *
 * Wszystko liczone jest na SZTUKACH, nie na dolarach — inaczej itemy tanie
 * (bruk) nigdy nie zważyłyby tyle, co drogie (diament), i mechanizm dotyczyłby
 * w praktyce tylko końcówki cennika.
 */
public class DynamicPriceManager {

    // ---- parametry modelu (jedyne miejsce do strojenia) ----

    /** Ile minut trwa cykl. 60 = korekta co godzinę. */
    private static final int MINUT_NA_CYKL = 60;

    /** Dolna i górna granica mnożnika. Skup nigdy nie zejdzie poniżej 50% ani nie przekroczy 150% ceny z cennika. */
    private static final double M_MIN = 0.50;
    private static final double M_MAX = 1.50;

    /** Maksymalna zmiana mnożnika w jednym cyklu. Chroni przed skokami cen po jednej dużej sprzedaży. */
    private static final double MAX_KROK = 0.05;

    /** Jak szybko średnia krocząca zapomina stare cykle. 0.02 ≈ tydzień historii przy cyklu godzinnym. */
    private static final double WSPOLCZYNNIK_SREDNIEJ = 0.02;

    /** O ile M wraca do 1.0 przy zerowym obrocie. Wolniej niż MAX_KROK — cisza nie ma windować cen tak szybko, jak windują je transakcje. */
    private static final double POWROT_DO_BAZY = 0.01;

    /**
     * Cena skupu nie może przekroczyć tego ułamka ceny kupna. Bez tego przy
     * M=1.5 skup mógłby wyjść wyżej niż kupno i powstałaby maszynka do
     * pieniędzy: kup w sklepie, sprzedaj do sklepu, zysk bez pracy.
     */
    private static final double MAX_UDZIAL_W_CENIE_KUPNA = 0.90;

    // ---- stan ----

    private final Plugin plugin;
    private final File plik;
    private final FileConfiguration config;
    private final AsyncConfigSaver saver;

    /**
     * ConcurrentHashMap, bo transakcje graczy lecą z głównego wątku, a cykl
     * korekty z zadania czasowego. Zwykła HashMap dałaby tu wyścig.
     */
    private final Map<String, Double> mnozniki = new ConcurrentHashMap<>();
    private final Map<String, Double> sredniObrot = new ConcurrentHashMap<>();
    private final Map<String, Integer> obrotBiezacegoCyklu = new ConcurrentHashMap<>();

    public DynamicPriceManager(Plugin plugin) {
        this.plugin = plugin;
        this.plik = new File(plugin.getDataFolder(), "ceny-dynamiczne.yml");
        this.config = YamlConfiguration.loadConfiguration(plik);
        wczytaj();
        this.saver = new AsyncConfigSaver(plugin, config, plik, 60);

        long ticki = MINUT_NA_CYKL * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::wykonajCykl, ticki, ticki);
    }

    // ==================================================== zapis i odczyt ====

    private void wczytaj() {
        for (String klucz : config.getKeys(false)) {
            mnozniki.put(klucz, config.getDouble(klucz + ".mnoznik", 1.0));
            sredniObrot.put(klucz, config.getDouble(klucz + ".sredni-obrot", 0.0));
        }
        plugin.getLogger().info("Ceny dynamiczne: wczytano " + mnozniki.size() + " pozycji.");
    }

    private void zapiszStan() {
        for (Map.Entry<String, Double> e : mnozniki.entrySet()) {
            config.set(e.getKey() + ".mnoznik", zaokraglij(e.getValue()));
            config.set(e.getKey() + ".sredni-obrot", zaokraglij(sredniObrot.getOrDefault(e.getKey(), 0.0)));
        }
        saver.oznaczZmiane();
    }

    private static double zaokraglij(double x) {
        return Math.round(x * 1000.0) / 1000.0;
    }

    // ==================================================== API dla sklepu ====

    /**
     * Rejestruje sprzedaż do sklepu. Woła ShopManager po każdej udanej
     * transakcji skupu.
     *
     * @param klucz identyfikator itemu (custom-id albo nazwa materiału)
     * @param sztuk ile sztuk gracz sprzedał
     */
    public void zarejestrujSprzedaz(String klucz, int sztuk) {
        obrotBiezacegoCyklu.merge(klucz, sztuk, Integer::sum);
    }

    /** Aktualny mnożnik itemu. 1.0 gdy item jest nowy albo nigdy nie handlowany. */
    public double getMnoznik(String klucz) {
        return mnozniki.getOrDefault(klucz, 1.0);
    }

    /**
     * Cena skupu po korekcie, w tych samych jednostkach co cena z cennika.
     *
     * @param cenaZCennika bazowa cena skupu za lot
     * @param cenaKupnaZaLot cena kupna za lot (dla ochrony przed odwróceniem marży);
     *                       podaj -1, jeśli itemu nie da się kupić
     */
    public int policzCeneSkupu(String klucz, int cenaZCennika, int cenaKupnaZaLot) {
        double m = getMnoznik(klucz);
        int cena = (int) Math.round(cenaZCennika * m);

        if (cenaKupnaZaLot > 0) {
            int sufit = (int) Math.floor(cenaKupnaZaLot * MAX_UDZIAL_W_CENIE_KUPNA);
            if (cena > sufit) cena = sufit;
        }
        return Math.max(1, cena);
    }

    /** Do wyświetlenia w GUI: -12% / +8% / null gdy cena bez zmian. */
    public String opisZmiany(String klucz) {
        double m = getMnoznik(klucz);
        int procent = (int) Math.round((m - 1.0) * 100);
        if (procent == 0) return null;
        return (procent > 0 ? "+" : "") + procent + "%";
    }

    // ==================================================== cykl korekty ====

    /**
     * Jeden cykl: porównaj obrót każdego itemu z jego własną średnią i przesuń
     * mnożnik. Itemy bez obrotu dryfują z powrotem do 1.0.
     */
    private void wykonajCykl() {
        // Zdejmujemy obrót cyklu jednym ruchem, żeby transakcje zachodzące
        // w trakcie liczenia trafiły już do następnego cyklu, a nie zginęły.
        Map<String, Integer> obrot = Map.copyOf(obrotBiezacegoCyklu);
        obrotBiezacegoCyklu.clear();

        // 1) itemy, którymi handlowano w tym cyklu
        for (Map.Entry<String, Integer> e : obrot.entrySet()) {
            String klucz = e.getKey();
            double obecny = e.getValue();
            double srednia = sredniObrot.getOrDefault(klucz, 0.0);

            if (srednia < 1.0) {
                // Pierwszy obrót tym itemem — nie mamy jeszcze do czego porównać,
                // więc tylko zakładamy średnią i nie ruszamy ceny.
                sredniObrot.put(klucz, obecny);
                continue;
            }

            // Stosunek do własnej normy: 2.0 = sprzedano dwa razy więcej niż zwykle.
            double stosunek = obecny / srednia;

            // log2 daje symetrię: 2x więcej i 2x mniej ruszają cenę o tyle samo,
            // tylko w przeciwne strony. Bez logarytmu wzrost obrotu (bez limitu)
            // ważyłby dużo więcej niż spadek (najwyżej do zera).
            double zmiana = -(Math.log(stosunek) / Math.log(2)) * MAX_KROK;
            zmiana = Math.max(-MAX_KROK, Math.min(MAX_KROK, zmiana));

            double nowy = mnozniki.getOrDefault(klucz, 1.0) + zmiana;
            mnozniki.put(klucz, Math.max(M_MIN, Math.min(M_MAX, nowy)));

            // Średnia krocząca: nowa wartość wchodzi z wagą WSPOLCZYNNIK_SREDNIEJ.
            sredniObrot.put(klucz, srednia + (obecny - srednia) * WSPOLCZYNNIK_SREDNIEJ);
        }

        // 2) itemy bez obrotu — powolny powrót do bazy i wygaszanie średniej
        for (String klucz : mnozniki.keySet()) {
            if (obrot.containsKey(klucz)) continue;

            double m = mnozniki.get(klucz);
            if (m < 1.0) m = Math.min(1.0, m + POWROT_DO_BAZY);
            else if (m > 1.0) m = Math.max(1.0, m - POWROT_DO_BAZY);
            mnozniki.put(klucz, m);

            // Bez tego item, którym handlowano intensywnie i przestano, miałby
            // na zawsze zawyżoną normę i po powrocie handlu jego cena od razu
            // by wystrzeliła.
            double srednia = sredniObrot.getOrDefault(klucz, 0.0);
            sredniObrot.put(klucz, srednia * (1.0 - WSPOLCZYNNIK_SREDNIEJ));
        }

        zapiszStan();
    }

    /** Wywołaj w onDisable(). */
    public void zamknij() {
        zapiszStan();
        saver.zamknij();
    }

    /** Reset wszystkich mnożników do 1.0 — do komendy administracyjnej. */
    public void resetuj() {
        mnozniki.clear();
        sredniObrot.clear();
        obrotBiezacegoCyklu.clear();
        for (String klucz : config.getKeys(false)) config.set(klucz, null);
        saver.oznaczZmiane();
    }
}
