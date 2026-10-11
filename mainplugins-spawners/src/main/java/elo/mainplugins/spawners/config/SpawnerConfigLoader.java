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
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje spawnery-typy.yml do niemutowalnego {@link SpawnerConfig}. Ten sam wzorzec co
 * MenuGuiLoader/ChatFilterConfigLoader/RanksConfigLoader: plik kopiowany z zasobu TYLKO
 * przy pierwszym uruchomieniu, każda zła/brakująca wartość dostaje warning i pada na
 * sensowny domyślny odpowiednik dawnej hardkodowanej stałej, zamiast crashować cały
 * serwer przy starcie.
 *
 * Typ może nadpisać każde ustawienie spawnu (interwał, ilość, stackowanie, promienie, drop, pora...) -
 * czego nie poda, bierze z sekcji "ustawienia". Stare pliki (bez nowych kluczy) działają jak dawniej.
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

        SpawnerSettings ustawienia = wczytajUstawienia(cfg, log);
        Map<String, SpawnerTypeDef> typy = wczytajTypy(cfg, ustawienia, log);
        Map<String, UpgradeDef> ulepszenia = wczytajUlepszenia(cfg, ustawienia, log);

        log.info("spawnery-typy.yml: wczytano " + typy.size() + " typow spawnerow i " + ulepszenia.size() + " ulepszen.");
        return new SpawnerConfig(typy, ustawienia, ulepszenia);
    }

    private static Map<String, SpawnerTypeDef> wczytajTypy(YamlConfiguration cfg, SpawnerSettings u, Logger log) {
        Map<String, SpawnerTypeDef> typy = new LinkedHashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection("typy");
        if (sekcja == null) {
            log.warning("spawnery-typy.yml: brak sekcji 'typy' - zaden spawner nie bedzie mozliwy do postawienia.");
            return typy;
        }
        ConfigurationSection g = cfg.getConfigurationSection("ustawienia");

        for (String id : sekcja.getKeys(false)) {
            ConfigurationSection t = sekcja.getConfigurationSection(id);
            if (t == null) continue;

            // Custom mob z Kreatora mobów (mainplugins-mobs) albo zwykły mob z gry.
            String customMob = t.getString("custom-mob");
            if (customMob != null && customMob.isBlank()) customMob = null;
            if (customMob != null) customMob = customMob.trim().toLowerCase(Locale.ROOT);

            String encjaRaw = t.getString("encja");
            EntityType encja = null;
            if (encjaRaw != null && !encjaRaw.isBlank()) {
                try {
                    encja = EntityType.valueOf(encjaRaw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // obsluzone ponizej
                }
            }
            if (encja == null && customMob == null) {
                log.warning("spawnery-typy.yml: typ '" + id + "' ma zly/brakujacy 'encja' ('" + encjaRaw + "') i nie ma 'custom-mob' - pomijam.");
                continue;
            }
            if (customMob != null) encja = null; // custom mob wygrywa - encja nie jest wtedy używana

            String nazwaOdmieniona = t.getString("nazwa-odmieniona", id);
            String nazwaPojedyncza = t.getString("nazwa-pojedyncza", id);
            String ikonaRaw = t.getString("ikona");
            Material ikona = ikonaRaw != null ? Material.matchMaterial(ikonaRaw) : null;
            if (ikona == null) {
                // Domyślnie jajo spawnu tego moba, a gdy go nie ma (albo to custom mob) - zwykły spawner.
                ikona = encja != null ? Material.matchMaterial(encja.name() + "_SPAWN_EGG") : null;
                if (ikona == null) ikona = Material.SPAWNER;
                if (ikonaRaw != null) log.warning("spawnery-typy.yml: typ '" + id + "' ma zla 'ikona' ('" + ikonaRaw + "') - uzywam " + ikona + ".");
            }
            double mnoznik = Math.max(0.1, t.getDouble("mnoznik", 1.0));

            int interwal = t.getInt("interwal-sekund", u.interwalSekundBazowy());
            int interwalNaPoziom = t.getInt("interwal-sekund-na-poziom", u.interwalSekundNaPoziom());
            int ilosc = t.getInt("ilosc-na-cykl", u.iloscNaCyklBazowa());
            int iloscNaPoziom = t.getInt("ilosc-na-cykl-na-poziom", u.iloscNaCyklNaPoziom());
            int limitKolejki = Math.max(1, t.getInt("limit-kolejki", u.limitKolejki()));
            boolean stackowanie = t.getBoolean("stackowanie", g == null || g.getBoolean("stackowanie", true));
            int maxNaRaz = Math.max(1, t.getInt("max-mobow-naraz", g == null ? 6 : g.getInt("max-mobow-naraz", 6)));
            int promienSpawnu = Math.max(1, Math.min(16, t.getInt("promien-spawnu", g == null ? 3 : g.getInt("promien-spawnu", 3))));
            int promienAktywnosci = Math.max(1, t.getInt("promien-aktywnosci", u.promienAktywnosciGracza()));
            boolean ai = t.getBoolean("ai", g == null || g.getBoolean("ai", true));
            double mnoznikDropu = Math.max(0, t.getDouble("mnoznik-dropu", g == null ? 1.0 : g.getDouble("mnoznik-dropu", 1.0)));
            int xp = t.getInt("xp", g == null ? -1 : g.getInt("xp", -1));
            SpawnerTypeDef.Pora pora = pora(t.getString("pora", g == null ? "zawsze" : g.getString("pora", "zawsze")), id, log);
            boolean ulepszenia = t.getBoolean("ulepszenia", true);
            // Które ulepszenia z ulepszenia.lista ma ten spawner (brak = wszystkie).
            List<String> listaUlepszen = t.isList("ulepszenia-lista") ? t.getStringList("ulepszenia-lista").stream().map(x -> x.trim().toLowerCase(Locale.ROOT)).toList() : null;
            int limitNaWyspe = Math.max(0, t.getInt("limit-na-wyspe", 0));
            boolean nametag = t.getBoolean("nametag", g == null || g.getBoolean("nametag", true));
            boolean wKlatce = t.getBoolean("mob-w-klatce", g == null || g.getBoolean("mob-w-klatce", true));

            typy.put(id, new SpawnerTypeDef(id, encja, customMob, nazwaOdmieniona, nazwaPojedyncza, ikona, mnoznik,
                    interwal, interwalNaPoziom, ilosc, iloscNaPoziom, limitKolejki, stackowanie, maxNaRaz, promienSpawnu,
                    promienAktywnosci, ai, mnoznikDropu, xp, pora, ulepszenia, listaUlepszen, limitNaWyspe, nametag, wKlatce));
        }
        return typy;
    }

    private static SpawnerTypeDef.Pora pora(String raw, String id, Logger log) {
        String v = raw == null ? "zawsze" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "zawsze", "always", "" -> SpawnerTypeDef.Pora.ZAWSZE;
            case "dzien", "dzień", "day" -> SpawnerTypeDef.Pora.DZIEN;
            case "noc", "night" -> SpawnerTypeDef.Pora.NOC;
            default -> {
                log.warning("spawnery-typy.yml: typ '" + id + "' ma zla 'pora' ('" + raw + "') - uzywam 'zawsze'.");
                yield SpawnerTypeDef.Pora.ZAWSZE;
            }
        };
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

        // Limity za uprawnienia: "mainplugins.spawners.vip: 20" - gracz dostaje najwyższy pasujący (i nie mniej niż limit zwykły).
        Map<String, Integer> limityUprawnien = new LinkedHashMap<>();
        ConfigurationSection lu = cfg.getConfigurationSection("ustawienia.limity-uprawnien");
        if (lu != null) {
            // getValues(true): klucz z kropkami ("mainplugins.spawners.vip") wraca w całości, niezależnie od tego,
            // czy w pliku jest zagnieżdżony, czy zapisany jako jeden klucz w cudzysłowie (tak zapisuje aplikacja).
            for (Map.Entry<String, Object> e : lu.getValues(true).entrySet()) {
                if (e.getValue() instanceof Number n) limityUprawnien.put(e.getKey(), Math.max(0, n.intValue()));
            }
        }

        String ktoRaw = cfg.getString("ustawienia.kto-moze-zbierac", "wyspa").trim().toLowerCase(Locale.ROOT);
        SpawnerSettings.KtoZbiera kto = switch (ktoRaw) {
            case "wlasciciel", "właściciel", "owner" -> SpawnerSettings.KtoZbiera.WLASCICIEL;
            case "kazdy", "każdy", "anyone" -> SpawnerSettings.KtoZbiera.KAZDY;
            case "nikt", "nobody" -> SpawnerSettings.KtoZbiera.NIKT;
            default -> SpawnerSettings.KtoZbiera.WYSPA;
        };

        boolean ulepszeniaWlaczone = cfg.getBoolean("ulepszenia.wlaczone", true);
        List<Integer> cenyIlosc = ceny(cfg, "ulepszenia.ceny-ilosc", List.of(2500, 5000, 8500, 17000), log);
        List<Integer> cenySzybkosc = ceny(cfg, "ulepszenia.ceny-szybkosc", List.of(3500, 7000, 12000, 24000), log);

        return new SpawnerSettings(Math.max(1, maxPoziom), limitKolejki, interwalBazowy, interwalNaPoziom,
                iloscBazowa, iloscNaPoziom, limitSpawnerow, promienAktywnosci, narzedzie,
                ulepszeniaWlaczone, cenyIlosc, cenySzybkosc, Map.copyOf(limityUprawnien), kto);
    }

    /**
     * Lista ulepszeń (ulepszenia.lista). Brak sekcji = stare pliki: dwa ulepszenia jak dawniej - Ilość
     * (ceny-ilosc) i Szybkość (ceny-szybkosc), oba do max-poziom. Id ulepszeń są zapisane w poziomy.yml
     * graczy, więc "ilosc"/"szybkosc" muszą zostać tymi samymi kluczami.
     */
    private static Map<String, UpgradeDef> wczytajUlepszenia(YamlConfiguration cfg, SpawnerSettings u, Logger log) {
        Map<String, UpgradeDef> out = new LinkedHashMap<>();
        ConfigurationSection lista = cfg.getConfigurationSection("ulepszenia.lista");
        if (lista == null) {
            out.put("ilosc", new UpgradeDef("ilosc", "Ilość", UpgradeDef.Efekt.ILOSC.ikona(), UpgradeDef.Efekt.ILOSC, u.iloscNaCyklNaPoziom(), u.maxPoziom(), u.cenyIlosc()));
            out.put("szybkosc", new UpgradeDef("szybkosc", "Szybkość", UpgradeDef.Efekt.SZYBKOSC.ikona(), UpgradeDef.Efekt.SZYBKOSC, u.interwalSekundNaPoziom(), u.maxPoziom(), u.cenySzybkosc()));
            return out;
        }
        for (String id : lista.getKeys(false)) {
            ConfigurationSection s = lista.getConfigurationSection(id);
            if (s == null) continue;
            String key = id.trim().toLowerCase(Locale.ROOT);
            UpgradeDef.Efekt efekt = UpgradeDef.Efekt.z(s.getString("efekt", key));
            if (efekt == null) {
                log.warning("spawnery-typy.yml: ulepszenie '" + id + "' ma zly 'efekt' ('" + s.getString("efekt") + "') - pomijam. Mozliwe: ilosc, szybkosc, drop, xp, max-naraz, stos, zasieg.");
                continue;
            }
            String ikonaRaw = s.getString("ikona");
            Material ikona = ikonaRaw != null ? Material.matchMaterial(ikonaRaw) : null;
            if (ikona == null) ikona = efekt.ikona();
            double naPoziom = s.getDouble("na-poziom", efekt == UpgradeDef.Efekt.ILOSC ? u.iloscNaCyklNaPoziom()
                    : efekt == UpgradeDef.Efekt.SZYBKOSC ? u.interwalSekundNaPoziom() : efekt.domyslnie);
            int max = Math.max(1, s.getInt("max-poziom", u.maxPoziom()));
            List<Integer> domyslneCeny = efekt == UpgradeDef.Efekt.SZYBKOSC ? u.cenySzybkosc() : u.cenyIlosc();
            out.put(key, new UpgradeDef(key, s.getString("nazwa", id), ikona, efekt, naPoziom, max, ceny(cfg, "ulepszenia.lista." + id + ".ceny", domyslneCeny, log)));
        }
        return out;
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
