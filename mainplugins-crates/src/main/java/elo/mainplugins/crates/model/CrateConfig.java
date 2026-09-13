package elo.mainplugins.crates.model;

import java.util.List;
import java.util.Map;

/**
 * Cały crates.yml po wczytaniu - kolejność jak w pliku (ważna dla starych skrzynek po numerze tieru).
 * hologramHeight = ile bloków nad blokiem skrzynki wisi napis (settings.hologram-height).
 * placedBlockFromItem = postawiony blok przyjmuje wygląd przedmiotu skrzynki (settings.placed-block-from-item).
 */
public record CrateConfig(Map<String, KeyDef> keys, Map<String, CrateDef> crates, double hologramHeight,
                          boolean placedBlockFromItem) {

    public static final double DEFAULT_HOLOGRAM_HEIGHT = 0.6;

    public static CrateConfig empty() {
        return new CrateConfig(Map.of(), Map.of(), DEFAULT_HOLOGRAM_HEIGHT, true);
    }

    public List<String> crateIdsInOrder() {
        return List.copyOf(crates.keySet());
    }
}
