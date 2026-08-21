package elo.mainplugins.shop;

import elo.mainplugins.core.api.CenyService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mnożniki cen skupu reagujące na obrót. Pełny opis modelu w nagłówku patcha.
 *
 * Wszystko liczone jest na SZTUKACH, nie na dolarach, i każdy item porównywany
 * jest wyłącznie SAM ZE SOBĄ. Gdyby porównywać obrót w dolarach do wspólnego
 * progu, tanie itemy (bruk) nigdy nie zaważyłyby tyle, co drogie (diament),
 * i mechanizm dotyczyłby w praktyce tylko końcówki cennika.
 */
public class DynamicPriceManager implements CenyService {

    // =========================================================================
    //  PARAMETRY MODELU — jedyne miejsce do strojenia
    // =========================================================================

    /** Długość cyklu w minutach. Na czas testów warto ustawić 1, żeby nie czekać godziny. */
    private static final int MINUT_NA_CYKL = 60;

    /** Twarde granice mnożnika. */
    private static final double M_MIN = 0.50;
    private static final double M_MAX = 1.50;

    /** Maksymalny spadek w jednym cyklu przy standardowej cenie (mnożnik = 1.0). */
    private static final double MAX_SPADEK = 0.05;

    /**
     * Ile razy mocniejszy jest spadek, gdy mnożnik stoi na samym szczycie (1.50).
     * Dobrane tak, żeby zejście z 1.50 do 1.0 przy ciągłej sprzedaży zajęło ~3h.
     *
     * Uzasadnienie: cena wysoka wzięła się TYLKO stąd, że nikt tego nie sprzedawał.
     * To nie jest realna wartość, tylko zaległość. Gdy pojawia się realna podaż,
     * cena powinna szybko wrócić na ziemię — bo okazuje się, że towar jednak jest.
     */
    private static final double MNOZNIK_SPADKU_NA_SZCZYCIE = 4.2;

    /** Jaka część pozostałego dystansu do 1.0 jest odrabiana w jednym cyklu ciszy. */
    private static final double TEMPO_POWROTU_Z_DOLU = 0.80;

    /** O ile rośnie mnożnik na cykl po rozpoczęciu wzrostu (1.0 → 1.50 w 4 cykle). */
    private static final double TEMPO_WZROSTU = 0.125;

    /** Sprzedaż poniżej tego ułamka normy liczy się jako "prawie cisza". */
    private static final double PROG_CISZY = 0.10;

    /** Ile cykli prawie ciszy, zanim mnożnik zacznie rosnąć ponad 1.0. */
    private static final int CYKLI_DO_WZROSTU = 2;

    /** Ile cykli mnożnik stoi zamrożony na 1.0 po zejściu z góry. */
    private static final int CYKLI_ZAMROZENIA = 2;

    /** Jak szybko norma zapomina stare cykle. 0.02 ≈ tydzień historii przy cyklu godzinnym. */
    private static final double TEMPO_UCZENIA_NORMY = 0.02;

    /** Cena skupu nie może przekroczyć tego ułamka ceny kupna. */
    private static final double MAX_UDZIAL_W_CENIE_KUPNA = 0.90;

    /** Co ile cykli globalny reset wszystkich mnożników do 1.0. 336 = 14 dni. */
    private static final int CYKLI_DO_RESETU = 336;

    // =========================================================================
    //  STAN
    // =========================================================================

    /**
     * Wszystko w ConcurrentHashMap, bo transakcje graczy lecą z głównego wątku,
     * a cykl korekty z zadania czasowego. Zwykła HashMap dałaby tu wyścig.
     */
    private final Map<String, Double> mnozniki = new ConcurrentHashMap<>();
    private final Map<String, Double> normy = new ConcurrentHashMap<>();
    private final Map<String, Integer> obrotCyklu = new ConcurrentHashMap<>();

    /** Ile cykli z rzędu trwa prawie cisza. */
    private final Map<String, Integer> licznikSuszy = new ConcurrentHashMap<>();

