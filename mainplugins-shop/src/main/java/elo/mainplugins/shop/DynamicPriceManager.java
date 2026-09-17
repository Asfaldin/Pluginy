package elo.mainplugins.shop;

import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.shop.model.DynamicSettings;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Mnożniki cen skupu reagujące na obrót. Ustawienia (włączone, cykl, granice, reset) z shop.yml;
 * reszta strojenia zostaje tutaj. Stan w prices.yml, klucze = ShopItem.key().
 *
 * Wszystko liczone jest na SZTUKACH, nie na pieniądzach, i każdy item porównywany
 * jest wyłącznie SAM ZE SOBĄ. Gdyby porównywać obrót w pieniądzach do wspólnego
 * progu, tanie itemy (bruk) nigdy nie zaważyłyby tyle, co drogie (diament).
 */
public class DynamicPriceManager {

    /** Najmocniej odchylony item - pod wskazówkę HUD "co teraz warto sprzedać". */
    public record Deviation(String key, String name, double multiplier) {}

    // =========================================================================
    //  STROJENIE (bez zmian względem dawnej wersji)
    // =========================================================================

    /** Maksymalny spadek w jednym cyklu przy standardowej cenie (mnożnik = 1.0). */
    private static final double MAX_SPADEK = 0.05;

    /** Ile razy mocniejszy jest spadek, gdy mnożnik stoi na samym szczycie. */
    private static final double MNOZNIK_SPADKU_NA_SZCZYCIE = 4.2;

    /** Jaka część pozostałego dystansu do 1.0 jest odrabiana w jednym cyklu ciszy. */
    private static final double TEMPO_POWROTU_Z_DOLU = 0.80;

    /** O ile rośnie mnożnik na cykl po rozpoczęciu wzrostu. */
    private static final double TEMPO_WZROSTU = 0.125;

    /** Sprzedaż poniżej tego ułamka normy liczy się jako "prawie cisza". */
    private static final double PROG_CISZY = 0.10;

    /** Ile cykli prawie ciszy, zanim mnożnik zacznie rosnąć ponad 1.0. */
    private static final int CYKLI_DO_WZROSTU = 2;

    /** Ile cykli mnożnik stoi zamrożony na 1.0 po zejściu z góry. */
    private static final int CYKLI_ZAMROZENIA = 2;

    /** Jak szybko norma zapomina stare cykle. 0.02 ≈ tydzień historii przy cyklu godzinnym. */
    private static final double TEMPO_UCZENIA_NORMY = 0.02;

    // =========================================================================
    //  STAN
    // =========================================================================

    private final Map<String, Double> mnozniki = new ConcurrentHashMap<>();
    private final Map<String, Double> normy = new ConcurrentHashMap<>();
    private final Map<String, Integer> obrotCyklu = new ConcurrentHashMap<>();
    private final Map<String, Integer> licznikSuszy = new ConcurrentHashMap<>();
    /** Próg suszy zamrożony w chwili jej rozpoczęcia (inaczej kurczyłby się razem z normą). */
    private final Map<String, Double> zamrozonyProg = new ConcurrentHashMap<>();
    private final Map<String, Integer> zamrozenieNaBazie = new ConcurrentHashMap<>();
    /** Itemy z ręcznie zablokowanym mnożnikiem (eventy) - cykl ich nie rusza. */
    private final Set<String> zablokowane = ConcurrentHashMap.newKeySet();
    /** Koniec eventu (czas systemowy w ms). Brak wpisu = event bez końca, trwa aż do /@shop event off. */
    private final Map<String, Long> koniecEventu = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final FileConfiguration config;
    private final AsyncConfigSaver saver;
    private final ShopStats stats;
    private final Function<String, String> nameOf;
    private final Runnable onGlobalReset;
    private volatile DynamicSettings settings;
    private BukkitTask task;
    private int cykliOdResetu = 0;

    /**
     * @param nameOf        klucz -> nazwa do pokazania graczom (HUD, eventy)
     * @param onGlobalReset wiadomość do graczy po globalnym resecie (teksty są w lang)
     */
    public DynamicPriceManager(Plugin plugin, DynamicSettings settings, ShopStats stats,
                               Function<String, String> nameOf, Runnable onGlobalReset) {
        this.plugin = plugin;
        this.stats = stats;
        this.nameOf = nameOf;
        this.onGlobalReset = onGlobalReset;
        File plik = new File(plugin.getDataFolder(), "prices.yml");
        this.config = YamlConfiguration.loadConfiguration(plik);
        wczytaj();
        this.saver = new AsyncConfigSaver(plugin, config, plik, 60);
        applySettings(settings);
    }

