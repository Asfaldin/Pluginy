package elo.mainplugins.spawners.config;

import java.util.Map;

/**
 * Cała konfiguracja mainplugins-spawners (typy + ustawienia) wczytana z
 * spawnery-typy.yml (patrz SpawnerConfigLoader) - jeden niemutowalny snapshot,
 * podmieniany w całości przy /@reloadspawnery.
 */
public record SpawnerConfig(Map<String, SpawnerTypeDef> typy, SpawnerSettings ustawienia) {

    /** Null, jeśli id nie istnieje (typ usunięty z configu albo literówka) - wołający musi to obsłużyć. */
    public SpawnerTypeDef typ(String id) {
        return typy.get(id);
    }
}
