package elo.mainplugins.skyblock.config;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.Reward;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        int domyslnyRozmiarWyspy = cfg.getInt("tworzenie-wyspy.domyslny-rozmiar", 15);
        List<IslandTuning.CooldownProg> cooldownProb = parseCooldownProb(cfg, log);

        int borderPrzyrost = cfg.getInt("border.przyrost-na-ulepszenie", 10);
        int borderKosztPierwszego = cfg.getInt("border.koszt-pierwszego-powiekszenia", 2250);
        double borderWzrostKosztu = Math.max(0, cfg.getDouble("border.wzrost-kosztu-procent", 35));
        int borderMaxRozmiar = cfg.getInt("border.max-rozmiar", 150);
        int odstepSiatkiWysp = cfg.getInt("border.odstep-siatki-wysp", 2000);

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
        List<SpawnerTyp> spawnerTypy = zListySpawnerow(plugin, parseSpawnerTypy(cfg, log), log);

        Map<Integer, Integer> kosztBazowyIloscPoziomy = parsePoziomyKosztow(cfg, "spawnery.koszt-bazowy-ilosc.poziomy", log);
        int kosztBazowyIloscDomyslny = cfg.getInt("spawnery.koszt-bazowy-ilosc.domyslny", 17000);
        Map<Integer, Integer> kosztBazowySzybkoscPoziomy = parsePoziomyKosztow(cfg, "spawnery.koszt-bazowy-szybkosc.poziomy", log);
        int kosztBazowySzybkoscDomyslny = cfg.getInt("spawnery.koszt-bazowy-szybkosc.domyslny", 24000);

        String nazwaSwiata = cfg.getString("swiat.nazwa", "skyblock_world");
        int wysokoscWyspy = cfg.getInt("tworzenie-wyspy.wysokosc", 100);
        String n = "nowa-wyspa.";
        IslandTuning.UstawieniaNowejWyspy nowaWyspa = new IslandTuning.UstawieniaNowejWyspy(
                cfg.getBoolean(n + "pvp", false),
                cfg.getBoolean(n + "budowanie-gosci", false), cfg.getBoolean(n + "wizualny-border", true),
                cfg.getBoolean(n + "zabijanie-mobow-gosci", false), cfg.getBoolean(n + "zabieranie-itemow-gosci", false),
                cfg.getBoolean(n + "skrzynie-gosci", false), cfg.getBoolean(n + "drzwi-i-mechanizmy-gosci", false),
                cfg.getBoolean(n + "otwarta-dla-odwiedzajacych", true), cfg.getBoolean(n + "rolnictwo-gosci", false),
                cfg.getBoolean(n + "wiadra-gosci", false),
                cfg.getBoolean(n + "czlonkowie-budowanie", true), cfg.getBoolean(n + "czlonkowie-skrzynie", true),
                cfg.getBoolean(n + "czlonkowie-zapraszanie", false), cfg.getBoolean(n + "czlonkowie-bank-i-ulepszenia", false));

        // Ilu graczy (razem z właścicielem) może mieć jedna wyspa; 0 = bez limitu.
        int limitCzlonkow = Math.max(0, cfg.getInt("czlonkowie.limit", 6));
        // Co gracz dostaje przy założeniu wyspy - wspólny format nagród z core (item/money/command...).
        List<Reward> nagrodyNaStart = CoreAPI.getRewardService().parse(cfg.getList("przedmioty-na-start"), "wyspy-config.yml przedmioty-na-start");

        log.info("wyspy-config.yml: wczytano konfiguracje (" + spawnerTypy.size() + " typow spawnerow, "
                + wartosciBlokow.size() + " wycenionych blokow).");

        return new IslandTuning(
                domyslnyRozmiarWyspy, cooldownProb,
                borderPrzyrost, borderKosztPierwszego, borderWzrostKosztu, borderMaxRozmiar, odstepSiatkiWysp,
                maxGlebokoscSzukaniaWDol, promienSzukaniaObok,
                timeoutPotwierdzeniaTicks, timeoutZaproszeniaTicks, maxLotPerlyTicks,
                maxDlugoscNazwyWyspy, zapasNaSchemat, chunkiNaTick,
                wartosciBlokow, spawnerMaxPoziom, spawnerTypy,
                kosztBazowyIloscPoziomy, kosztBazowyIloscDomyslny,
                kosztBazowySzybkoscPoziomy, kosztBazowySzybkoscDomyslny,
                nazwaSwiata, wysokoscWyspy, nowaWyspa,
                limitCzlonkow, nagrodyNaStart,
                cfg.getBoolean("napis-przy-wejsciu", true),
                parseWzory(cfg, log), parseLimityBlokow(cfg, log),
                cfg.getBoolean("limity.wlaczone", true), parseWylaczoneLimity(cfg, log),
                Math.max(0, cfg.getInt("limity.zwierzeta", 50)),
                // Moby pojawiające się same na wyspach - ustawienie całego serwera (domyślnie wyłączone, mniej lagów).
                cfg.getBoolean("moby.potwory", false), cfg.getBoolean("moby.zwierzeta", false),
                // Upadek w pustkę wraca na wyspę zamiast zabijać - ustawienie całego serwera.
                cfg.getBoolean("powrot-z-pustki", true),
                // Ogień i lawa podpalają bloki na wyspach - ustawienie całego serwera (domyślnie wyłączone).
                cfg.getBoolean("ogien-sie-rozprzestrzenia", false),
                // Zasady całego serwera (aplikacja: Wyspy -> Zasady serwera).
                cfg.getBoolean("odrodzenie-na-wyspie", true), cfg.getBoolean("zachowanie-ekwipunku", true),
                cfg.getBoolean("odwiedzanie-wysp", true), cfg.getBoolean("wybuchy-niszcza-bloki", false),
                cfg.getBoolean("pioruny", false),
                cfg.getBoolean("moby.warden", false), cfg.getBoolean("moby.wither", false), cfg.getBoolean("moby.balwan", false),
                parseBiomy(cfg, log)
        );
    }

    /** biomy: lista {biom, nazwa}; pusta = zmiana biomu wyłączona. Nieznany biom jest pomijany. */
    private static List<IslandTuning.BiomWyspy> parseBiomy(YamlConfiguration cfg, Logger log) {
        List<IslandTuning.BiomWyspy> lista = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("biomy")) {
            String id = String.valueOf(m.get("biom")).toLowerCase(java.util.Locale.ROOT);
            if (org.bukkit.Registry.BIOME.get(org.bukkit.NamespacedKey.minecraft(id)) == null) {
                log.warning("wyspy-config.yml: biomy - nie ma biomu '" + id + "', pomijam.");
                continue;
            }
            String nazwa = m.get("nazwa") != null ? String.valueOf(m.get("nazwa")) : id;
            lista.add(new IslandTuning.BiomWyspy(id, nazwa));
        }
        return lista;
    }

    private static List<IslandTuning.WzorWyspy> parseWzory(YamlConfiguration cfg, Logger log) {
        List<IslandTuning.WzorWyspy> lista = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("wzory-wysp")) {
            Object id = m.get("id");
            if (id == null) {
                log.warning("wyspy-config.yml: wpis w wzory-wysp bez 'id' - pomijam.");
                continue;
            }
            Material ikona = m.get("ikona") != null ? Material.matchMaterial(String.valueOf(m.get("ikona"))) : null;
            List<String> opis = new ArrayList<>();
            if (m.get("opis") instanceof List<?> linie) for (Object l : linie) opis.add(String.valueOf(l));
            // Zawartość skrzyni we wzorze: [{item: MATERIAL, amount: ile}], pusta = to, co zapisano w grze.
            List<org.bukkit.inventory.ItemStack> skrzynia = new ArrayList<>();
            if (m.get("skrzynia") instanceof List<?> przedmioty) {
                for (Object o : przedmioty) {
                    if (!(o instanceof Map<?, ?> p) || p.get("item") == null) continue;
                    Material mat = Material.matchMaterial(String.valueOf(p.get("item")));
                    if (mat == null || !mat.isItem()) {
                        log.warning("wyspy-config.yml: wzory-wysp '" + id + "' skrzynia ma nieznany przedmiot '" + p.get("item") + "' - pomijam.");
                        continue;
                    }
                    int ile = p.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                    skrzynia.add(new org.bukkit.inventory.ItemStack(mat, ile));
                }
            }
            lista.add(new IslandTuning.WzorWyspy(
                    elo.mainplugins.skyblock.template.IslandTemplate.czysteId(String.valueOf(id)),
                    m.get("nazwa") != null ? String.valueOf(m.get("nazwa")) : String.valueOf(id),
                    ikona != null ? ikona : Material.GRASS_BLOCK, opis, skrzynia));
        }
        if (lista.isEmpty()) lista.add(new IslandTuning.WzorWyspy("default", "Wyspa", Material.GRASS_BLOCK, List.of(), List.of()));
        return lista;
    }

    private static Map<Material, Integer> parseLimityBlokow(YamlConfiguration cfg, Logger log) {
        Map<Material, Integer> mapa = new LinkedHashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection("limity.bloki");
        if (sekcja == null) return mapa;
        for (String key : sekcja.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                log.warning("wyspy-config.yml: limity.bloki ma nieznany blok '" + key + "' - pomijam.");
                continue;
            }
            mapa.put(stawianyBlok(material), Math.max(0, sekcja.getInt(key)));
        }
        return mapa;
    }

    private static Set<Material> parseWylaczoneLimity(YamlConfiguration cfg, Logger log) {
        Set<Material> set = new HashSet<>();
        for (String key : cfg.getStringList("limity.wylaczone-bloki")) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                log.warning("wyspy-config.yml: limity.wylaczone-bloki ma nieznany blok '" + key + "' - pomijam.");
                continue;
            }
            set.add(stawianyBlok(material));
        }
        return set;
    }

    /** Przedmioty stawiane jako inny blok: limit liczy postawiony blok. */
    private static Material stawianyBlok(Material material) {
        if (material == Material.REDSTONE) return Material.REDSTONE_WIRE;
        if (material == Material.STRING) return Material.TRIPWIRE;
        return material;
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
            double mnoznik = m.get("mnoznik") instanceof Number n ? Math.max(0.1, n.doubleValue()) : 1.0;
            lista.add(new SpawnerTyp(String.valueOf(idRaw), nazwaOdmieniona, ikona, mnoznik));
        }
        return lista;
    }

    /**
     * Lista spawnerów pochodzi z pluginu Spawnery (plugins/MainpluginsSpawners/spawnery-typy.yml), żeby nowy
     * spawner od razu dało się ulepszać. Z wyspy-config.yml bierzemy tylko ikonę i mnożnik ceny (brak = ×1).
     * Bez pluginu Spawnery zostaje własna lista z wyspy-config.yml.
     */
    private static List<SpawnerTyp> zListySpawnerow(Plugin plugin, List<SpawnerTyp> wlasne, Logger log) {
        File plik = new File(plugin.getDataFolder().getParentFile(), "MainpluginsSpawners/spawnery-typy.yml");
        if (!plik.exists()) return wlasne;
        ConfigurationSection typy = YamlConfiguration.loadConfiguration(plik).getConfigurationSection("typy");
        if (typy == null) return wlasne;
        List<SpawnerTyp> lista = new ArrayList<>();
        for (String id : typy.getKeys(false)) {
            SpawnerTyp wlasny = null;
            for (SpawnerTyp t : wlasne) if (t.id().equals(id)) wlasny = t;
            String encja = typy.getString(id + ".encja", id);
            String nazwa = typy.getString(id + ".nazwa-odmieniona", wlasny != null ? wlasny.nazwaOdmieniona() : id);
            Material ikona = wlasny != null ? wlasny.ikona() : Material.matchMaterial(encja + "_SPAWN_EGG");
            if (ikona == null) ikona = Material.SPAWNER;
            lista.add(new SpawnerTyp(id, nazwa, ikona, wlasny != null ? wlasny.mnoznik() : 1.0));
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
