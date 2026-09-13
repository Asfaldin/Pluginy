package elo.mainplugins.crates.model;

import java.util.List;
import java.util.Map;

/** Cały crates.yml po wczytaniu - kolejność jak w pliku (ważna dla starych skrzynek po numerze tieru). */
public record CrateConfig(Map<String, KeyDef> keys, Map<String, CrateDef> crates) {

    public List<String> crateIdsInOrder() {
        return List.copyOf(crates.keySet());
    }
}