    /**
     * Próg suszy zamrożony w chwili jej rozpoczęcia. Bez tego próg liczony
     * na bieżąco z normy KURCZYŁBY SIĘ razem z nią podczas ciszy — im dłużej
     * trwa susza, tym łatwiej byłoby ją przypadkiem przerwać. Zamrożenie
     * sprawia, że próg jest stały przez całą suszę.
     */
    private final Map<String, Double> zamrozonyProg = new ConcurrentHashMap<>();

    /** Ile cykli zostało do końca zamrożenia na 1.0 po zejściu z góry. */
    private final Map<String, Integer> zamrozenieNaBazie = new ConcurrentHashMap<>();

    /**
     * Itemy z ręcznie zablokowanym mnożnikiem (eventy, korekty awaryjne).
     * Cykl korekty je pomija — cena stoi, dopóki admin nie odblokuje.
     */
    private final Set<String> zablokowane = ConcurrentHashMap.newKeySet();

    private final Plugin plugin;
    private final ShopManager shopManager;
    private final File plik;
    private final FileConfiguration config;
    private final AsyncConfigSaver saver;
    private final StatystykiSklepu statystyki;
    private int cykliOdResetu = 0;

    /**
     * @param shopManager potrzebny wyłącznie do nazwaWyswietlana() (patrz CenyService) -
     *                    ShopManager przekazuje "this" zanim jego własny konstruktor się
     *                    skończy, ale to bezpieczne: nazwaWyswietlana() woła się dopiero
     *                    później (przy żądaniu placeholdera), nigdy w trakcie budowy.
     */
    public DynamicPriceManager(Plugin plugin, ShopManager shopManager) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.plik = new File(plugin.getDataFolder(), "ceny-dynamiczne.yml");
        this.config = YamlConfiguration.loadConfiguration(plik);
        this.statystyki = new StatystykiSklepu(plugin);
        wczytaj();
        this.saver = new AsyncConfigSaver(plugin, config, plik, 60);

        Bukkit.getServicesManager().register(CenyService.class, this,
                plugin, org.bukkit.plugin.ServicePriority.Normal);

