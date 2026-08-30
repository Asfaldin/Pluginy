package elo.mainplugins.fishing;

import elo.mainplugins.core.util.AsyncConfigSaver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Statystyki rybackie graczy - suma zlowionych kg NA ZAWSZE (bez resetow/sezonow, patrz
 * rozmowa z userem 2026-08-29), osobisty rekord gracza i rekordy poszczegolnych gatunkow
 * (kto zlowil najciezszego Karpia itd. - patrz zanotujRekordGatunkuJesliNowy). Ten sam
 * wzorzec co EconomyManager w mainplugins-core: plaski YAML kluczowany UUID gracza,
 * buforowany zapis przez AsyncConfigSaver, pozycja w rankingu liczona przez zliczenie ilu
 * graczy ma wiecej - bez pelnego sortowania calej listy.
 *
 * PLIK jest CELOWO czytelny dla czlowieka - liczby zapisane jako normalne kg z przecinkiem
 * (np. "suma-kg: 143.7", nie "1437") i nick gracza obok UUID, zeby dalo sie recznie
 * podejrzec/poprawic (patrz rozmowa z userem 2026-08-29: chcial latwo edytowalny YAML).
 * To NIE wraca problemu z dryfem doubli przy sumowaniu (ten sam problem co zmusil
 * EconomyManager do przejscia na grosze) - kazda operacja odczytuje zapisana liczbe,
 * ZAOKRAGLA JA Z POWROTEM do dokladnej liczby calkowitej "dziesiatych kg" (Math.round),
 * dopiero na TEJ liczbie calkowitej robi dodawanie, i wynik zapisuje z powrotem jako kg.
 * Zaokraglenie w te i we w te przy KAZDYM kroku "resetuje" ewentualny mikroskopijny blad
 * zapisu double, wiec nic sie nie kumuluje bez wzgledu na to ile tysiecy razy to sie
 * powtorzy - w odroznieniu od trzymania samej sumy jako double i tylko do niej dodawania.
 */
public class FishingStatsManager {

    private final Plugin plugin;
    private final File plik;
    private final FileConfiguration config;
    private final AsyncConfigSaver saver;

    public FishingStatsManager(Plugin plugin) {
        this.plugin = plugin;
        this.plik = new File(plugin.getDataFolder(), "rybackie-statystyki.yml");
        if (!plik.exists()) {
            plik.getParentFile().mkdirs();
            try {
                plik.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Nie udalo sie utworzyc rybackie-statystyki.yml: " + e.getMessage());
            }
        }
        this.config = YamlConfiguration.loadConfiguration(plik);
        this.saver = new AsyncConfigSaver(plugin, config, plik, 30);
    }

    /** kg (double, do 1 miejsca po przecinku) -> dokladna liczba "dziesiatych kg" - patrz javadoc klasy. */
    private static long naDziesiete(double kg) {
        return Math.round(kg * 10.0);
    }

