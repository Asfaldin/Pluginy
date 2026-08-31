package elo.mainplugins.fishing.config;

import elo.mainplugins.fishing.RybaGatunek;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
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

        FishingConfig.MinigryConfig minigra = new FishingConfig.MinigryConfig(
                cfg.getInt("minigra.szerokosc-paska", 40),
                cfg.getDouble("minigra.grawitacja", 0.65),
                cfg.getDouble("minigra.impuls-kliknieca", 0.38),
                Math.max(1, cfg.getInt("minigra.okres-tickow", 2)),
                formula(cfg, log, "minigra.polowa-szerokosci-suwaka", 0.10, -0.011, 0.05, 0.10),
                formula(cfg, log, "minigra.predkosc-ryby", 0.10, 0.05, 0.0, 999.0),
                formula(cfg, log, "minigra.tempo-napelniania", 0.18, -0.015, 0.10, 0.18),
                formula(cfg, log, "minigra.tempo-oprozniania", 0.12, 0.02, 0.12, 0.20)
        );

        double szansaSkrzynki = cfg.getDouble("bonusowa-skrzynka.szansa-procent", 3.0);

        return new FishingConfig(minigra, szansaSkrzynki);
    }

    private static FishingConfig.Formula formula(YamlConfiguration cfg, Logger log, String path, double bazowaDomyslna, double naTrudnoscDomyslna, double minDomyslny, double maxDomyslny) {
        return new FishingConfig.Formula(
                cfg.getDouble(path + ".bazowa", bazowaDomyslna),
                cfg.getDouble(path + ".na-trudnosc", naTrudnoscDomyslna),
                cfg.getDouble(path + ".min", minDomyslny),
                cfg.getDouble(path + ".max", maxDomyslny),
                nadpisania(cfg, log, path + ".nadpisania")
        );
    }

    /**
     * Opcjonalna sekcja "nadpisania: {RZADKOSC: wartosc}" pod daną formułą (patrz javadoc
     * FishingConfig.Formula) - klucz to nazwa RybaGatunek.Rzadkosc, zamieniana na ordinal
     * (to samo "trudnosc" co Formula.wartosc dostaje z FishingMinigame). Zła/nieznana nazwa
     * rzadkości dostaje warning i jest pomijana, zamiast crashować cały load configu.
     */
    private static Map<Integer, Double> nadpisania(YamlConfiguration cfg, Logger log, String path) {
        Map<Integer, Double> wynik = new HashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection(path);
        if (sekcja == null) return wynik;

        for (String nazwaRzadkosci : sekcja.getKeys(false)) {
            try {
                RybaGatunek.Rzadkosc rzadkosc = RybaGatunek.Rzadkosc.valueOf(nazwaRzadkosci.toUpperCase());
                wynik.put(rzadkosc.ordinal(), sekcja.getDouble(nazwaRzadkosci));
            } catch (IllegalArgumentException e) {
                log.warning("fishing-config.yml: '" + path + "." + nazwaRzadkosci + "' to nieznana rzadkosc - pomijam nadpisanie.");
            }
        }
        return wynik;
    }
}
