package elo.mainplugins.crates.model;

import java.util.List;
import java.util.Map;

/**
 * Cały crates.yml po wczytaniu - kolejność jak w pliku (ważna dla starych skrzynek po numerze tieru).
 * hologramHeight = ile bloków nad blokiem skrzynki wisi napis (settings.hologram-height).
 */
public record CrateConfig(Map<String, KeyDef> keys, Map<String, CrateDef> crates, double hologramHeight) {

    public static final double DEFAULT_HOLOGRAM_HEIGHT = 0.6;

    public List<String> crateIdsInOrder() {
        return List.copyOf(crates.keySet());
    }
}
