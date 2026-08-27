package elo.mainplugins.spawners.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
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
            typy.put(id, new SpawnerTypeDef(id, encja, nazwaOdmieniona, nazwaPojedyncza));
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

        return new SpawnerSettings(maxPoziom, limitKolejki, interwalBazowy, interwalNaPoziom,
                iloscBazowa, iloscNaPoziom, limitSpawnerow, promienAktywnosci, narzedzie);
    }
}