        long ticki = MINUT_NA_CYKL * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::wykonajCykl, ticki, ticki);
    }

    /** Żeby ShopManager mógł zapisać wypłaconą kwotę po każdej sprzedaży. */
    public StatystykiSklepu getStatystyki() { return statystyki; }

    /** Kopia mapy mnożników — do wyświetlania, nie do modyfikacji. */
    public Map<String, Double> getWszystkieMnozniki() {
        return new HashMap<>(mnozniki);
    }

    // =========================================================================
    //  ZAPIS I ODCZYT
    // =========================================================================

    private void wczytaj() {
        cykliOdResetu = config.getInt("_meta.cykli-od-resetu", 0);
        for (String klucz : config.getKeys(false)) {
            if (klucz.equals("_meta")) continue;
            mnozniki.put(klucz, config.getDouble(klucz + ".mnoznik", 1.0));
            normy.put(klucz, config.getDouble(klucz + ".norma", 0.0));
            licznikSuszy.put(klucz, config.getInt(klucz + ".susza", 0));
            zamrozenieNaBazie.put(klucz, config.getInt(klucz + ".zamrozenie", 0));
            double prog = config.getDouble(klucz + ".prog-suszy", -1);
            if (prog >= 0) zamrozonyProg.put(klucz, prog);
            if (config.getBoolean(klucz + ".zablokowany", false)) zablokowane.add(klucz);
        }
        plugin.getLogger().info("Ceny dynamiczne: wczytano " + mnozniki.size() + " pozycji.");
    }

    private void zapiszStan() {
        config.set("_meta.cykli-od-resetu", cykliOdResetu);
        for (String klucz : mnozniki.keySet()) {
            config.set(klucz + ".mnoznik", zaokr(mnozniki.get(klucz)));
            config.set(klucz + ".norma", zaokr(normy.getOrDefault(klucz, 0.0)));
            config.set(klucz + ".susza", licznikSuszy.getOrDefault(klucz, 0));
            config.set(klucz + ".zamrozenie", zamrozenieNaBazie.getOrDefault(klucz, 0));
            Double prog = zamrozonyProg.get(klucz);
            config.set(klucz + ".prog-suszy", prog != null ? zaokr(prog) : null);
            config.set(klucz + ".zablokowany", zablokowane.contains(klucz) ? true : null);
        }
        saver.oznaczZmiane();
    }

    private static double zaokr(double x) {
        return Math.round(x * 1000.0) / 1000.0;
    }

    // =========================================================================
    //  API DLA SKLEPU
    // =========================================================================

    /**
     * Rejestruje sprzedaż do sklepu. Woła ShopManager po każdej udanej transakcji.
     *
     * @param klucz identyfikator itemu — custom-id, a gdy go nie ma, nazwa materiału
     * @param sztuk ile sztuk gracz sprzedał
     */
    public void zarejestrujSprzedaz(String klucz, int sztuk) {
        obrotCyklu.merge(klucz, sztuk, Integer::sum);
    }

    public double getMnoznik(String klucz) {
        return mnozniki.getOrDefault(klucz, 1.0);
    }

    /**
     * Cena skupu po korekcie.
     *
     * @param cenaZCennika  bazowa cena skupu za lot (z categories/*.yml)
     * @param cenaKupnaZaLot cena kupna przeliczona na TEN SAM rozmiar lotu; -1 gdy
     *                       itemu nie da się kupić
     */
    public int policzCeneSkupu(String klucz, int cenaZCennika, int cenaKupnaZaLot) {
        int cena = (int) Math.round(cenaZCennika * getMnoznik(klucz));

        // Bez tego przy wysokim mnożniku skup mógłby przebić kupno i powstałaby
        // maszynka do pieniędzy: kup w sklepie, sprzedaj do sklepu, zysk bez pracy.
        if (cenaKupnaZaLot > 0) {
            int sufit = (int) Math.floor(cenaKupnaZaLot * MAX_UDZIAL_W_CENIE_KUPNA);
            if (cena > sufit) cena = sufit;
        }
        return Math.max(1, cena);
    }

    /** Strzałka do GUI: 1 = cena wyższa niż zwykle, -1 = niższa, 0 = normalna. */
    public int kierunekZmiany(String klucz) {
        double m = getMnoznik(klucz);
        if (m > 1.02) return 1;
        if (m < 0.98) return -1;
        return 0;
    }

    // =========================================================================
    //  CYKL — serce mechanizmu
    // =========================================================================

    private void wykonajCykl() {
        // Reset globalny co 14 dni. Wentyl bezpieczeństwa: bez niego farmowalne
        // itemy utknęłyby na 0.50 na zawsze, bo norma wchłania stałą podaż farmy
        // i przestaje ją widzieć jako nadmiar.
        if (++cykliOdResetu >= CYKLI_DO_RESETU) {
            resetujWszystko();
            return;
        }

        // Zdejmujemy obrót jednym ruchem, żeby transakcje zachodzące w trakcie
        // liczenia trafiły do NASTĘPNEGO cyklu, a nie zginęły w połowie.
        Map<String, Integer> obrot = new HashMap<>(obrotCyklu);
        obrotCyklu.clear();

        // Itemy bez obrotu też muszą przejść przez cykl (susza, powrót, wzrost),
        // więc bierzemy sumę wszystkich znanych kluczy.
        java.util.Set<String> wszystkie = new java.util.HashSet<>(mnozniki.keySet());
        wszystkie.addAll(obrot.keySet());

        for (String klucz : wszystkie) {
            przetworzItem(klucz, obrot.getOrDefault(klucz, 0));
        }
        zapiszStan();
        statystyki.zapisz();
    }

    private void przetworzItem(String klucz, int sprzedano) {
        // Item zablokowany ręcznie - cykl go nie rusza. Ale obrót nadal
        // rejestrujemy w statystykach, bo to realne dane o rynku.
        if (zablokowane.contains(klucz)) {
            if (sprzedano > 0) {
                double norma = normy.getOrDefault(klucz, 0.0);
                if (norma < 1.0) normy.put(klucz, (double) sprzedano);
                else normy.put(klucz, norma + (sprzedano - norma) * TEMPO_UCZENIA_NORMY);
            }
            statystyki.zapiszCykl(klucz, mnozniki.getOrDefault(klucz, 1.0));
            return;
        }

        double norma = normy.getOrDefault(klucz, 0.0);

        // --- NOWY ITEM ---------------------------------------------------
        // Pierwsza sprzedaż nie ma z czym się porównać (dzielenie przez zero),
        // więc tylko ustawia normę i nie rusza jeszcze ceny.
        if (norma < 1.0) {
            if (sprzedano > 0) normy.put(klucz, (double) sprzedano);
            mnozniki.putIfAbsent(klucz, 1.0);
            return;
        }

        double m = mnozniki.getOrDefault(klucz, 1.0);
        int susza = licznikSuszy.getOrDefault(klucz, 0);
        int zamrozenie = zamrozenieNaBazie.getOrDefault(klucz, 0);

        // Próg suszy: zamrożony jeśli susza już trwa, świeży jeśli dopiero patrzymy.
        double prog = zamrozonyProg.containsKey(klucz)
                ? zamrozonyProg.get(klucz)
                : norma * PROG_CISZY;

        boolean cisza = sprzedano < prog;

        if (cisza) {
            // ================= GAŁĄŹ CISZY =================

            // Pierwszy cykl ciszy zamraża próg na całą jej długość.
            zamrozonyProg.putIfAbsent(klucz, norma * PROG_CISZY);
            susza++;

            if (m < 1.0) {
                // Powrót do normy z dołu. Szybki, bo przy realnym ruchu graczy
                // ciągła drobna sprzedaż i tak stale ściąga cenę — powrót musi
                // nadążać, inaczej nic nigdy nie wróciłoby do 1.0.
                m += (1.0 - m) * TEMPO_POWROTU_Z_DOLU;
                if (m > 0.995) m = 1.0;   // domknięcie, żeby nie pełzać w nieskończoność

            } else if (zamrozenie > 0) {
                // Zamrożenie po zejściu z góry — mnożnik stoi na 1.0.
                // Licznik suszy tyka RÓWNOLEGLE (ustalone: ma tykać od razu),
                // więc po zakończeniu zamrożenia wzrost może ruszyć bez zwłoki.
                zamrozenie--;

            } else if (susza >= CYKLI_DO_WZROSTU) {
                // Susza dojrzała — cena rośnie ponad bazę, żeby skusić kogoś
                // do sprzedania zaległego towaru.
                m += TEMPO_WZROSTU;
            }
            // else: mnożnik == 1.0, susza jeszcze za krótka — stoimy

        } else {
            // ================= GAŁĄŹ SPRZEDAŻY =================

            // Susza przerwana. Licznik cofa się o 1, nie zeruje całkiem —
            // inaczej jedna przypadkowa transakcja tuż przed końcem progu
            // kasowałaby cały postęp i susza prawie nigdy by nie ruszyła.
            susza = Math.max(0, susza - 1);
            zamrozonyProg.remove(klucz);

            // Jeśli mnożnik był powyżej 1.0 i właśnie spada, po dojściu do 1.0
            // ma się zatrzymać na 2 cykle (żeby nie przelecieć przez bazę w dół).
            if (m > 1.0) zamrozenie = CYKLI_ZAMROZENIA;

            m -= policzSpadek(m, sprzedano, norma);
        }

        // Aktualizacja normy — żyje cały czas, także podczas ciszy.
        // Zamrożony jest tylko PRÓG, nie sama norma.
        normy.put(klucz, norma + (sprzedano - norma) * TEMPO_UCZENIA_NORMY);

        mnozniki.put(klucz, Math.max(M_MIN, Math.min(M_MAX, m)));
        licznikSuszy.put(klucz, susza);
        zamrozenieNaBazie.put(klucz, zamrozenie);

        statystyki.zapiszCykl(klucz, mnozniki.get(klucz));
    }

    /**
     * Siła spadku = WIĘKSZY z dwóch efektów, nie ich suma.
     *
     * Suma byłaby niebezpieczna: ktoś sprzedający ogromną ilość itemu stojącego
     * na szczycie zrzuciłby cenę z 1.50 do 0.50 w jednym cyklu, bo oba efekty
     * nałożyłyby się na siebie.
     */
    private double policzSpadek(double mnoznik, int sprzedano, double norma) {
        // Efekt 1 — ILOŚĆ. Ile razy więcej niż zwykle sprzedano.
        // log2 daje symetrię i wygaszanie: 2x norma to nie dwa razy większy
        // spadek niż 1x, tylko jeden "krok". Bez logarytmu sprzedaż 50x normy
        // dawałaby absurdalny wynik.
        double stosunek = sprzedano / Math.max(1.0, norma);
        double efektIlosci = 0.0;
        if (stosunek > 1.0) {
            efektIlosci = (Math.log(stosunek) / Math.log(2)) * MAX_SPADEK;
            efektIlosci = Math.min(efektIlosci, MAX_SPADEK);
        }

        // Efekt 2 — WYSOKOŚĆ. Im wyżej mnożnik ponad 1.0, tym mocniej boli
        // sprzedaż. Na szczycie (1.50) spadek jest MNOZNIK_SPADKU_NA_SZCZYCIE
        // razy silniejszy niż standardowy.
        double efektWysokosci = 0.0;
        if (mnoznik > 1.0) {
            double ponad = (mnoznik - 1.0) / (M_MAX - 1.0);   // 0.0 na bazie, 1.0 na szczycie
            efektWysokosci = MAX_SPADEK * (1.0 + ponad * (MNOZNIK_SPADKU_NA_SZCZYCIE - 1.0));
        }

        return Math.max(efektIlosci, efektWysokosci);
    }

    private void resetujWszystko() {
        cykliOdResetu = 0;
        // Zablokowane (eventowe) itemy reset pomija — event trzyma się mocno,
        // dopóki ktoś go świadomie nie zdejmie przez "/@sklep event <item> off".
        for (String klucz : mnozniki.keySet()) {
            if (zablokowane.contains(klucz)) continue;
            mnozniki.put(klucz, 1.0);
            licznikSuszy.remove(klucz);
            zamrozonyProg.remove(klucz);
            zamrozenieNaBazie.remove(klucz);
        }
        // Normy NIE są kasowane — to wiedza o tym, ile się czego zwykle sprzedaje,
        // i nie ma powodu jej tracić. Resetujemy tylko ceny.
        zapiszStan();
        plugin.getLogger().info("Ceny dynamiczne: globalny reset do cen bazowych.");
        Bukkit.broadcast(net.kyori.adventure.text.Component.text(
                "Ceny w sklepie wróciły do wartości bazowych!",
                net.kyori.adventure.text.format.NamedTextColor.GOLD));
    }

    /** Wywołaj w onDisable(). */
    public void zamknij() {
        zapiszStan();
        statystyki.zapisz();
        saver.zamknij();
        Bukkit.getServicesManager().unregister(CenyService.class, this);
    }

    /** Ręczny reset — do komendy administracyjnej. */
    public void wymusReset() {
        resetujWszystko();
    }

    /** Ustawia mnożnik ręcznie. Przycinany do dozwolonych granic. */
    public void ustawMnoznik(String klucz, double wartosc) {
        mnozniki.put(klucz, Math.max(M_MIN, Math.min(M_MAX, wartosc)));
        // Czyścimy stan pomocniczy, żeby item startował "od zera" —
        // inaczej zaraz po ustawieniu mógłby wskoczyć w niedokończoną suszę.
        licznikSuszy.remove(klucz);
        zamrozonyProg.remove(klucz);
        zamrozenieNaBazie.remove(klucz);
        zapiszStan();
    }

    /** Reset pojedynczego itemu do ceny bazowej. */
    public void resetujItem(String klucz) {
        ustawMnoznik(klucz, 1.0);
    }

    /** Ile cykli zostało do najbliższego globalnego resetu. */
    public int cykliDoResetu() {
        return CYKLI_DO_RESETU - cykliOdResetu;
    }

    /** Norma sprzedaży itemu — do wyświetlenia w /@sklep info. */
    public double getNorma(String klucz) {
        return normy.getOrDefault(klucz, 0.0);
    }

    /** Ile cykli trwa obecna susza. */
    public int getLicznikSuszy(String klucz) {
        return licznikSuszy.getOrDefault(klucz, 0);
    }

    /**
     * Ustawia mnożnik i BLOKUJE go — cykl korekty przestaje go ruszać.
     * Do eventów i awaryjnych korekt.
     */
    public void zablokujMnoznik(String klucz, double wartosc) {
        mnozniki.put(klucz, Math.max(M_MIN, Math.min(M_MAX, wartosc)));
        zablokowane.add(klucz);
        // Stan pomocniczy czyścimy - po odblokowaniu item ma startować od zera,
        // a nie wskakiwać w niedokończoną suszę sprzed eventu.
        licznikSuszy.remove(klucz);
        zamrozonyProg.remove(klucz);
        zamrozenieNaBazie.remove(klucz);
        zapiszStan();
    }

    /**
     * Kończy event: zdejmuje blokadę i NATYCHMIAST przywraca cenę bazową.
     *
     * Powrót do 1.0 od razu, a nie stopniowo, bo inaczej po ogłoszeniu końca
     * eventu cena przez kolejne ~3 godziny byłaby jeszcze podwyższona - event
     * formalnie nie trwa, a ceny eventowe wciąż obowiązują. To myli graczy.
     */
    public void odblokujMnoznik(String klucz) {
        zablokowane.remove(klucz);
        mnozniki.put(klucz, 1.0);
        // Stan pomocniczy czyścimy, żeby item startował od zera - inaczej
        // mógłby od razu wskoczyć w suszę naliczoną jeszcze przed eventem.
        licznikSuszy.remove(klucz);
        zamrozonyProg.remove(klucz);
        zamrozenieNaBazie.remove(klucz);
        zapiszStan();
    }

    public boolean czyZablokowany(String klucz) {
        return zablokowane.contains(klucz);
    }

    /** Lista zablokowanych itemów wraz z ich mnożnikami. */
    public Map<String, Double> getZablokowane() {
        Map<String, Double> wynik = new HashMap<>();
        for (String k : zablokowane) wynik.put(k, mnozniki.getOrDefault(k, 1.0));
        return wynik;
    }

    // =========================================================================
    //  CenyService — cienki interfejs dla innych modułów (patrz HUD)
    // =========================================================================

    @Override
    public Odchylenie najwiekszeOdchylenie() {
        String najlepszyKlucz = null;
        double najwiekszeOdchylenie = 0.02;   // próg - poniżej tego to "w normie"

        for (var wpis : mnozniki.entrySet()) {
            double odchylenie = Math.abs(wpis.getValue() - 1.0);
            if (odchylenie > najwiekszeOdchylenie) {
                najwiekszeOdchylenie = odchylenie;
                najlepszyKlucz = wpis.getKey();
            }
        }
        if (najlepszyKlucz == null) return null;

        // Nazwa wyswietlana z cennika, nie surowy klucz (COBBLESTONE -> Bruk).
        String nazwa = nazwaWyswietlana(najlepszyKlucz);
        return new Odchylenie(nazwa, mnozniki.get(najlepszyKlucz));
    }

    @Override
    public int dniDoResetu() {
        int cykliZostalo = CYKLI_DO_RESETU - cykliOdResetu;
        return Math.max(0, cykliZostalo / 24);   // MINUT_NA_CYKL=60 -> 24 cykle = 1 dzień
    }

    @Override
    public List<String> getZablokowaneNazwy() {
        return zablokowane.stream().map(this::nazwaWyswietlana).toList();
    }

    /** Deleguje do ShopManager.nazwaWyswietlana() - tam siedzi sklepConfig z "display-name". */
    private String nazwaWyswietlana(String klucz) {
        return shopManager.nazwaWyswietlana(klucz);
    }
}
