package elo.mainplugins.spawners.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cała konfiguracja mainplugins-spawners (typy + ustawienia + ulepszenia) wczytana z
 * spawnery-typy.yml (patrz SpawnerConfigLoader) - jeden niemutowalny snapshot,
 * podmieniany w całości przy /@reloadspawnery.
 *
 * @param ulepszenia lista ulepszeń w kolejności z pliku (id -> definicja); pusta = brak ulepszeń
 */
public record SpawnerConfig(Map<String, SpawnerTypeDef> typy, SpawnerSettings ustawienia, Map<String, UpgradeDef> ulepszenia) {

    /** Null, jeśli id nie istnieje (typ usunięty z configu albo literówka) - wołający musi to obsłużyć. */
    public SpawnerTypeDef typ(String id) {
        return typy.get(id);
    }

    /** Ulepszenia, które ma ten spawner: wszystkie albo te z jego listy; puste, gdy ulepszenia są wyłączone. */
    public List<UpgradeDef> ulepszeniaTypu(SpawnerTypeDef typ) {
        if (!ustawienia.ulepszeniaWlaczone() || !typ.ulepszenia()) return List.of();
        List<UpgradeDef> out = new ArrayList<>();
        for (UpgradeDef u : ulepszenia.values()) {
            if (u.maxPoziom() <= 1) continue;
            if (typ.listaUlepszen() == null || typ.listaUlepszen().contains(u.id())) out.add(u);
        }
        return out;
    }
}
