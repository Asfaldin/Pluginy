package elo.mainplugins.advancements.config;

import elo.mainplugins.advancements.model.AchievementCategory;
import elo.mainplugins.advancements.model.AchievementDef;
import elo.mainplugins.advancements.model.Reward;
import elo.mainplugins.advancements.model.TriggerSource;
import elo.mainplugins.core.api.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje osiagniecia.yml do niemutowalnego {@link AchievementsConfig}. Ten sam
 * wzorzec co ChatFilterConfigLoader / TrybGuiLoader: plik kopiowany z zasobu tylko
 * przy pierwszym starcie, każda zła wartość dostaje warning i sensowny fallback
 * (albo cały wpis jest pomijany), nigdy nie wywala serwera przy starcie.
 */
public final class AchievementsConfigLoader {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();
    private static final int DOMYSLNY_ROZMIAR = 54;

    private AchievementsConfigLoader() {}

    public static AchievementsConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "osiagniecia.yml");
        if (!file.exists()) {
            plugin.saveResource("osiagniecia.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        AchievementsConfig.Gui gui = wczytajGui(cfg.getConfigurationSection("gui"), log);
        AchievementsConfig.Powiadomienia pow = wczytajPowiadomienia(cfg.getConfigurationSection("powiadomienia"), log);
        AchievementsConfig.Datapack datapack = wczytajDatapack(cfg.getConfigurationSection("datapack"));

        ConfigurationSection nagrodySekcja = cfg.getConfigurationSection("nagrody");
        boolean autoWszystkie = nagrodySekcja != null && nagrodySekcja.getBoolean("auto-wszystkie", false);
        AchievementsConfig.Nagrody nagrody = new AchievementsConfig.Nagrody(autoWszystkie);

        int coSekund = cfg.getInt("sprawdzanie-co-sekund", 10);
        if (coSekund < 1) {
            log.warning("osiagniecia.yml: 'sprawdzanie-co-sekund' = " + coSekund + " < 1 - uzywam 10.");
            coSekund = 10;
        }

        List<AchievementCategory> kategorie = wczytajKategorie(cfg.getConfigurationSection("kategorie"), log);
        List<AchievementDef> osiagniecia = wczytajOsiagniecia(cfg.getConfigurationSection("osiagniecia"), kategorie, autoWszystkie, log);

        log.info("osiagniecia.yml: wczytano " + kategorie.size() + " kategorii i " + osiagniecia.size() + " osiagniec.");
        return new AchievementsConfig(gui, pow, datapack, nagrody, coSekund, kategorie, osiagniecia);
    }

    private static AchievementsConfig.Datapack wczytajDatapack(ConfigurationSection s) {
        boolean wlaczony = s != null && s.getBoolean("wlaczony", false);
        String namespace = s != null ? s.getString("namespace", "mpa") : "mpa";
        // namespace advancementu musi być [a-z0-9_.-]; sanityzujemy, żeby nie wywalić datapacka
        namespace = namespace.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "");
        if (namespace.isBlank()) namespace = "mpa";
        String tlo = s != null ? s.getString("tlo-domyslne", "minecraft:textures/block/stone.png") : "minecraft:textures/block/stone.png";
        return new AchievementsConfig.Datapack(wlaczony, namespace, tlo);
    }

    private static AchievementsConfig.Gui wczytajGui(ConfigurationSection s, Logger log) {
        String tytulRaw = s != null ? s.getString("tytul", "&8Osiągnięcia") : "&8Osiągnięcia";
        Component tytul = SER.deserialize(tytulRaw).decoration(TextDecoration.ITALIC, false);

        int rozmiar = s != null ? s.getInt("rozmiar", DOMYSLNY_ROZMIAR) : DOMYSLNY_ROZMIAR;
        if (rozmiar < 18 || rozmiar > 54 || rozmiar % 9 != 0) {
            log.warning("osiagniecia.yml: 'gui.rozmiar' = " + rozmiar + " nie jest wielokrotnoscia 9 w zakresie 18-54 - uzywam " + DOMYSLNY_ROZMIAR + ".");
            rozmiar = DOMYSLNY_ROZMIAR;
        }

        Material tlo = Material.GRAY_STAINED_GLASS_PANE;
        String tloRaw = s != null ? s.getString("tlo") : null;
        if (tloRaw != null) {
            Material m = Material.matchMaterial(tloRaw);
            if (m != null && m.isItem()) tlo = m;
            else log.warning("osiagniecia.yml: 'gui.tlo' ma zly material ('" + tloRaw + "') - uzywam GRAY_STAINED_GLASS_PANE.");
        }
        return new AchievementsConfig.Gui(tytul, rozmiar, tlo);
    }

    private static AchievementsConfig.Powiadomienia wczytajPowiadomienia(ConfigurationSection s, Logger log) {
        String dzwiek = s != null ? s.getString("dzwiek", "entity.player.levelup") : "entity.player.levelup";
        String tytul = s != null ? s.getString("tytul", "&6&lOSIĄGNIĘCIE ZDOBYTE") : "&6&lOSIĄGNIĘCIE ZDOBYTE";
        String podtytul = s != null ? s.getString("podtytul", "&e%nazwa%") : "&e%nazwa%";
        boolean efektRzadkich = s == null || s.getBoolean("efekt-rzadkich", true);
        return new AchievementsConfig.Powiadomienia(dzwiek, tytul, podtytul, efektRzadkich);
    }

    private static List<AchievementCategory> wczytajKategorie(ConfigurationSection s, Logger log) {
        List<AchievementCategory> lista = new ArrayList<>();
        if (s == null) {
            log.warning("osiagniecia.yml: brak sekcji 'kategorie' - panel bedzie pusty.");
            return lista;
        }
        for (String id : s.getKeys(false)) {
            ConfigurationSection k = s.getConfigurationSection(id);
            if (k == null) continue;

            String nazwaRaw = k.getString("nazwa", id);
            Component nazwa = SER.deserialize(nazwaRaw).decoration(TextDecoration.ITALIC, false);

            Material ikona = Material.BOOK;
            String ikonaRaw = k.getString("ikona");
            if (ikonaRaw != null) {
                Material m = Material.matchMaterial(ikonaRaw);
                if (m != null && m.isItem()) ikona = m;
                else log.warning("osiagniecia.yml: kategoria '" + id + "' ma zla ikone ('" + ikonaRaw + "') - uzywam BOOK.");
            }

            int kolejnosc = k.getInt("kolejnosc", 100);
            String tloZakladki = k.getString("tlo-zakladki");
            lista.add(new AchievementCategory(id, nazwa, ikona, kolejnosc, tloZakladki));
        }
        lista.sort((a, b) -> Integer.compare(a.kolejnosc(), b.kolejnosc()));
        return lista;
    }

    private static List<AchievementDef> wczytajOsiagniecia(ConfigurationSection s, List<AchievementCategory> kategorie, boolean autoWszystkie, Logger log) {
        List<AchievementDef> lista = new ArrayList<>();
        if (s == null) {
            log.warning("osiagniecia.yml: brak sekcji 'osiagniecia'.");
            return lista;
        }
        java.util.Set<String> znaneKategorie = new java.util.HashSet<>();
        for (AchievementCategory k : kategorie) znaneKategorie.add(k.id());

        for (String id : s.getKeys(false)) {
            ConfigurationSection a = s.getConfigurationSection(id);
            if (a == null) continue;

            String kategoriaId = a.getString("kategoria");
            if (kategoriaId == null || !znaneKategorie.contains(kategoriaId)) {
                log.warning("osiagniecia.yml: osiagniecie '" + id + "' wskazuje na nieznana kategorie '" + kategoriaId + "' - pomijam.");
                continue;
            }

            TriggerSource zrodlo = wczytajZrodlo(a, id, log);
            if (zrodlo == null) continue;

            Material ikona = Material.PAPER;
            String ikonaRaw = a.getString("ikona");
            if (ikonaRaw != null) {
                Material m = Material.matchMaterial(ikonaRaw);
                if (m != null && m.isItem()) ikona = m;
                else log.warning("osiagniecia.yml: osiagniecie '" + id + "' ma zla ikone ('" + ikonaRaw + "') - uzywam PAPER.");
            }

            Component nazwa = SER.deserialize(a.getString("nazwa", id)).decoration(TextDecoration.ITALIC, false);

            List<Component> opis = new ArrayList<>();
            for (String linia : a.getStringList("opis")) {
                opis.add(SER.deserialize(linia).decoration(TextDecoration.ITALIC, false));
            }

            boolean ukryte = a.getBoolean("ukryte", false);
            boolean spektakularne = a.getBoolean("spektakularne", false);
            // Globalne 'nagrody.auto-wszystkie' można nadpisać per osiągnięcie wpisem 'auto'.
            boolean auto = a.isSet("auto") ? a.getBoolean("auto") : autoWszystkie;

            String ramka = a.getString("ramka", spektakularne ? "challenge" : "task").trim().toLowerCase(Locale.ROOT);
            if (!ramka.equals("task") && !ramka.equals("goal") && !ramka.equals("challenge")) {
                log.warning("osiagniecia.yml: osiagniecie '" + id + "' ma zla 'ramka' ('" + ramka + "') - uzywam 'task'.");
                ramka = "task";
            }

            List<Reward> nagrody = wczytajNagrody(a.getMapList("nagrody"), id, log);

            lista.add(new AchievementDef(id, kategoriaId, zrodlo, ikona, nazwa, opis, ramka, ukryte, spektakularne, auto, nagrody));
        }
        return lista;
    }

    private static TriggerSource wczytajZrodlo(ConfigurationSection a, String id, Logger log) {
        String raw = a.getString("zrodlo");
        if (raw == null || raw.isBlank()) {
            log.warning("osiagniecia.yml: osiagniecie '" + id + "' nie ma 'zrodlo' - pomijam.");
            return null;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);

        if (lower.startsWith("vanilla:")) {
            String klucz = raw.trim().substring("vanilla:".length());
            NamespacedKey nk;
            try {
                nk = klucz.contains(":") ? NamespacedKey.fromString(klucz) : NamespacedKey.minecraft(klucz.toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                nk = null;
            }
            if (nk == null) {
                log.warning("osiagniecia.yml: osiagniecie '" + id + "' ma niepoprawny klucz advancementu ('" + klucz + "') - pomijam.");
                return null;
            }
            return new TriggerSource.Vanilla(nk);
        }

        switch (lower) {
            case "custom:kasa" -> {
                double prog = a.getDouble("prog", 0);
                if (prog <= 0) { log.warning("osiagniecia.yml: '" + id + "' (custom:kasa) wymaga dodatniego 'prog' - pomijam."); return null; }
                return new TriggerSource.Balance(prog);
            }
            case "custom:ranga" -> {
                String r = a.getString("ranga", "");
                try {
                    return new TriggerSource.RankAtLeast(Rank.valueOf(r.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    log.warning("osiagniecia.yml: '" + id + "' (custom:ranga) ma nieznana range '" + r + "' - pomijam.");
                    return null;
                }
            }
            case "custom:czas-gry" -> {
                int godziny = a.getInt("godziny", 0);
                if (godziny <= 0) { log.warning("osiagniecia.yml: '" + id + "' (custom:czas-gry) wymaga dodatnich 'godziny' - pomijam."); return null; }
                return new TriggerSource.Playtime(godziny);
            }
            case "custom:advancementy" -> {
                int prog = a.getInt("prog", 0);
                if (prog <= 0) { log.warning("osiagniecia.yml: '" + id + "' (custom:advancementy) wymaga dodatniego 'prog' - pomijam."); return null; }
                return new TriggerSource.AdvancementCount(prog);
            }
            case "custom:statystyka" -> {
                return wczytajStatystyke(a, id, log);
            }
            default -> {
                log.warning("osiagniecia.yml: osiagniecie '" + id + "' ma nieznane 'zrodlo' ('" + raw + "') - pomijam.");
                return null;
            }
        }
    }

    private static TriggerSource wczytajStatystyke(ConfigurationSection a, String id, Logger log) {
        String statRaw = a.getString("statystyka", "");
        Statistic stat;
        try {
            stat = Statistic.valueOf(statRaw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning("osiagniecia.yml: '" + id + "' (custom:statystyka) ma nieznana statystyke '" + statRaw + "' - pomijam.");
            return null;
        }
        int prog = a.getInt("prog", 0);
        if (prog <= 0) {
            log.warning("osiagniecia.yml: '" + id + "' (custom:statystyka) wymaga dodatniego 'prog' - pomijam.");
            return null;
        }

        Material material = null;
        EntityType entity = null;
        Statistic.Type typ = stat.getType();
        if (typ == Statistic.Type.BLOCK || typ == Statistic.Type.ITEM) {
            String mRaw = a.getString("material");
            material = mRaw != null ? Material.matchMaterial(mRaw) : null;
            if (material == null) {
                log.warning("osiagniecia.yml: '" + id + "' - statystyka " + stat + " wymaga poprawnego 'material' - pomijam.");
                return null;
            }
        } else if (typ == Statistic.Type.ENTITY) {
            String eRaw = a.getString("istota");
            try {
                entity = eRaw != null ? EntityType.valueOf(eRaw.trim().toUpperCase(Locale.ROOT)) : null;
            } catch (IllegalArgumentException ignored) { /* entity zostaje null */ }
            if (entity == null) {
                log.warning("osiagniecia.yml: '" + id + "' - statystyka " + stat + " wymaga poprawnej 'istota' (EntityType) - pomijam.");
                return null;
            }
        }
        return new TriggerSource.StatisticThreshold(stat, material, entity, prog);
    }

    private static List<Reward> wczytajNagrody(List<Map<?, ?>> surowe, String id, Logger log) {
        List<Reward> nagrody = new ArrayList<>();
        for (Map<?, ?> m : surowe) {
            Object typObj = m.get("typ");
            if (typObj == null) {
                log.warning("osiagniecia.yml: osiagniecie '" + id + "' ma nagrode bez 'typ' - pomijam wpis.");
                continue;
            }
            String typ = typObj.toString().trim().toUpperCase(Locale.ROOT);
            switch (typ) {
                case "MONEY", "KASA" -> {
                    double ilosc = liczba(m.get("ilosc"), 0);
                    if (ilosc <= 0) { log.warning("osiagniecia.yml: '" + id + "' - nagroda MONEY z niedodatnia 'ilosc' - pomijam."); continue; }
                    nagrody.add(new Reward.Money(ilosc));
                }
                case "ITEM" -> {
                    Material mat = m.get("material") != null ? Material.matchMaterial(m.get("material").toString()) : null;
                    int ilosc = (int) liczba(m.get("ilosc"), 1);
                    if (mat == null || !mat.isItem() || ilosc <= 0) { log.warning("osiagniecia.yml: '" + id + "' - nagroda ITEM z zlym 'material'/'ilosc' - pomijam."); continue; }
                    nagrody.add(new Reward.Item(mat, ilosc));
                }
                case "CUSTOM_ITEM" -> {
                    Object cid = m.get("id");
                    int ilosc = (int) liczba(m.get("ilosc"), 1);
                    if (cid == null || ilosc <= 0) { log.warning("osiagniecia.yml: '" + id + "' - nagroda CUSTOM_ITEM bez 'id' - pomijam."); continue; }
                    nagrody.add(new Reward.CustomItem(cid.toString(), ilosc));
                }
                case "CRATE", "SKRZYNKA" -> {
                    int tier = (int) liczba(m.get("tier"), 1);
                    if (tier < 1) { log.warning("osiagniecia.yml: '" + id + "' - nagroda CRATE z 'tier' < 1 - pomijam."); continue; }
                    nagrody.add(new Reward.Crate(tier));
                }
                case "COMMAND", "KOMENDA" -> {
                    Object kom = m.get("komenda");
                    if (kom == null || kom.toString().isBlank()) { log.warning("osiagniecia.yml: '" + id + "' - nagroda COMMAND bez 'komenda' - pomijam."); continue; }
                    nagrody.add(new Reward.Command(kom.toString()));
                }
                default -> log.warning("osiagniecia.yml: '" + id + "' - nieznany typ nagrody '" + typ + "' - pomijam.");
            }
        }
        return nagrody;
    }

    private static double liczba(Object o, double domyslna) {
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) {
            try { return Double.parseDouble(o.toString().trim()); } catch (NumberFormatException ignored) {}
        }
        return domyslna;
    }
}