    /** Wywolywane po kazdym udanym polowie w lowisku - patrz FishingManager.nagrodaZaPolow. */
    public void zanotujPolow(UUID uuid, String nick, String customId, String nazwaGatunku, int wagaDziesieteKg) {
        String klucz = uuid.toString();

        long sumaDziesiete = naDziesiete(config.getDouble(klucz + ".suma-kg", 0.0)) + wagaDziesieteKg;
        config.set(klucz + ".suma-kg", sumaDziesiete / 10.0);

        long rekordDziesiete = naDziesiete(config.getDouble(klucz + ".rekord-kg", 0.0));
        if (wagaDziesieteKg > rekordDziesiete) {
            config.set(klucz + ".rekord-kg", wagaDziesieteKg / 10.0);
            config.set(klucz + ".rekord-gatunek", nazwaGatunku);
        }

        config.set(klucz + ".nick", nick); // czysto do czytelnosci pliku - patrz nickGracza

        // Indeks rybacki (patrz getIndeks/WpisIndeksu) - ile sztuk TEGO gatunku zlowil TEN
        // gracz i jego wlasny rekord wagi na TEN gatunek. Sama OBECNOSC wpisu pod danym
        // customId to jednoczesnie "odkrycie" tego gatunku w indeksie - patrz getIndeks,
        // ktora zwraca WYLACZNIE gatunki majace tu jakikolwiek wpis (rozmowa z userem
        // 2026-08-29: nieodkryte gatunki maja byc calkowicie niewidoczne, zero "???").
        String kluczGatunku = klucz + ".gatunki." + customId;
        int zlowionychSztuk = config.getInt(kluczGatunku + ".zlowionych", 0) + 1;
        config.set(kluczGatunku + ".zlowionych", zlowionychSztuk);
        config.set(kluczGatunku + ".nazwa", nazwaGatunku); // czytelnosc pliku - sam klucz to customId (np. FISH_KARP)

        long rekordGatunkuDziesiete = naDziesiete(config.getDouble(kluczGatunku + ".rekord-kg", 0.0));
        if (wagaDziesieteKg > rekordGatunkuDziesiete) {
            config.set(kluczGatunku + ".rekord-kg", wagaDziesieteKg / 10.0);
        }

        // Najlzejszy okaz TEGO gatunku zlowiony przez TEGO gracza (patrz WpisIndeksu.minKg /
        // GUI indeksu 2026-08-30) - "brak wpisu" (pierwszy polow tego gatunku) traktujemy
        // jako nieskonczonosc, zeby ta pierwsza waga zawsze wygrala jako nowe minimum.
        long minGatunkuDziesiete = config.contains(kluczGatunku + ".rekord-min-kg")
                ? naDziesiete(config.getDouble(kluczGatunku + ".rekord-min-kg"))
                : Long.MAX_VALUE;
        if (wagaDziesieteKg < minGatunkuDziesiete) {
            config.set(kluczGatunku + ".rekord-min-kg", wagaDziesieteKg / 10.0);
        }

        // Historia polowow (patrz getHistoria/WpisHistorii, Dziennik Polowow w GUI
        // 2026-08-30) - NAJNOWSZY na poczatku listy, obcinana do LIMIT_HISTORII zeby plik
        // nie rosl bez konca przy aktywnych rybakach (Dziennik i tak pokazuje tylko
        // "ostatnie" polowy, nie pelna historie na zawsze - w odroznieniu od sumy-kg/
        // rekordow wyzej, ktore ZAWSZE trzymamy w calosci).
        List<Map<?, ?>> historia = new ArrayList<>(config.getMapList(klucz + ".historia"));
        Map<String, Object> wpisHistorii = new LinkedHashMap<>();
        wpisHistorii.put("custom-id", customId);
        wpisHistorii.put("gatunek", nazwaGatunku);
        wpisHistorii.put("kg", wagaDziesieteKg / 10.0);
        wpisHistorii.put("czas", FORMAT_CZASU.format(LocalDateTime.now()));
        historia.add(0, wpisHistorii);
        if (historia.size() > LIMIT_HISTORII) historia = historia.subList(0, LIMIT_HISTORII);
        config.set(klucz + ".historia", historia);

        // Top 10 NAJCIEZSZYCH POJEDYNCZYCH POLOWOW na serwerze, dowolny gracz/gatunek
        // (patrz getTopPolowy, /rybtop w FishingManager.wyslijRanking) - user 2026-08-30
        // NIE chcial rankingu po sumie zlowionych kg (patrz TopRybak/getTop nizej, zostaje
        // jako martwa-ale-zachowana funkcja), tylko 10 najciezszych POJEDYNCZYCH ryb w
        // historii serwera, bez wzgledu na to jaki to gatunek. Ta sama zasada remisu co
        // rekord gatunku/serwera: SCISLE ">" gdy lista jest juz pelna (10) - dokladny
        // remis z aktualnym 10. miejscem NIE wypycha go z topki.
        List<Map<?, ?>> topPolowy = new ArrayList<>(config.getMapList("top-polowy"));
        boolean dodajDoTopki = topPolowy.size() < LIMIT_TOP_POLOWOW;
        if (!dodajDoTopki) {
            double najmniejszaWTopce = topPolowy.stream().mapToDouble(w -> ((Number) w.get("kg")).doubleValue()).min().orElse(0.0);
            dodajDoTopki = wagaDziesieteKg > naDziesiete(najmniejszaWTopce);
        }
        if (dodajDoTopki) {
            Map<String, Object> wpisTopki = new LinkedHashMap<>();
            wpisTopki.put("nick", nick);
            wpisTopki.put("gatunek", nazwaGatunku);
            wpisTopki.put("kg", wagaDziesieteKg / 10.0);
            topPolowy.add(wpisTopki);
            topPolowy.sort((a, b) -> Double.compare(((Number) b.get("kg")).doubleValue(), ((Number) a.get("kg")).doubleValue()));
            if (topPolowy.size() > LIMIT_TOP_POLOWOW) topPolowy = topPolowy.subList(0, LIMIT_TOP_POLOWOW);
            config.set("top-polowy", topPolowy);
        }

        saver.oznaczZmiane();
    }

