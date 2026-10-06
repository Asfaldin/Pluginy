package elo.mainplugins.spawners.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje spawnery-typy.yml do niemutowalnego {@link SpawnerConfig}. Ten sam wzorzec co
 * MenuGuiLoader/ChatFilterConfigLoader/RanksConfigLoader: plik kopiowany z zasobu TYLKO
 * przy pierwszym uruchomieniu, każda zła/brakująca wartość dostaje warning i pada na
 * sensowny domyślny odpowiednik dawnej hardkodowanej stałej, zamiast crashować cały
 * serwer przy starcie.
 */
public final class SpawnerConfigLoader {

    private SpawnerConfigLoader() {}

    public static SpawnerConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "spawnery-typy.yml");
        if (!file.exists()) {
            plugin.saveResource("spawnery-typy.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        Map<String, SpawnerTypeDef> typy = wczytajTypy(cfg, log);
        SpawnerSettings ustawienia = wczytajUstawienia(cfg, log);

        log.info("spawnery-typy.yml: wczytano " + typy.size() + " typow spawnerow.");
        return new SpawnerConfig(typy, ustawienia);
    }

    private static Map<String, SpawnerTypeDef> wczytajTypy(YamlConfiguration cfg, Logger log) {
        Map<String, SpawnerTypeDef> typy = new LinkedHashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection("typy");
        if (sekcja == null) {
            log.warning("spawnery-typy.yml: brak sekcji 'typy' - zaden spawner nie bedzie mozliwy do postawienia.");
            return typy;
        }

        for (String id : sekcja.getKeys(false)) {
            String path = "typy." + id + ".";
            String encjaRaw = cfg.getString(path + "encja");
            EntityType encja = null;
            if (encjaRaw != null) {
                try {
                    encja = EntityType.valueOf(encjaRaw.toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    // obsluzone ponizej jako null - warning
                }
            }
            if (encja == null) {
                log.warning("spawnery-typy.yml: typ '" + id + "' ma zly/brakujacy 'encja' ('" + encjaRaw + "') - pomijam.");
                continue;
            }

            String nazwaOdmieniona = cfg.getString(path + "nazwa-odmieniona", id);
            String nazwaPojedyncza = cfg.getString(path + "nazwa-pojedyncza", id);
            String ikonaRaw = cfg.getString(path + "ikona");
            Material ikona = ikonaRaw != null ? Material.matchMaterial(ikonaRaw) : null;
            if (ikona == null) {
                // Domyślnie jajo spawnu tego moba, a gdy go nie ma - zwykły spawner.
                ikona = Material.matchMaterial(encja.name() + "_SPAWN_EGG");
                if (ikona == null) ikona = Material.SPAWNER;
                if (ikonaRaw != null) log.warning("spawnery-typy.yml: typ '" + id + "' ma zla 'ikona' ('" + ikonaRaw + "') - uzywam " + ikona + ".");
            }
            double mnoznik = Math.max(0.1, cfg.getDouble(path + "mnoznik", 1.0));
            typy.put(id, new SpawnerTypeDef(id, encja, nazwaOdmieniona, nazwaPojedyncza, ikona, mnoznik));
        }
        return typy;
    }

    private static SpawnerSettings wczytajUstawienia(YamlConfiguration cfg, Logger log) {
        int maxPoziom = cfg.getInt("ustawienia.max-poziom", 5);
        int limitKolejki = cfg.getInt("ustawienia.limit-kolejki", 50);
        int interwalBazowy = cfg.getInt("ustawienia.interwal-sekund-bazowy", 36);
        int interwalNaPoziom = cfg.getInt("ustawienia.interwal-sekund-na-poziom", -4);
        int iloscBazowa = cfg.getInt("ustawienia.ilosc-na-cykl-bazowa", 4);
        int iloscNaPoziom = cfg.getInt("ustawienia.ilosc-na-cykl-na-poziom", 1);
        int limitSpawnerow = cfg.getInt("ustawienia.limit-spawnerow-na-wyspe", 10);
        int promienAktywnosci = cfg.getInt("ustawienia.promien-aktywnosci-gracza", 16);

        String narzedzieRaw = cfg.getString("ustawienia.narzedzie-zbierania", "STICK");
        Material narzedzie = narzedzieRaw != null ? Material.matchMaterial(narzedzieRaw) : null;
        if (narzedzie == null) {
            log.warning("spawnery-typy.yml: 'ustawienia.narzedzie-zbierania' ma zly material ('" + narzedzieRaw + "') - uzywam STICK.");
            narzedzie = Material.STICK;
        }

        boolean ulepszeniaWlaczone = cfg.getBoolean("ulepszenia.wlaczone", true);
        List<Integer> cenyIlosc = ceny(cfg, "ulepszenia.ceny-ilosc", List.of(2500, 5000, 8500, 17000), log);
        List<Integer> cenySzybkosc = ceny(cfg, "ulepszenia.ceny-szybkosc", List.of(3500, 7000, 12000, 24000), log);

        return new SpawnerSettings(Math.max(1, maxPoziom), limitKolejki, interwalBazowy, interwalNaPoziom,
                iloscBazowa, iloscNaPoziom, limitSpawnerow, promienAktywnosci, narzedzie,
                ulepszeniaWlaczone, cenyIlosc, cenySzybkosc);
    }

    /** Lista cen poziomów (1->2, 2->3...); brak albo pusta = domyślna. */
    private static List<Integer> ceny(YamlConfiguration cfg, String path, List<Integer> domyslne, Logger log) {
        if (!cfg.isList(path)) return domyslne;
        List<Integer> lista = new ArrayList<>();
        for (Object o : cfg.getList(path)) {
            if (o instanceof Number n) lista.add(Math.max(0, n.intValue()));
            else log.warning("spawnery-typy.yml: " + path + " ma nie-liczbe '" + o + "' - pomijam.");
        }
        return lista.isEmpty() ? domyslne : List.copyOf(lista);
    }
}
