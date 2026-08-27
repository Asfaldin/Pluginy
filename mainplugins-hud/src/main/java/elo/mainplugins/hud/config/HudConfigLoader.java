package elo.mainplugins.hud.config;

import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.TopGracz;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje hud-config.yml do niemutowalnego {@link HudConfig}. Ten sam wzorzec co
 * MenuGuiLoader: plik kopiowany z zasobu TYLKO przy pierwszym uruchomieniu, zła/brakująca
 * wartość dostaje warning i pada na sensowny domyślny odpowiednik dawnej hardkodowanej stałej.
 */
public final class HudConfigLoader {

    private HudConfigLoader() {}

    public static HudConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "hud-config.yml");
        if (!file.exists()) {
            plugin.saveResource("hud-config.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        List<String> proTipy = cfg.getStringList("pro-tipy");
        if (proTipy.isEmpty()) {
            log.warning("hud-config.yml: 'pro-tipy' puste - stopka bedzie pokazywac tylko wskazowke rynkowa (jesli dostepna).");
        }

        int maxTop = cfg.getInt("ustawienia.max-top", 10);
        int sekundNaSlajd = Math.max(1, cfg.getInt("ustawienia.sekund-na-slajd", 8));
        int coKtorySlajdRynkowy = Math.max(1, cfg.getInt("ustawienia.co-ktory-slajd-rynkowy", 3));
        int szerokoscProTipu = cfg.getInt("ustawienia.szerokosc-pro-tipu", 32);
        int szerokoscPadGracza = cfg.getInt("ustawienia.szerokosc-pad-gracza", 34);

        List<TopGracz> fakeTopGraczy = wczytajFakeTopGraczy(cfg, log);
        List<IslandSummary> fakeTopWysp = wczytajFakeTopWysp(cfg, log);

        log.info("hud-config.yml: wczytano " + proTipy.size() + " pro tipow.");
        return new HudConfig(proTipy, maxTop, sekundNaSlajd, coKtorySlajdRynkowy, szerokoscProTipu, szerokoscPadGracza, fakeTopGraczy, fakeTopWysp);
    }

    private static List<TopGracz> wczytajFakeTopGraczy(YamlConfiguration cfg, Logger log) {
        List<TopGracz> lista = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("fake-top-graczy")) {
            Object nick = m.get("nick");
            Object kasa = m.get("kasa");
            if (nick == null || !(kasa instanceof Number)) {
                log.warning("hud-config.yml: wpis w fake-top-graczy bez 'nick'/'kasa' - pomijam.");
                continue;
            }
            lista.add(new TopGracz(null, String.valueOf(nick), ((Number) kasa).doubleValue()));
        }
        return lista;
    }

    private static List<IslandSummary> wczytajFakeTopWysp(YamlConfiguration cfg, Logger log) {
        List<IslandSummary> lista = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("fake-top-wysp")) {
            Object nick = m.get("nick");
            Object rozmiar = m.get("rozmiar");
            Object czlonkowie = m.get("czlonkowie");
            if (nick == null || !(rozmiar instanceof Number) || !(czlonkowie instanceof Number)) {
                log.warning("hud-config.yml: wpis w fake-top-wysp bez 'nick'/'rozmiar'/'czlonkowie' - pomijam.");
                continue;
            }
            lista.add(new IslandSummary(null, String.valueOf(nick), ((Number) rozmiar).intValue(), ((Number) czlonkowie).intValue(), Map.of()));
        }
        return lista;
    }
}
