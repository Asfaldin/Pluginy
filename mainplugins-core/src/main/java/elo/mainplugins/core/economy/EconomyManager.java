package elo.mainplugins.core.economy;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.TopGracz;
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
 * Kasa graczy trzymana jako long w GROSZACH (saldo × 100), nie jako double.
 *
 * Powód: double nie potrafi dokładnie zapisać większości ułamków dziesiętnych,
 * więc przy dużej liczbie operacji salda dryfują (1234.9999999998 zamiast 1235)
 * i gracza "nie stać" na przedmiot, na który go stać. long jest dokładny zawsze.
 *
 * Publiczne metody double zostają dla zgodności z resztą pluginów — konwersja
 * odbywa się na wejściu i wyjściu, a wewnątrz liczone jest wyłącznie na long.
 */
public class EconomyManager implements EconomyService {

    /** Ile groszy w jednostce waluty. */
    private static final long GROSZE_W_JEDNOSTCE = 100L;

    /**
     * Wersja formatu pliku. 1 = stary zapis (kwoty jako double),
     * 2 = obecny (grosze jako long). Klucz z kropką nie koliduje z UUID.
     */
    private static final String KLUCZ_WERSJI = "meta.wersja-formatu";
    private static final int WERSJA_GROSZE = 2;

    private final Plugin plugin;
    private final File plikEkonomii;
    private final FileConfiguration configEkonomii;

    public EconomyManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikEkonomii = new File(plugin.getDataFolder(), "ekonomia.yml");
        if (!plikEkonomii.exists()) {
            plikEkonomii.getParentFile().mkdirs();
            try { plikEkonomii.createNewFile(); } catch (IOException ignored) {}
        }
        this.configEkonomii = YamlConfiguration.loadConfiguration(plikEkonomii);
        migrujJesliTrzeba();
    }

    // ==================================================== migracja ====

    /**
     * Przepisuje stary plik (kwoty jako double) na grosze. Odpala się raz —
     * po migracji plik dostaje znacznik wersji i kolejne starty go pomijają.
     *
     * Bez tego po wgraniu patcha każde saldo zostałoby odczytane jako grosze,
     * czyli gracz z 5000 $ zobaczyłby 50 $.
     */
    private void migrujJesliTrzeba() {
        if (configEkonomii.getInt(KLUCZ_WERSJI, 1) >= WERSJA_GROSZE) return;

        int przeliczone = 0;
        for (String key : configEkonomii.getKeys(false)) {
            if (key.equals("meta")) continue;
            if (!czyUUID(key)) continue;

            double stareSaldo = configEkonomii.getDouble(key, 0.0);
            configEkonomii.set(key, Math.round(stareSaldo * GROSZE_W_JEDNOSTCE));
            przeliczone++;
        }

        configEkonomii.set(KLUCZ_WERSJI, WERSJA_GROSZE);
        zapisz();
        if (przeliczone > 0) {
            plugin.getLogger().info("Ekonomia: przeliczono " + przeliczone
                    + " kont na grosze (long). Stary format double już nieużywany.");
        }
    }

    private static boolean czyUUID(String key) {
        try { UUID.fromString(key); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    // ==================================================== API groszowe ====

    @Override
    public long getGrosze(UUID uuid) {
        return configEkonomii.getLong(uuid.toString(), 0L);
    }

    @Override
    public void setGrosze(UUID uuid, long grosze) {
        configEkonomii.set(uuid.toString(), Math.max(0L, grosze));
        zapisz();
    }

    @Override
    public void dodajGrosze(UUID uuid, long grosze) {
        setGrosze(uuid, getGrosze(uuid) + grosze);
    }

    @Override
    public boolean pobierzGrosze(UUID uuid, long grosze) {
        if (grosze <= 0) return true;
        long saldo = getGrosze(uuid);
        if (saldo < grosze) return false;
        setGrosze(uuid, saldo - grosze);
        return true;
    }

    // ==================================================== API double (zgodność) ====

    /** Zaokrąglenie do najbliższego grosza — jedyne miejsce, gdzie double dotyka pieniędzy. */
    private static long naGrosze(double kwota) {
        return Math.round(kwota * GROSZE_W_JEDNOSTCE);
    }

    private static double naKwote(long grosze) {
        return (double) grosze / GROSZE_W_JEDNOSTCE;
    }

    @Override
    public double getKasa(UUID uuid) {
        return naKwote(getGrosze(uuid));
    }

    @Override
    public void setKasa(UUID uuid, double ilosc) {
        setGrosze(uuid, naGrosze(ilosc));
    }

    @Override
    public void dodajKase(UUID uuid, double ilosc) {
        dodajGrosze(uuid, naGrosze(ilosc));
    }

    @Override
    public void odejmijKase(UUID uuid, double ilosc) {
        long doOdjecia = naGrosze(ilosc);
        long saldo = getGrosze(uuid);
        setGrosze(uuid, Math.max(0L, saldo - doOdjecia));
    }

    @Override
    public boolean maWystarczajaco(UUID uuid, double ilosc) {
        // Porównanie w groszach, nie w double — tu właśnie wcześniej gracz
        // z dokładnie 1000 $ dostawał "nie stać cię" przy cenie 1000 $.
        return getGrosze(uuid) >= naGrosze(ilosc);
    }

    // ==================================================== topka ====

    @Override
    public List<TopGracz> getTop(int limit) {
        List<TopGracz> wynik = new ArrayList<>();
        for (String key : configEkonomii.getKeys(false)) {
            if (key.equals("meta")) continue;
            if (!czyUUID(key)) continue;

            long grosze = configEkonomii.getLong(key, 0L);
            if (grosze <= 0) continue; // gracze bez kasy nie mają czego szukać w topce

            UUID uuid = UUID.fromString(key);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String nick = offlinePlayer.getName() != null
                    ? offlinePlayer.getName()
                    : uuid.toString().substring(0, 8);
            wynik.add(new TopGracz(uuid, nick, naKwote(grosze), grosze));
        }

        wynik.sort((a, b) -> Long.compare(b.grosze(), a.grosze()));
        return wynik.size() > limit ? wynik.subList(0, limit) : wynik;
    }

    private void zapisz() {
        try { configEkonomii.save(plikEkonomii); }
        catch (IOException e) { plugin.getLogger().warning("Nie mozna zapisac ekonomii!"); }
    }
}
