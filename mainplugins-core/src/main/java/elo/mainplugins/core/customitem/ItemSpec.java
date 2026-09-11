package elo.mainplugins.core.customitem;

import java.util.List;
import java.util.Map;

/** Jeden wpis z pliku items/*.yml jako czyste dane (bez Materiału/Enchantu z Bukkita) - patrz ItemSpecParser. */
public record ItemSpec(String id, String material, String name, List<String> lore, String model, boolean glint,
                       Map<String, Integer> enchants, boolean unbreakable, String sourceFile) {
}
