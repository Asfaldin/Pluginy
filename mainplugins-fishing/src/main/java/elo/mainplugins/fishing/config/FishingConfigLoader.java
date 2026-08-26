package elo.mainplugins.fishing.config;

import elo.mainplugins.fishing.RybaGatunek;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Wczytuje fishing-config.yml do niemutowalnego {@link FishingConfig}. Ten sam wzorzec co
 * MenuGuiLoader/SpawnerConfigLoader: plik kopiowany z zasobu TYLKO przy pierwszym
 * uruchomieniu, każda zła/brakująca wartość dostaje warning i pada na sensowny domyślny
 * odpowiednik dawnej hardkodowanej stałej, zamiast crashować cały serwer przy starcie.
 */
public final class FishingConfigLoader {

    private FishingConfigLoader() {}

    public static FishingConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "fishing-config.yml");
        if (!file.exists()) {
            plugin.saveResource("fishing-config.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        List<RybaGatunek> gatunki = wczytajGatunki(cfg, log);

        FishingConfig.MinigryConfig minigra = new FishingConfig.MinigryConfig(
                cfg.getInt("minigra.szerokosc-paska", 40),
                cfg.getDouble("minigra.grawitacja", 1.35),
                cfg.getDouble("minigra.impuls-kliknieca", 0.62),
                Math.max(1, cfg.getInt("minigra.okres-tickow", 2)),
                Math.max(1, cfg.getInt("minigra.maksymalny-czas-sekund", 30)) * 20L,
                formula(cfg, "minigra.polowa-szerokosci-suwaka", 0.20, -0.022, 0.09, 0.20),
                formula(cfg, "minigra.predkosc-ryby", 0.35, 0.18, 0.0, 999.0),
                formula(cfg, "minigra.tempo-napelniania", 0.55, -0.05, 0.30, 0.55),
                formula(cfg, "minigra.tempo-oprozniania", 0.32, 0.05, 0.0, 999.0)
        );

        double szansaSkrzynki = cfg.getDouble("bonusowa-skrzynka.szansa-procent", 3.0);

        log.info("fishing-config.yml: wczytano " + gatunki.size() + " gatunkow ryb.");
        return new FishingConfig(gatunki, minigra, szansaSkrzynki);
    }

    private static List<RybaGatunek> wczytajGatunki(YamlConfiguration cfg, Logger log) {
        List<RybaGatunek> gatunki = new ArrayList<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection("gatunki");
        if (sekcja == null) {
            log.warning("fishing-config.yml: brak sekcji 'gatunki' - lowienie w lowisku nic nie zwroci.");
            return gatunki;
        }

        for (String id : sekcja.getKeys(false)) {
            String path = "gatunki." + id + ".";
            String materialRaw = cfg.getString(path + "material");
            Material material = materialRaw != null ? Material.matchMaterial(materialRaw) : null;
            if (material == null) {
                log.warning("fishing-config.yml: gatunek '" + id + "' ma zly/brakujacy 'material' ('" + materialRaw + "') - pomijam.");
                continue;
            }

            String kolorRaw = cfg.getString(path + "kolor", "GRAY");
            NamedTextColor kolor = NamedTextColor.NAMES.value(kolorRaw.toLowerCase());
            if (kolor == null) {
                log.warning("fishing-config.yml: gatunek '" + id + "' ma zly kolor ('" + kolorRaw + "') - uzywam GRAY.");
                kolor = NamedTextColor.GRAY;
            }

            RybaGatunek.Rzadkosc rzadkosc;
            try {
                rzadkosc = RybaGatunek.Rzadkosc.valueOf(cfg.getString(path + "rzadkosc", "ZWYKLA").toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warning("fishing-config.yml: gatunek '" + id + "' ma zla rzadkosc - uzywam ZWYKLA.");
                rzadkosc = RybaGatunek.Rzadkosc.ZWYKLA;
            }

            String nazwa = cfg.getString(path + "nazwa", id);
            int waga = Math.max(1, cfg.getInt(path + "waga", 1));
            gatunki.add(new RybaGatunek(id, nazwa, material, kolor, rzadkosc, waga));
        }
        return gatunki;
    }

    private static FishingConfig.Formula formula(YamlConfiguration cfg, String path, double bazowaDomyslna, double naTrudnoscDomyslna, double minDomyslny, double maxDomyslny) {
        return new FishingConfig.Formula(
                cfg.getDouble(path + ".bazowa", bazowaDomyslna),
                cfg.getDouble(path + ".na-trudnosc", naTrudnoscDomyslna),
                cfg.getDouble(path + ".min", minDomyslny),
                cfg.getDouble(path + ".max", maxDomyslny)
        );
    }
}
