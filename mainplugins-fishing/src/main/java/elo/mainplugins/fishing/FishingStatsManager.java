package elo.mainplugins.fishing;

import elo.mainplugins.core.util.AsyncConfigSaver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

        saver.oznaczZmiane();
    }

    public record WpisIndeksu(String customId, String nazwaGatunku, int zlowionychSztuk, double rekordKg) {}

    /**
     * Wpisy indeksu rybackiego DANEGO GRACZA - patrz /rybindeks w MainpluginsFishing.
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
            wynik.add(new WpisIndeksu(
                    customId,
                    config.getString(baza + ".nazwa", customId),
                    config.getInt(baza + ".zlowionych", 0),
                    config.getDouble(baza + ".rekord-kg", 0.0)));
        }
        return wynik;
    }

    /** Suma zlowionych kg danego gracza (na zawsze), w kg - 0.0 jesli jeszcze nic nie zlowil. */
    public double sumaKg(UUID uuid) {
        return config.getDouble(uuid.toString() + ".suma-kg", 0.0);
    }

    public record TopRybak(UUID uuid, String nick, double sumaKg) {}

    /** Top graczy po sumie zlowionych kg, malejaco - patrz /rybtop w MainpluginsFishing. */
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

    public record RekordSerwera(UUID uuid, String nick, double wagaKg, String gatunek) {}

    /** Najciezsza pojedyncza ryba zlowiona kiedykolwiek na serwerze (dowolny gatunek) - null jesli nikt jeszcze nic nie zlowil. */
    public RekordSerwera getRekordSerwera() {
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
        return new RekordSerwera(uuid, nickGracza(uuid, najlepszyKlucz), najlepszaWagaKg, gatunek);
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