    /** Nowe ustawienia (start, /@shop reload) - planuje cykl na nowo tylko, gdy trzeba. */
    public void applySettings(DynamicSettings nowe) {
        DynamicSettings stare = this.settings;
        this.settings = nowe;
        boolean przeplanuj = stare == null || stare.enabled() != nowe.enabled() || stare.cycleMinutes() != nowe.cycleMinutes();
        if (!przeplanuj) return;
        if (task != null) task.cancel();
        task = null;
        if (nowe.enabled()) {
            long ticki = nowe.cycleMinutes() * 60L * 20L;
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::wykonajCykl, ticki, ticki);
        }
    }

    public boolean enabled() {
        return settings.enabled();
    }

    private int cykliDoResetuLacznie() {
        return Math.max(1, settings.resetDays() * 1440 / settings.cycleMinutes());
    }

    // =========================================================================
    //  ZAPIS I ODCZYT
    // =========================================================================

    private void wczytaj() {
        cykliOdResetu = config.getInt("_meta.cycles-since-reset", 0);
        for (String klucz : config.getKeys(false)) {
            if (klucz.equals("_meta")) continue;
            mnozniki.put(klucz, config.getDouble(klucz + ".multiplier", 1.0));
            normy.put(klucz, config.getDouble(klucz + ".norm", 0.0));
            licznikSuszy.put(klucz, config.getInt(klucz + ".drought", 0));
            zamrozenieNaBazie.put(klucz, config.getInt(klucz + ".frozen", 0));
            double prog = config.getDouble(klucz + ".drought-threshold", -1);
            if (prog >= 0) zamrozonyProg.put(klucz, prog);
            if (config.getBoolean(klucz + ".locked", false)) zablokowane.add(klucz);
            long doKiedy = config.getLong(klucz + ".event-until", 0L);
            if (doKiedy > 0) koniecEventu.put(klucz, doKiedy);
        }
    }

    private void zapiszStan() {
        config.set("_meta.cycles-since-reset", cykliOdResetu);
        for (String klucz : mnozniki.keySet()) {
            config.set(klucz + ".multiplier", zaokr(mnozniki.get(klucz)));
            config.set(klucz + ".norm", zaokr(normy.getOrDefault(klucz, 0.0)));
            config.set(klucz + ".drought", licznikSuszy.getOrDefault(klucz, 0));
            config.set(klucz + ".frozen", zamrozenieNaBazie.getOrDefault(klucz, 0));
            Double prog = zamrozonyProg.get(klucz);
            config.set(klucz + ".drought-threshold", prog != null ? zaokr(prog) : null);
            config.set(klucz + ".locked", zablokowane.contains(klucz) ? true : null);
            Long doKiedy = koniecEventu.get(klucz);
            config.set(klucz + ".event-until", doKiedy != null && doKiedy > 0 ? doKiedy : null);
        }
        saver.oznaczZmiane();
    }

    private static double zaokr(double x) {
        return Math.round(x * 1000.0) / 1000.0;
    }

    // =========================================================================
    //  API DLA SKLEPU
    // =========================================================================

    /** Rejestruje sprzedaż do sklepu (sztuki). */
    public void zarejestrujSprzedaz(String klucz, int sztuk) {
        if (settings.enabled()) obrotCyklu.merge(klucz, sztuk, Integer::sum);
    }

    /** Aktualny mnożnik; przy wyłączonych cenach dynamicznych zawsze 1.0. */
    public double getMnoznik(String klucz) {
        return settings.enabled() ? mnozniki.getOrDefault(klucz, 1.0) : 1.0;
    }

    /** Strzałka do GUI: 1 = cena wyższa niż zwykle, -1 = niższa, 0 = normalna. */
    public int kierunekZmiany(String klucz) {
        double m = getMnoznik(klucz);
        if (m > 1.02) return 1;
        if (m < 0.98) return -1;
        return 0;
    }

    // =========================================================================
    //  CYKL
    // =========================================================================

    private void wykonajCykl() {
        // Reset globalny co reset-days. Bez niego farmowalne itemy utknęłyby na dnie na zawsze.
        if (++cykliOdResetu >= cykliDoResetuLacznie()) {
            resetujWszystko();
            return;
        }
        // Obrót zdejmujemy jednym ruchem - transakcje w trakcie liczenia idą do następnego cyklu.
        Map<String, Integer> obrot = new HashMap<>(obrotCyklu);
        obrotCyklu.clear();

        Set<String> wszystkie = new HashSet<>(mnozniki.keySet());
        wszystkie.addAll(obrot.keySet());
        for (String klucz : wszystkie) przetworzItem(klucz, obrot.getOrDefault(klucz, 0));
        zapiszStan();
    }

    private void przetworzItem(String klucz, int sprzedano) {
        double min = settings.minMultiplier();
        double max = settings.maxMultiplier();

        // Zablokowany ręcznie - cykl go nie rusza, ale norma i statystyki się uczą.
        if (zablokowane.contains(klucz)) {
            if (sprzedano > 0) {
                double norma = normy.getOrDefault(klucz, 0.0);
                if (norma < 1.0) normy.put(klucz, (double) sprzedano);
                else normy.put(klucz, norma + (sprzedano - norma) * TEMPO_UCZENIA_NORMY);
            }
            stats.zapiszCykl(klucz, mnozniki.getOrDefault(klucz, 1.0));
            return;
        }

        double norma = normy.getOrDefault(klucz, 0.0);
        // Nowy item: pierwsza sprzedaż tylko ustawia normę.
        if (norma < 1.0) {
            if (sprzedano > 0) normy.put(klucz, (double) sprzedano);
            mnozniki.putIfAbsent(klucz, 1.0);
            return;
        }

        double m = mnozniki.getOrDefault(klucz, 1.0);
        int susza = licznikSuszy.getOrDefault(klucz, 0);
        int zamrozenie = zamrozenieNaBazie.getOrDefault(klucz, 0);
        double prog = zamrozonyProg.containsKey(klucz) ? zamrozonyProg.get(klucz) : norma * PROG_CISZY;

        if (sprzedano < prog) {
            // ---- cisza ----
            zamrozonyProg.putIfAbsent(klucz, norma * PROG_CISZY);
            susza++;
            if (m < 1.0) {
                m += (1.0 - m) * TEMPO_POWROTU_Z_DOLU;
                if (m > 0.995) m = 1.0;
            } else if (zamrozenie > 0) {
                zamrozenie--;
            } else if (susza >= CYKLI_DO_WZROSTU) {
                m += TEMPO_WZROSTU;
            }
        } else {
            // ---- sprzedaż ----
            susza = Math.max(0, susza - 1);
            zamrozonyProg.remove(klucz);
            if (m > 1.0) zamrozenie = CYKLI_ZAMROZENIA;
            m -= policzSpadek(m, sprzedano, norma, max);
        }

        normy.put(klucz, norma + (sprzedano - norma) * TEMPO_UCZENIA_NORMY);
        mnozniki.put(klucz, Math.max(min, Math.min(max, m)));
        licznikSuszy.put(klucz, susza);
        zamrozenieNaBazie.put(klucz, zamrozenie);
        stats.zapiszCykl(klucz, mnozniki.get(klucz));
    }

    /** Siła spadku = WIĘKSZY z efektów ilości i wysokości (nie suma - inaczej jedna duża sprzedaż zrzuciłaby cenę na dno). */
    private double policzSpadek(double mnoznik, int sprzedano, double norma, double max) {
        double stosunek = sprzedano / Math.max(1.0, norma);
        double efektIlosci = 0.0;
        if (stosunek > 1.0) efektIlosci = Math.min((Math.log(stosunek) / Math.log(2)) * MAX_SPADEK, MAX_SPADEK);
        double efektWysokosci = 0.0;
        if (mnoznik > 1.0 && max > 1.0) {
            double ponad = (mnoznik - 1.0) / (max - 1.0);
            efektWysokosci = MAX_SPADEK * (1.0 + ponad * (MNOZNIK_SPADKU_NA_SZCZYCIE - 1.0));
        }
        return Math.max(efektIlosci, efektWysokosci);
    }

    private void resetujWszystko() {
        cykliOdResetu = 0;
        // Eventy (zablokowane) reset pomija; normy zostają - to wiedza o rynku.
        for (String klucz : mnozniki.keySet()) {
            if (zablokowane.contains(klucz)) continue;
            mnozniki.put(klucz, 1.0);
            licznikSuszy.remove(klucz);
            zamrozonyProg.remove(klucz);
            zamrozenieNaBazie.remove(klucz);
        }
        zapiszStan();
        plugin.getLogger().info("Dynamic prices: global reset to base prices.");
        onGlobalReset.run();
    }

    public void zamknij() {
        if (task != null) task.cancel();
        zapiszStan();
        saver.zamknij();
    }

    // =========================================================================
    //  ADMIN
    // =========================================================================

    public void wymusReset() {
        resetujWszystko();
    }

    public void ustawMnoznik(String klucz, double wartosc) {
        mnozniki.put(klucz, Math.max(settings.minMultiplier(), Math.min(settings.maxMultiplier(), wartosc)));
        licznikSuszy.remove(klucz);
        zamrozonyProg.remove(klucz);
        zamrozenieNaBazie.remove(klucz);
        zapiszStan();
    }

    public void resetujItem(String klucz) {
        ustawMnoznik(klucz, 1.0);
    }

    public double getNorma(String klucz) {
        return normy.getOrDefault(klucz, 0.0);
    }

    public int getLicznikSuszy(String klucz) {
        return licznikSuszy.getOrDefault(klucz, 0);
    }

    /** Ustawia mnożnik i blokuje go (event bez końca). */
    public void zablokujMnoznik(String klucz, double wartosc) {
        zablokujMnoznik(klucz, wartosc, 0L);
    }

    /** Ustawia mnożnik i blokuje go. doKiedy = czas systemowy w ms, 0 = event bez końca. */
    public void zablokujMnoznik(String klucz, double wartosc, long doKiedy) {
        ustawMnoznik(klucz, wartosc);
        zablokowane.add(klucz);
        if (doKiedy > 0) koniecEventu.put(klucz, doKiedy);
        else koniecEventu.remove(klucz);
        zapiszStan();
    }

    /** Koniec eventu: blokada zdjęta i od razu cena bazowa (inaczej ceny eventowe trzymałyby się godzinami). */
    public void odblokujMnoznik(String klucz) {
        zablokowane.remove(klucz);
        koniecEventu.remove(klucz);
        ustawMnoznik(klucz, 1.0);
    }

    /** Ile ms zostało do końca eventu; null = event bez końca albo przedmiot nie ma eventu. */
    public Long zostaloEventu(String klucz) {
        Long doKiedy = koniecEventu.get(klucz);
        if (doKiedy == null || !zablokowane.contains(klucz)) return null;
        return Math.max(0L, doKiedy - System.currentTimeMillis());
    }

    /** Kończy wszystkie trwające eventy naraz. Zwraca ich klucze. */
    public List<String> zakonczWszystkieEventy() {
        List<String> wszystkie = new ArrayList<>(zablokowane);
        for (String klucz : wszystkie) odblokujMnoznik(klucz);
        if (!wszystkie.isEmpty()) zapiszStan();
        return wszystkie;
    }

    /** Kończy eventy, którym minął czas. Zwraca klucze zakończonych - do ogłoszenia na czacie. */
    public List<String> zakonczWygasle() {
        long teraz = System.currentTimeMillis();
        List<String> wygasle = new ArrayList<>();
        for (Map.Entry<String, Long> e : koniecEventu.entrySet()) {
            if (e.getValue() <= teraz) wygasle.add(e.getKey());
        }
        for (String klucz : wygasle) odblokujMnoznik(klucz);
        if (!wygasle.isEmpty()) zapiszStan();
        return wygasle;
    }

    public boolean czyZablokowany(String klucz) {
        return zablokowane.contains(klucz);
    }

    public Map<String, Double> getZablokowane() {
        Map<String, Double> wynik = new HashMap<>();
        for (String k : zablokowane) wynik.put(k, mnozniki.getOrDefault(k, 1.0));
        return wynik;
    }

    public Map<String, Double> getWszystkieMnozniki() {
        return new HashMap<>(mnozniki);
    }

    // =========================================================================
    //  DLA HUD (placeholdery)
    // =========================================================================

    /** Najmocniej odchylony item (w dowolną stronę), null gdy wszystko w normie albo ceny wyłączone. */
    public Deviation najwiekszeOdchylenie() {
        if (!settings.enabled()) return null;
        String najlepszy = null;
        double odchylenie = 0.02;
        for (var e : mnozniki.entrySet()) {
            double o = Math.abs(e.getValue() - 1.0);
            if (o > odchylenie) {
                odchylenie = o;
                najlepszy = e.getKey();
            }
        }
        return najlepszy == null ? null : new Deviation(najlepszy, nameOf.apply(najlepszy), mnozniki.get(najlepszy));
    }

    /** Ile dni do globalnego resetu cen. */
    public int dniDoResetu() {
        int cykliZostalo = cykliDoResetuLacznie() - cykliOdResetu;
        return Math.max(0, cykliZostalo * settings.cycleMinutes() / 1440);
    }

    public List<String> getZablokowaneNazwy() {
        return zablokowane.stream().map(nameOf).toList();
    }
}
