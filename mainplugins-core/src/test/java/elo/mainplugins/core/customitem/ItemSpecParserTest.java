package elo.mainplugins.core.customitem;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemSpecParserTest {

    private final List<String> warnings = new ArrayList<>();

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    @Test
    void parsesAllFields() throws Exception {
        List<ItemSpec> specs = ItemSpecParser.parseFile(yaml("""
                items:
                  MAGIC_SWORD:
                    material: DIAMOND_SWORD
                    name: "&bMagic"
                    lore: ["&7line"]
                    model: "mainplugins:magic"
                    glint: true
                    unbreakable: true
                    enchants:
                      Sharpness: 5
                      unbreaking: 3
                """), "a.yml", warnings::add);

        assertEquals(1, specs.size());
        ItemSpec s = specs.get(0);
        assertEquals("MAGIC_SWORD", s.id());
        assertEquals("DIAMOND_SWORD", s.material());
        assertEquals("&bMagic", s.name());
        assertEquals(List.of("&7line"), s.lore());
        assertEquals("mainplugins:magic", s.model());
        assertTrue(s.glint());
        assertTrue(s.unbreakable());
        assertEquals(Map.of("sharpness", 5, "unbreaking", 3), s.enchants());
        assertEquals("a.yml", s.sourceFile());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void optionalFieldsHaveDefaults() throws Exception {
        ItemSpec s = ItemSpecParser.parseFile(yaml("items:\n  ROCK:\n    material: STONE\n"), "a.yml", warnings::add).get(0);
        assertNull(s.name());
        assertEquals(List.of(), s.lore());
        assertNull(s.model());
        assertFalse(s.glint());
        assertFalse(s.unbreakable());
        assertEquals(Map.of(), s.enchants());
    }

    @Test
    void entryWithoutMaterialIsSkipped() throws Exception {
        List<ItemSpec> specs = ItemSpecParser.parseFile(yaml("items:\n  BAD:\n    name: x\n  OK:\n    material: STONE\n"), "a.yml", warnings::add);
        assertEquals(List.of("OK"), specs.stream().map(ItemSpec::id).toList());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("BAD"));
    }

    @Test
    void enchantWithLevelBelowOneIsDropped() throws Exception {
        ItemSpec s = ItemSpecParser.parseFile(yaml("items:\n  X:\n    material: STONE\n    enchants:\n      sharpness: 0\n"), "a.yml", warnings::add).get(0);
        assertEquals(Map.of(), s.enchants());
        assertEquals(1, warnings.size());
    }

    @Test
    void fileWithoutItemsSectionGivesNothing() throws Exception {
        assertEquals(List.of(), ItemSpecParser.parseFile(yaml("other: 1\n"), "a.yml", warnings::add));
        assertEquals(1, warnings.size());
    }
}
