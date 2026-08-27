package elo.mainplugins.farming.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.logging.Logger;

/**
 * Wczytuje farming-config.yml do niemutowalnego {@link FarmingConfig}. Ten sam wzorzec co
 * MenuGuiLoader: plik kopiowany z zasobu TYLKO przy pierwszym uruchomieniu, zła/brakująca
 * wartość dostaje warning i pada na sensowny domyślny odpowiednik dawnej hardkodowanej stałej.
 */
public final class FarmingConfigLoader {

    private FarmingConfigLoader() {}

    public static FarmingConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "farming-config.yml");
        if (!file.exists()) {
            plugin.saveResource("farming-config.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        int min = cfg.getInt("zlota-marchewka.ilosc-min", 2);
        int max = cfg.getInt("zlota-marchewka.ilosc-max", 4);
        if (max < min) {
            log.warning("farming-config.yml: 'zlota-marchewka.ilosc-max' < 'ilosc-min' - uzywam domyslnych 2-4.");
            min = 2;
            max = 4;
        }

        log.info("farming-config.yml: wczytano konfiguracje.");
        return new FarmingConfig(min, max);
    }
}
