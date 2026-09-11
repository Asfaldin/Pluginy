package elo.mainplugins.core.customitem;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultItemMergerTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    private static final String DEFAULTS = """
            items:
              A:
                material: STONE
              B:
                material: DIAMOND_SWORD
                lore: ["&7one", "&7two"]
                enchants:
                  sharpness: 5
            """;

    @Test
    void findsOnlyMissingIds() throws Exception {
        ItemCatalog catalog = ItemCatalog.build(List.of(
                new ItemSpec("a", "STONE", null, List.of(), null, false, Map.of(), false, "x.yml")), s -> {});
        assertEquals(List.of("B"), DefaultItemMerger.missingIds(yaml(DEFAULTS), catalog));
    }

    @Test
    void copiedEntriesSurviveSaveAndReload() throws Exception {
        YamlConfiguration target = yaml("items:\n  OLD:\n    material: DIRT\n");
        DefaultItemMerger.copyEntries(yaml(DEFAULTS), target, List.of("B"));

        YamlConfiguration reloaded = yaml(target.saveToString());
        assertEquals("DIRT", reloaded.getString("items.OLD.material"));
        assertEquals("DIAMOND_SWORD", reloaded.getString("items.B.material"));
        assertEquals(List.of("&7one", "&7two"), reloaded.getStringList("items.B.lore"));
        assertEquals(5, reloaded.getInt("items.B.enchants.sharpness"));
        assertFalse(reloaded.contains("items.A"));
    }
}