    private static final int LIMIT_HISTORII = 50; // patrz zanotujPolow/getHistoria
    private static final int LIMIT_TOP_POLOWOW = 10; // patrz zanotujPolow/getTopPolowy
    private static final DateTimeFormatter FORMAT_CZASU = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("pl"));

    /** Jeden wpis w Dzienniku Połowów (patrz getHistoria) - customId, żeby GUI mogło pokazać PRAWDZIWĄ ikonę gatunku (patrz FishingManager), nie tylko samą nazwę. */
    public record WpisHistorii(String customId, String nazwaGatunku, double kg, String czas) {}

    /** Jeden wpis w top 10 najcięższych POJEDYNCZYCH połowów serwera (patrz getTopPolowy) - w odróżnieniu od TopRybak nizej to NIE suma, tylko jedna konkretna ryba. */
    public record TopPolow(String nick, String gatunek, double kg) {}

    /**
     * Top 10 (albo mniej, jeśli serwer nie ma jeszcze tylu połowów) najcięższych
     * POJEDYNCZYCH ryb złowionych KIEDYKOLWIEK, przez dowolnego gracza, dowolny gatunek -
     * patrz /rybtop w FishingManager.wyslijRanking (user 2026-08-30: chciał to zamiast
     * rankingu po sumie kg). Lista jest trzymana już posortowana malejąco (patrz
     * zanotujPolow), więc tu tylko odczyt, bez ponownego sortowania.
     */
    public List<TopPolow> getTopPolowy(int limit) {
        List<TopPolow> wynik = new ArrayList<>();
        for (Map<?, ?> wpis : config.getMapList("top-polowy")) {
            if (wynik.size() >= limit) break;
            wynik.add(new TopPolow(
                    String.valueOf(wpis.get("nick")),
                    String.valueOf(wpis.get("gatunek")),
                    wpis.get("kg") instanceof Number liczba ? liczba.doubleValue() : 0.0));
        }
        return wynik;
    }

    /**
     * Ostatnie połowy DANEGO GRACZA, NAJNOWSZE pierwsze - patrz Dziennik Połowów w GUI
     * (FishingManager.otworzDziennik). W odróżnieniu od getIndeks (jeden wpis na gatunek,
     * na zawsze) to chronologiczny log POJEDYNCZYCH połowów, obcięty do LIMIT_HISTORII
     * najnowszych (patrz zanotujPolow) - nie pełna historia życia gracza.
     */
    public List<WpisHistorii> getHistoria(UUID uuid, int limit) {
        List<WpisHistorii> wynik = new ArrayList<>();
        for (Map<?, ?> wpis : config.getMapList(uuid.toString() + ".historia")) {
            if (wynik.size() >= limit) break;
            wynik.add(new WpisHistorii(
                    String.valueOf(wpis.get("custom-id")),
                    String.valueOf(wpis.get("gatunek")),
                    wpis.get("kg") instanceof Number liczba ? liczba.doubleValue() : 0.0,
                    String.valueOf(wpis.get("czas"))));
        }
        return wynik;
    }

    /**
     * minKg - najlzejszy okaz TEGO gatunku, jaki ten gracz kiedykolwiek zlowil (patrz
     * zanotujPolow) - dla wpisow sprzed wprowadzenia tego pola (2026-08-30) domyslnie
     * rowny rekordKg, dopoki gracz nie zlowi czegos lzejszego.
     */
    public record WpisIndeksu(String customId, String nazwaGatunku, int zlowionychSztuk, double rekordKg, double minKg) {}

    /**
     * Wpisy indeksu rybackiego DANEGO GRACZA - patrz /rybiemenu w MainpluginsFishing.
     * Zwraca WYLACZNIE gatunki, ktore ten gracz juz KIEDYKOLWIEK zlowil (kolejnosc
     * dowolna - to wolajacy/komenda decyduje w jakiej kolejnosci je pokazac, zazwyczaj
     * wg kanonicznej kolejnosci z ryby.yml, patrz FishingManager.gatunki). Pusta lista,
     * jesli gracz jeszcze nic nie zlowil w lowisku.
     */
    public List<WpisIndeksu> getIndeks(UUID uuid) {
        List<WpisIndeksu> wynik = new ArrayList<>();
        var sekcja = config.getConfigurationSection(uuid.toString() + ".gatunki");
        if (sekcja == null) return wynik;

        for (String customId : sekcja.getKeys(false)) {
            String baza = uuid.toString() + ".gatunki." + customId;
            double rekordKg = config.getDouble(baza + ".rekord-kg", 0.0);
            wynik.add(new WpisIndeksu(
                    customId,
                    config.getString(baza + ".nazwa", customId),
                    config.getInt(baza + ".zlowionych", 0),
                    rekordKg,
                    config.getDouble(baza + ".rekord-min-kg", rekordKg)));
        }
        return wynik;
    }

    /** Suma zlowionych kg danego gracza (na zawsze), w kg - 0.0 jesli jeszcze nic nie zlowil. */
    public double sumaKg(UUID uuid) {
        return config.getDouble(uuid.toString() + ".suma-kg", 0.0);
    }

    public record OsobistyRekord(double kg, String gatunek) {}

    /** Najciezszy POJEDYNCZY polow TEGO gracza (dowolny gatunek) - kg=0.0 jesli jeszcze nic nie zlowil. Patrz "Twój najcięższy połów" w FishingManager.wyslijRanking. */
    public OsobistyRekord getOsobistyRekord(UUID uuid) {
        String klucz = uuid.toString();
        return new OsobistyRekord(config.getDouble(klucz + ".rekord-kg", 0.0), config.getString(klucz + ".rekord-gatunek", "?"));
    }

    public record TopRybak(UUID uuid, String nick, double sumaKg) {}

    /**
     * Top graczy po sumie zlowionych kg, malejaco. NIEUZYWANE w /rybtop od 2026-08-30 -
     * user chcial ranking po najciezszych POJEDYNCZYCH polowach zamiast sumy (patrz
     * getTopPolowy/TopPolow), ale suma-kg dalej jest zbierana (patrz zanotujPolow), wiec
     * zostawione jako gotowa funkcja - moze przydac sie np. w przyszlej karcie
     * "Statystyki gracza" w Dzienniku Rybaka.
     */
    public List<TopRybak> getTop(int limit) {
        List<TopRybak> wynik = new ArrayList<>();
        for (String klucz : config.getKeys(false)) {
            if (!czyUUID(klucz)) continue;

            double suma = config.getDouble(klucz + ".suma-kg", 0.0);
            if (suma <= 0) continue; // gracze bez ani jednego polowu nie maja czego szukac w topce

            UUID uuid = UUID.fromString(klucz);
            wynik.add(new TopRybak(uuid, nickGracza(uuid, klucz), suma));
        }
        wynik.sort((a, b) -> Double.compare(b.sumaKg(), a.sumaKg()));
        return wynik.size() > limit ? wynik.subList(0, limit) : wynik;
    }

    /** Pozycja gracza w rankingu sumy kg (1-based), albo -1 jesli jeszcze nic nie zlowil. */
    public int getPozycjaWRankingu(UUID uuid) {
        double mojaSuma = config.getDouble(uuid.toString() + ".suma-kg", 0.0);
        if (mojaSuma <= 0) return -1;

        int wiecejMajacych = 0;
        for (String klucz : config.getKeys(false)) {
            if (!czyUUID(klucz) || klucz.equals(uuid.toString())) continue;
            if (config.getDouble(klucz + ".suma-kg", 0.0) > mojaSuma) wiecejMajacych++;
        }
        return wiecejMajacych + 1;
    }

    /**
     * pierwszy = true jesli to byl PIERWSZY kiedykolwiek zlowiony okaz tego gatunku (wiec
     * automatycznie "rekord" - nie ma z czym porownac), false jesli realnie pobil czyjs
     * poprzedni rekord - wtedy poprzedniaWagaKg/poprzedniNick opisuja co zostalo pobite
     * (patrz FishingManager.ogloszRekordGatunku - inne brzmienie ogloszenia w obu przypadkach).
     */
    public record NowyRekordGatunku(boolean pierwszy, double poprzedniaWagaKg, String poprzedniNick) {}

    /**
     * Wywolywane po kazdym polowie (patrz FishingManager.nagrodaZaPolow) - jesli TA
     * zlowiona ryba jest ciezsza niz dotychczasowy rekord SWOJEGO GATUNKU (kluczowane po
     * customId, nie po nazwie - odporne na ewentualna zmiane display-name w przyszlosci),
     * zapisuje nowy rekord i zwraca informacje o nim (do publicznego ogloszenia na czacie).
     * Zwraca null, jesli to NIE byl nowy rekord (nic sie wtedy nie zapisuje/nie zmienia).
     */
    public NowyRekordGatunku zanotujRekordGatunkuJesliNowy(String customId, String nazwaGatunku, int wagaDziesieteKg, UUID uuid, String nick) {
        String klucz = "rekordy-gatunkow." + customId;
        long obecnyRekord = naDziesiete(config.getDouble(klucz + ".rekord-kg", 0.0));
        if (wagaDziesieteKg <= obecnyRekord) return null;

        boolean pierwszy = obecnyRekord <= 0;
        String poprzedniNick = pierwszy ? null : config.getString(klucz + ".nick");

        config.set(klucz + ".nazwa", nazwaGatunku); // czysto do czytelnosci pliku (klucz to customId, np. FISH_KARP)
        config.set(klucz + ".rekord-kg", wagaDziesieteKg / 10.0);
        config.set(klucz + ".uuid", uuid.toString());
        config.set(klucz + ".nick", nick);
        saver.oznaczZmiane();

        return new NowyRekordGatunku(pierwszy, obecnyRekord / 10.0, poprzedniNick);
    }

    public record RekordGatunku(String nick, double wagaKg) {}

    /**
     * Aktualny rekord SERWERA (dowolny gracz, patrz zanotujRekordGatunkuJesliNowy) DANEGO
     * gatunku - patrz GUI indeksu (iconaIndeksu) 2026-08-30, do odroznienia od osobistego
     * rekordu gracza (WpisIndeksu.rekordKg). Null jesli NIKT jeszcze nigdy nie zlowil
     * tego gatunku (nie powinno sie zdarzyc dla gatunku, ktory JUZ jest w indeksie gracza -
     * skoro on go zlowil, rekord serwera na ten gatunek musi istniec).
     */
    public RekordGatunku getRekordGatunku(String customId) {
        String klucz = "rekordy-gatunkow." + customId;
        if (!config.contains(klucz + ".rekord-kg")) return null;
        return new RekordGatunku(config.getString(klucz + ".nick", "?"), config.getDouble(klucz + ".rekord-kg", 0.0));
    }

    public record RekordSerwera(UUID uuid, String nick, double wagaKg, String gatunek) {}

    /**
     * Wywolywane po KAZDYM polowie (patrz FishingManager.nagrodaZaPolow, obok
     * zanotujRekordGatunkuJesliNowy) - jesli TA zlowiona ryba jest ciezsza niz DOTYCHCZASOWY
     * rekord SERWERA (dowolny gatunek, patrz getRekordSerwera), zapisuje nowy rekord.
     * SCISLE ">" (nie ">="), NIE ROWNOSC - user 2026-08-30: przy DOKLADNYM remisie
     * dotychczasowy rekordzista ZATRZYMUJE rekord (kto pierwszy zlowil te wage, ten
     * trzyma, remis go nie przebija) - dokladnie ta sama zasada co juz mial rekord
     * gatunku wyzej.
     */
    public void zanotujRekordSerweraJesliNowy(UUID uuid, String nick, String gatunek, int wagaDziesieteKg) {
        long obecnyRekord = naDziesiete(config.getDouble("rekord-serwera-global.rekord-kg", 0.0));
        if (wagaDziesieteKg <= obecnyRekord) return;

        config.set("rekord-serwera-global.rekord-kg", wagaDziesieteKg / 10.0);
        config.set("rekord-serwera-global.uuid", uuid.toString());
        config.set("rekord-serwera-global.nick", nick);
        config.set("rekord-serwera-global.gatunek", gatunek);
        saver.oznaczZmiane();
    }

    /**
     * Najciezsza pojedyncza ryba zlowiona kiedykolwiek na serwerze (dowolny gatunek) - null
     * jesli nikt jeszcze nic nie zlowil. Jawnie zapisany (patrz zanotujRekordSerweraJesliNowy)
     * zamiast przeliczany za kazdym razem ze wszystkich graczy - przy DOKLADNYM remisie
     * dwoch roznych graczy stare przeliczanie-na-biezaco wygrywalo losowo (kolejnosc w
     * pliku), to jest deterministyczne: kto pierwszy zlowil te wage, ten trzyma rekord.
     *
     * Wsteczna zgodnosc: jesli ten klucz jeszcze nie istnieje (dane sprzed tej zmiany,
     * 2026-08-30) - JEDNORAZOWO przelicza go starym sposobem (skan wszystkich graczy) i
     * zapisuje jawnie na przyszlosc, zamiast zwracac null mimo istniejacych polowow.
     */
    public RekordSerwera getRekordSerwera() {
        if (!config.contains("rekord-serwera-global.rekord-kg")) {
            String najlepszyKlucz = null;
            double najlepszaWagaKg = 0.0;
            for (String klucz : config.getKeys(false)) {
                if (!czyUUID(klucz)) continue;
                double rekord = config.getDouble(klucz + ".rekord-kg", 0.0);
                if (rekord > najlepszaWagaKg) {
                    najlepszaWagaKg = rekord;
                    najlepszyKlucz = klucz;
                }
            }
            if (najlepszyKlucz == null) return null;

            UUID uuid = UUID.fromString(najlepszyKlucz);
            String gatunek = config.getString(najlepszyKlucz + ".rekord-gatunek", "?");
            String nick = nickGracza(uuid, najlepszyKlucz);
            config.set("rekord-serwera-global.rekord-kg", najlepszaWagaKg);
            config.set("rekord-serwera-global.uuid", uuid.toString());
            config.set("rekord-serwera-global.nick", nick);
            config.set("rekord-serwera-global.gatunek", gatunek);
            saver.oznaczZmiane();
            return new RekordSerwera(uuid, nick, najlepszaWagaKg, gatunek);
        }

        return new RekordSerwera(
                UUID.fromString(config.getString("rekord-serwera-global.uuid")),
                config.getString("rekord-serwera-global.nick", "?"),
                config.getDouble("rekord-serwera-global.rekord-kg", 0.0),
                config.getString("rekord-serwera-global.gatunek", "?"));
    }

    /** Najpierw nick zapisany w pliku (patrz zanotujPolow) - dopiero jak go brakuje (stare dane / recznie skasowany), OfflinePlayer jako zapasowe zrodlo, a na sam koniec skrocone UUID zamiast "null". */
    private String nickGracza(UUID uuid, String klucz) {
        String zapisanyNick = config.getString(klucz + ".nick");
        if (zapisanyNick != null) return zapisanyNick;

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        return offlinePlayer.getName() != null ? offlinePlayer.getName() : klucz.substring(0, 8);
    }

    private static boolean czyUUID(String klucz) {
        try {
            UUID.fromString(klucz);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Wywolaj w onDisable() - zapis natychmiastowy, synchroniczny (patrz AsyncConfigSaver.zamknij). */
    public void zamknij() {
        saver.zamknij();
    }

    /**
     * Kasuje WSZYSTKO - sumy kg graczy, ich osobiste rekordy i rekordy gatunkow - patrz
     * komenda @resetrybtop w MainpluginsFishing. Glownie do sprzatania po testach na
     * wedkach testowych (patrz FishingManager.stworzWedkeTestowa) - zeby fejkowe polowy z
     * testow admina nie zostaly na zawsze w prawdziwej topce/rekordach graczy. Zapis
     * natychmiastowy (nie czeka na cykl AsyncConfigSaver), zeby admin od razu widzial ze
     * zadzialalo, nawet gdyby serwer padl zaraz potem.
     */
    public void wyczyscWszystko() {
        for (String klucz : new ArrayList<>(config.getKeys(false))) {
            config.set(klucz, null);
        }
        saver.zapiszTeraz();
    }
}
