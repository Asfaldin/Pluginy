package elo.mainplugins.skyblock.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje wyspy-config.yml (cała liczbowa/danych konfiguracja systemu wysp - koszty,
 * promienie, timeouty, typy spawnerów, wartości bloków) do niemutowalnego {@link IslandTuning}.
 * Ten sam wzorzec co ShopGuiLoader/QuestContentLoader: plik kopiowany z zasobu TYLKO przy
 * pierwszym uruchomieniu; brakująca/zła wartość dostaje warning i pada na sensowny domyślny
 * odpowiednik dawnej hardkodowanej stałej, zamiast crashować cały serwer przy starcie.
 */
public final class IslandConfigLoader {

    private IslandConfigLoader() {}

    public static IslandTuning load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "wyspy-config.yml");
        if (!file.exists()) {
            plugin.saveResource("wyspy-config.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        int domyslnyRozmiarWyspy = cfg.getInt("tworzenie-wyspy.domyslny-rozmiar", 50);
        List<IslandTuning.CooldownProg> cooldownProb = parseCooldownProb(cfg, log);

        int borderPrzyrost = cfg.getInt("border.przyrost-na-ulepszenie", 25);
        int borderKosztZaBlok = cfg.getInt("border.koszt-za-blok", 1000);
        int borderMaxRozmiar = cfg.getInt("border.max-rozmiar", 750);
        int odstepSiatkiWysp = cfg.getInt("border.odstep-siatki-wysp", 10000);

        int maxGlebokoscSzukaniaWDol = cfg.getInt("teleport-bezpieczenstwo.max-glebokosc-szukania-w-dol", 10);
        int promienSzukaniaObok = cfg.getInt("teleport-bezpieczenstwo.promien-szukania-obok", 5);

        long timeoutPotwierdzeniaTicks = cfg.getInt("timeouty.potwierdzenie-sekundy", 15) * 20L;
        long timeoutZaproszeniaTicks = cfg.getInt("timeouty.zaproszenie-sekundy", 60) * 20L;
        long maxLotPerlyTicks = cfg.getInt("timeouty.max-lot-perly-sekundy", 10) * 20L;

        int maxDlugoscNazwyWyspy = cfg.getInt("nazwa-wyspy.max-dlugosc", 24);

        int zapasNaSchemat = cfg.getInt("wyczyszczenie-terenu.zapas-na-schemat", 20);
        int chunkiNaTick = cfg.getInt("wyczyszczenie-terenu.chunki-na-tick", 4);

        Map<Material, Double> wartosciBlokow = parseWartosciBlokow(cfg, log);

        int spawnerMaxPoziom = cfg.getInt("spawnery.max-poziom", 5);
        List<SpawnerTyp> spawnerTypy = parseSpawnerTypy(cfg, log);

        Map<Integer, Integer> kosztBazowyIloscPoziomy = parsePoziomyKosztow(cfg, "spawnery.koszt-bazowy-ilosc.poziomy", log);
        int kosztBazowyIloscDomyslny = cfg.getInt("spawnery.koszt-bazowy-ilosc.domyslny", 17000);
        Map<Integer, Integer> kosztBazowySzybkoscPoziomy = parsePoziomyKosztow(cfg, "spawnery.koszt-bazowy-szybkosc.poziomy", log);
        int kosztBazowySzybkoscDomyslny = cfg.getInt("spawnery.koszt-bazowy-szybkosc.domyslny", 24000);

        log.info("wyspy-config.yml: wczytano konfiguracje (" + spawnerTypy.size() + " typow spawnerow, "
                + wartosciBlokow.size() + " wycenionych blokow).");

        return new IslandTuning(
                domyslnyRozmiarWyspy, cooldownProb,
                borderPrzyrost, borderKosztZaBlok, borderMaxRozmiar, odstepSiatkiWysp,
                maxGlebokoscSzukaniaWDol, promienSzukaniaObok,
                timeoutPotwierdzeniaTicks, timeoutZaproszeniaTicks, maxLotPerlyTicks,
                maxDlugoscNazwyWyspy, zapasNaSchemat, chunkiNaTick,
                wartosciBlokow, spawnerMaxPoziom, spawnerTypy,
                kosztBazowyIloscPoziomy, kosztBazowyIloscDomyslny,
                kosztBazowySzybkoscPoziomy, kosztBazowySzybkoscDomyslny
        );
    }

    private static List<IslandTuning.CooldownProg> parseCooldownProb(YamlConfiguration cfg, Logger log) {
        List<IslandTuning.CooldownProg> lista = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("tworzenie-wyspy.cooldown-prob")) {
            Object odProbyRaw = m.get("od-proby");
            Object sekundyRaw = m.get("sekundy");
            if (!(odProbyRaw instanceof Number) || !(sekundyRaw instanceof Number)) {
                log.warning("wyspy-config.yml: wpis w tworzenie-wyspy.cooldown-prob bez 'od-proby'/'sekundy' - pomijam.");
                continue;
            }
            lista.add(new IslandTuning.CooldownProg(((Number) odProbyRaw).intValue(), ((Number) sekundyRaw).longValue() * 1000L));
        }
        if (lista.isEmpty()) {
            log.warning("wyspy-config.yml: brak tworzenie-wyspy.cooldown-prob - uzywam wbudowanych domyslnych progow.");
            lista.add(new IslandTuning.CooldownProg(1, 0L));
            lista.add(new IslandTuning.CooldownProg(3, 60_000L));
            lista.add(new IslandTuning.CooldownProg(4, 5 * 60_000L));
            lista.add(new IslandTuning.CooldownProg(5, 30 * 60_000L));
            lista.add(new IslandTuning.CooldownProg(6, 60 * 60_000L));
            lista.add(new IslandTuning.CooldownProg(7, 24 * 60 * 60_000L));
        }
        return lista;
    }

    private static Map<Material, Double> parseWartosciBlokow(YamlConfiguration cfg, Logger log) {
        Map<Material, Double> mapa = new LinkedHashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection("wartosci-blokow");
        if (sekcja == null) return mapa;
        for (String key : sekcja.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                log.warning("wyspy-config.yml: wartosci-blokow ma nieznany material '" + key + "' - pomijam.");
                continue;
            }
            mapa.put(material, sekcja.getDouble(key));
        }
        return mapa;
    }

    private static List<SpawnerTyp> parseSpawnerTypy(YamlConfiguration cfg, Logger log) {
        List<SpawnerTyp> lista = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("spawnery.typy")) {
            Object idRaw = m.get("id");
            Object ikonaRaw = m.get("ikona");
            if (idRaw == null || ikonaRaw == null) {
                log.warning("wyspy-config.yml: wpis w spawnery.typy bez 'id'/'ikona' - pomijam.");
                continue;
            }
            Material ikona = Material.matchMaterial(String.valueOf(ikonaRaw));
            if (ikona == null) {
                log.warning("wyspy-config.yml: spawnery.typy '" + idRaw + "' ma zly material ikony ('" + ikonaRaw + "') - pomijam.");
                continue;
            }
            String nazwaOdmieniona = m.get("nazwa-odmieniona") != null ? String.valueOf(m.get("nazwa-odmieniona")) : String.valueOf(idRaw);
            int cenaWSklepie = m.get("cena-w-sklepie") instanceof Number n ? n.intValue() : 0;
            lista.add(new SpawnerTyp(String.valueOf(idRaw), nazwaOdmieniona, ikona, cenaWSklepie));
        }
        return lista;
    }

    private static Map<Integer, Integer> parsePoziomyKosztow(YamlConfiguration cfg, String path, Logger log) {
        Map<Integer, Integer> mapa = new LinkedHashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection(path);
        if (sekcja == null) return mapa;
        for (String key : sekcja.getKeys(false)) {
            try {
                mapa.put(Integer.parseInt(key), sekcja.getInt(key));
            } catch (NumberFormatException e) {
                log.warning("wyspy-config.yml: " + path + " ma nienumeryczny klucz poziomu '" + key + "' - pomijam.");
            }
        }
        return mapa;
    }
}
