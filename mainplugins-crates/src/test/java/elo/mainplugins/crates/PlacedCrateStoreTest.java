package elo.mainplugins.crates;

import elo.mainplugins.crates.model.PlacedCrate;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlacedCrateStoreTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void roundTripsThroughYaml() throws Exception {
        List<PlacedCrate> list = List.of(new PlacedCrate("basic", "world", 10, 64, -5),
                new PlacedCrate("abyss", "world_nether", 0, 70, 0));
        YamlConfiguration y = new YamlConfiguration();
        y.set("placed", PlacedCrateStore.toYaml(list));
        YamlConfiguration back = new YamlConfiguration();
        back.loadFromString(y.saveToString());
        assertEquals(list, PlacedCrateStore.parse(back.getList("placed"), warnings::add));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void skipsBrokenEntries() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("""
                placed:
                  - { crate: basic, world: world, x: 1, y: 2, z: 3 }
                  - { crate: basic, world: world, x: 1, y: 2 }
                  - { world: world, x: 1, y: 2, z: 3 }
                  - nope
                """);
        List<PlacedCrate> list = PlacedCrateStore.parse(y.getList("placed"), warnings::add);
        assertEquals(List.of(new PlacedCrate("basic", "world", 1, 2, 3)), list);
        assertEquals(3, warnings.size());
    }

    @Test
    void missingListIsEmpty() {
        assertTrue(PlacedCrateStore.parse(null, warnings::add).isEmpty());
    }
}
