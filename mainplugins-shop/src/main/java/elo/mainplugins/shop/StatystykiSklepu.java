package elo.mainplugins.shop;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zbiera długoterminowe statystyki sprzedaży, żeby dało się na ich podstawie
 * korygować ceny bazowe w categories/*.yml.
 *
 * Dane trzymane są w statystyki-sklepu.yml (bo tam wygodnie dopisywać
 * przyrostowo), a raport wypluwany do statystyki-sklepu.csv (bo to otwiera
 * się w Excelu i da się posortować).
 */
public class StatystykiSklepu {

    /** Poniżej tego mnożnika uznajemy, że item "siedzi na dnie". */
    private static final double PROG_DNA = 0.55;
    /** Powyżej tego mnożnika uznajemy, że item "siedzi na szczycie". */
    private static final double PROG_SZCZYTU = 1.45;

    /**
     * Ile procent czasu na dnie/szczycie wystarczy, żeby zasugerować zmianę
     * ceny bazowej. 60% to sporo — nie chcemy sugerować zmiany przy chwilowych
     * wahnięciach, tylko przy trwałym trendzie.
     */
    private static final double PROG_SUGESTII = 0.60;

    /** Ile cykli musi minąć, zanim sugestia w ogóle ma sens statystyczny. */
    private static final int MIN_CYKLI_DO_SUGESTII = 168;   // tydzień przy cyklu godzinnym

    private static class Wpis {
        // ---- kumulowane od zawsze (bez zmian) ----
        long sztukLacznie;
        long wyplaconoLacznie;
        long transakcji;
        long cykliLacznie;
        long cykliNaDnie;
        long cykliNaSzczycie;
        double sumaMnoznikow;      // do policzenia średniej
        long najwiekszaTransakcja;

        // ---- liczniki dobowe, zerowane o północy ----
        long sztukDzis;
        long wyplaconoDzis;
        long transakcjiDzis;
    }

    private final Map<String, Wpis> dane = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final File plikYml;
    private final File plikCsv;
    private final FileConfiguration config;

    /** Data, dla której liczą się obecne liczniki dobowe. Zmiana = czas na snapshot. */
    private String dzisiejszaData;

    private final File folderArchiwum;

    public StatystykiSklepu(Plugin plugin) {
        this.plugin = plugin;
        this.plikYml = new File(plugin.getDataFolder(), "statystyki-sklepu.yml");
        this.plikCsv = new File(plugin.getDataFolder(), "statystyki-sklepu.csv");
        this.config = YamlConfiguration.loadConfiguration(plikYml);
        wczytaj();

        this.folderArchiwum = new File(plugin.getDataFolder(), "archiwum-statystyk");
        if (!folderArchiwum.exists()) folderArchiwum.mkdirs();

        // Data zapisana w pliku, a nie bieżąca — jeśli serwer stał wyłączony
        // przez dobę, chcemy to wykryć i zamknąć poprzedni dzień, a nie
        // po cichu doliczyć wczorajszy ruch do dzisiejszego.
        this.dzisiejszaData = config.getString("_meta.data", dzisiejszaDataSystemowa());
    }

    private static String dzisiejszaDataSystemowa() {
        return java.time.LocalDate.now().toString();   // format RRRR-MM-DD
    }

    /**
     * Sprawdza, czy zmienił się dzień. Jeśli tak — zapisuje snapshot minionej
     * doby do archiwum i zeruje liczniki dobowe.
     *
     * Woła się raz na cykl, więc rozdzielczość to jedna godzina — wystarczająco
     * dokładnie, żeby snapshot powstał w ciągu godziny po północy.
     */
    private void sprawdzZmianeDoby() {
        String teraz = dzisiejszaDataSystemowa();
        if (teraz.equals(dzisiejszaData)) return;

        zapiszSnapshotDobowy(dzisiejszaData);

        for (Wpis w : dane.values()) {
            w.sztukDzis = 0;
            w.wyplaconoDzis = 0;
            w.transakcjiDzis = 0;
        }
        dzisiejszaData = teraz;
        plugin.getLogger().info("Statystyki sklepu: zamknieto dobe, snapshot zapisany.");
    }

