package elo.mainplugins.spawners;

import elo.mainplugins.core.util.AsyncConfigSaver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Poziomy ulepszeń spawnerów, zapisane w poziomy.yml: właściciel -> typ -> id ulepszenia (np. ilosc, szybkosc, drop).
 * Właściciel = właściciel wyspy (gdy jest Skyblock i gracz ma wyspę) albo sam gracz - ten sam,
 * którego SpawnerManager zapisuje przy postawionym spawnerze. Brak wpisu = poziom 1.
 */
final class SpawnerLevels {

    private final YamlConfiguration plik;
    private final AsyncConfigSaver saver;

    SpawnerLevels(Plugin plugin) {
        File f = new File(plugin.getDataFolder(), "poziomy.yml");
        if (!f.exists()) {
            f.getParentFile().mkdirs();
            try { f.createNewFile(); } catch (IOException ignored) {}
        }
        this.plik = YamlConfiguration.loadConfiguration(f);
        this.saver = new AsyncConfigSaver(plugin, plik, f, 30);
    }

    int poziom(UUID wlasciciel, String typ, String ulepszenie) {
        return Math.max(1, plik.getInt(wlasciciel + "." + typ + "." + ulepszenie, 1));
    }

    void ustaw(UUID wlasciciel, String typ, String ulepszenie, int poziom) {
        plik.set(wlasciciel + "." + typ + "." + ulepszenie, poziom);
        saver.oznaczZmiane();
    }

    void zamknij() {
        saver.zamknij();
    }
}