    /**
     * Zapisuje CSV z ruchem z jednej doby. Itemy bez żadnego ruchu pomijamy —
     * inaczej plik miałby 330 wierszy, z czego 300 pustych.
     */
    private void zapiszSnapshotDobowy(String data) {
        List<Map.Entry<String, Wpis>> zRuchem = new ArrayList<>();
        for (Map.Entry<String, Wpis> e : dane.entrySet()) {
            if (e.getValue().sztukDzis > 0) zRuchem.add(e);
        }
        if (zRuchem.isEmpty()) return;

        zRuchem.sort((a, b) -> Long.compare(b.getValue().sztukDzis, a.getValue().sztukDzis));

        List<String> linie = new ArrayList<>();
        linie.add(String.join(";", "item", "sztuk", "wyplacono", "transakcji", "sredni_mnoznik"));
        for (Map.Entry<String, Wpis> e : zRuchem) {
            Wpis w = e.getValue();
            double sredni = w.cykliLacznie > 0 ? w.sumaMnoznikow / w.cykliLacznie : 1.0;
            linie.add(String.join(";",
                    e.getKey(),
                    String.valueOf(w.sztukDzis),
                    String.valueOf(w.wyplaconoDzis),
                    String.valueOf(w.transakcjiDzis),
                    fmt(sredni)));
        }

        File plik = new File(folderArchiwum, "statystyki-" + data + ".csv");
        final String tresc = String.join("\n", linie) + "\n";
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> zapiszPlik(plik.toPath(), tresc));
    }

    private void wczytaj() {
        for (String klucz : config.getKeys(false)) {
            if (klucz.equals("_meta")) continue;
            Wpis w = new Wpis();
            w.sztukLacznie = config.getLong(klucz + ".sztuk", 0);
            w.wyplaconoLacznie = config.getLong(klucz + ".wyplacono", 0);
            w.transakcji = config.getLong(klucz + ".transakcji", 0);
            w.cykliLacznie = config.getLong(klucz + ".cykli", 0);
            w.cykliNaDnie = config.getLong(klucz + ".cykli-na-dnie", 0);
            w.cykliNaSzczycie = config.getLong(klucz + ".cykli-na-szczycie", 0);
            w.sumaMnoznikow = config.getDouble(klucz + ".suma-mnoznikow", 0);
            w.najwiekszaTransakcja = config.getLong(klucz + ".max-transakcja", 0);
            w.sztukDzis = config.getLong(klucz + ".sztuk-dzis", 0);
            w.wyplaconoDzis = config.getLong(klucz + ".wyplacono-dzis", 0);
            w.transakcjiDzis = config.getLong(klucz + ".transakcji-dzis", 0);
            dane.put(klucz, w);
        }
        plugin.getLogger().info("Statystyki sklepu: wczytano " + dane.size() + " pozycji.");
    }

    // =========================================================================
    //  ZBIERANIE DANYCH
    // =========================================================================

    /**
     * Woła się przy każdej udanej sprzedaży do sklepu.
     *
     * @param klucz identyfikator itemu (custom-id albo materiał)
     * @param sztuk ile sztuk sprzedano w tej transakcji
     * @param wyplacono ile $ gracz dostał
     */
    public void zapiszTransakcje(String klucz, int sztuk, long wyplacono) {
        Wpis w = dane.computeIfAbsent(klucz, k -> new Wpis());
        w.sztukLacznie += sztuk;
        w.wyplaconoLacznie += wyplacono;
        w.transakcji++;
        if (sztuk > w.najwiekszaTransakcja) w.najwiekszaTransakcja = sztuk;

        w.sztukDzis += sztuk;
        w.wyplaconoDzis += wyplacono;
        w.transakcjiDzis++;
    }

    /**
     * Woła się raz na cykl dla każdego itemu — to stąd bierze się wiedza
     * o tym, ile czasu item spędza na dnie i na szczycie.
     */
    public void zapiszCykl(String klucz, double mnoznik) {
        Wpis w = dane.computeIfAbsent(klucz, k -> new Wpis());
        w.cykliLacznie++;
        w.sumaMnoznikow += mnoznik;
        if (mnoznik <= PROG_DNA) w.cykliNaDnie++;
        else if (mnoznik >= PROG_SZCZYTU) w.cykliNaSzczycie++;
    }

    // =========================================================================
    //  ZAPIS
    // =========================================================================

    public void zapisz() {
        sprawdzZmianeDoby();

        config.set("_meta.data", dzisiejszaData);
        for (Map.Entry<String, Wpis> e : dane.entrySet()) {
            String k = e.getKey();
            Wpis w = e.getValue();
            config.set(k + ".sztuk", w.sztukLacznie);
            config.set(k + ".wyplacono", w.wyplaconoLacznie);
            config.set(k + ".transakcji", w.transakcji);
            config.set(k + ".cykli", w.cykliLacznie);
            config.set(k + ".cykli-na-dnie", w.cykliNaDnie);
            config.set(k + ".cykli-na-szczycie", w.cykliNaSzczycie);
            config.set(k + ".suma-mnoznikow", Math.round(w.sumaMnoznikow * 100.0) / 100.0);
            config.set(k + ".max-transakcja", w.najwiekszaTransakcja);
            config.set(k + ".sztuk-dzis", w.sztukDzis);
            config.set(k + ".wyplacono-dzis", w.wyplaconoDzis);
            config.set(k + ".transakcji-dzis", w.transakcjiDzis);
        }
        // Serializacja na głównym wątku (config nie jest thread-safe),
        // zapis na dysk asynchronicznie — ten sam wzorzec co AsyncConfigSaver.
        final String tresc = config.saveToString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            zapiszPlik(plikYml.toPath(), tresc);
            zapiszPlik(plikCsv.toPath(), zbudujCsv());
        });
    }

    private void zapiszPlik(Path docelowy, String tresc) {
        try {
            Path tmp = docelowy.resolveSibling(docelowy.getFileName() + ".tmp");
            Files.writeString(tmp, tresc, StandardCharsets.UTF_8);
            Files.move(tmp, docelowy, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie mozna zapisac " + docelowy.getFileName() + ": " + e.getMessage());
        }
    }

    // =========================================================================
    //  RAPORT CSV
    // =========================================================================

    /**
     * Buduje raport gotowy do otwarcia w Excelu.
     *
     * Separator średnikiem, nie przecinkiem — polski Excel domyślnie tak czyta
     * i nie trzeba nic importować ręcznie. Liczby dziesiętne z przecinkiem
     * z tego samego powodu.
     */
    private String zbudujCsv() {
        List<String> linie = new ArrayList<>();
        linie.add(String.join(";",
                "item", "sztuk_24h", "wyplacono_24h", "transakcji_24h",
                "sztuk_lacznie", "wyplacono_lacznie", "transakcji",
                "srednia_transakcja", "najwieksza_transakcja",
                "cykli", "proc_na_dnie", "proc_na_szczycie",
                "sredni_mnoznik", "SUGESTIA"));

        // Sortowanie po wolumenie malejąco — najczęściej sprzedawane na górze,
        // bo to one najmocniej ważą na ekonomii serwera.
        List<Map.Entry<String, Wpis>> posortowane = new ArrayList<>(dane.entrySet());
        posortowane.sort((a, b) -> Long.compare(b.getValue().sztukLacznie, a.getValue().sztukLacznie));

        for (Map.Entry<String, Wpis> e : posortowane) {
            Wpis w = e.getValue();
            double procDna = w.cykliLacznie > 0 ? (double) w.cykliNaDnie / w.cykliLacznie : 0;
            double procSzczytu = w.cykliLacznie > 0 ? (double) w.cykliNaSzczycie / w.cykliLacznie : 0;
            double sredniMnoznik = w.cykliLacznie > 0 ? w.sumaMnoznikow / w.cykliLacznie : 1.0;
            double sredniaTrans = w.transakcji > 0 ? (double) w.sztukLacznie / w.transakcji : 0;

            linie.add(String.join(";",
                    e.getKey(),
                    String.valueOf(w.sztukDzis),
                    String.valueOf(w.wyplaconoDzis),
                    String.valueOf(w.transakcjiDzis),
                    String.valueOf(w.sztukLacznie),
                    String.valueOf(w.wyplaconoLacznie),
                    String.valueOf(w.transakcji),
                    fmt(sredniaTrans),
                    String.valueOf(w.najwiekszaTransakcja),
                    String.valueOf(w.cykliLacznie),
                    fmt(procDna * 100),
                    fmt(procSzczytu * 100),
                    fmt(sredniMnoznik),
                    sugestia(w, procDna, procSzczytu)));
        }
        return String.join("\n", linie) + "\n";
    }

    /** Przecinek zamiast kropki — polski Excel inaczej nie rozpozna liczby. */
    private static String fmt(double x) {
        return String.format("%.2f", x).replace('.', ',');
    }

    /**
     * Gotowa podpowiedź, co zrobić z ceną bazową.
     *
     * To jest sedno całego pliku — reszta kolumn to dane surowe, a ta jedna
     * mówi wprost, gdzie zajrzeć.
     */
    private static String sugestia(Wpis w, double procDna, double procSzczytu) {
        if (w.cykliLacznie < MIN_CYKLI_DO_SUGESTII) return "za malo danych";
        if (w.transakcji == 0) return "NIKT NIE SPRZEDAJE - sprawdz czy cena nie za niska";
        if (procDna >= PROG_SUGESTII) return "OBNIZ CENE BAZOWA - stale na dnie";
        if (procSzczytu >= PROG_SUGESTII) return "PODNIES CENE BAZOWA - stale na szczycie";
        return "ok";
    }

    // =========================================================================
    //  DANE DLA KOMENDY W GRZE
    // =========================================================================

    /** Rekord na potrzeby wyświetlania w grze. */
    public record PozycjaTopki(String item, long sztuk, long wyplacono, double mnoznik) {}

    /**
     * Najczęściej sprzedawane itemy z bieżącej doby.
     *
     * @param limit ile pozycji zwrócić
     * @param mnozniki mapa aktualnych mnożników z DynamicPriceManager
     */
    public List<PozycjaTopki> getTopDzis(int limit, Map<String, Double> mnozniki) {
        List<Map.Entry<String, Wpis>> lista = new ArrayList<>();
        for (Map.Entry<String, Wpis> e : dane.entrySet()) {
            if (e.getValue().sztukDzis > 0) lista.add(e);
        }
        lista.sort((a, b) -> Long.compare(b.getValue().sztukDzis, a.getValue().sztukDzis));

        List<PozycjaTopki> wynik = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, lista.size()); i++) {
            Map.Entry<String, Wpis> e = lista.get(i);
            wynik.add(new PozycjaTopki(
                    e.getKey(),
                    e.getValue().sztukDzis,
                    e.getValue().wyplaconoDzis,
                    mnozniki.getOrDefault(e.getKey(), 1.0)));
        }
        return wynik;
    }

    /** Podsumowanie całej doby — ile w sumie wypłacono graczom. */
    public long getWyplaconoDzis() {
        long suma = 0;
        for (Wpis w : dane.values()) suma += w.wyplaconoDzis;
        return suma;
    }

    /** Wymuszony snapshot — do komendy administracyjnej. */
    public void wymusSnapshot() {
        zapiszSnapshotDobowy(dzisiejszaData + "-reczny-" + System.currentTimeMillis());
    }
}
