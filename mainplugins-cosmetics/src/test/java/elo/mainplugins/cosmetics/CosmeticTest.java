package elo.mainplugins.cosmetics;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticTest {

    private final List<String> warnings = new ArrayList<>();

    private List<Cosmetic> parse(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        return Cosmetic.parseAll(y, warnings::add);
    }

    @Test
    void readsAllTypesWithDefaultsAndPermissions() throws Exception {
        List<Cosmetic> c = parse("""
                hats:
                  - { id: Crown Hat, item: golden_helmet }
                  - { id: magic, custom: MAGIC_HAT, free: true }
                trails:
                  - { id: fire, particle: flame }
                pets:
                  - { id: kitty, mob: cat, icon: string }
                """);
        assertEquals(4, c.size());
        Cosmetic crown = c.get(0);
        assertEquals("crown_hat", crown.id(), "id is normalized");
        assertEquals(Cosmetic.Type.HAT, crown.type());
        assertEquals("GOLDEN_HELMET", crown.value());
        assertEquals("GOLDEN_HELMET", crown.icon(), "hat icon = its item");
        assertFalse(crown.free());
        assertEquals("mainplugins.cosmetics.crown_hat", crown.permission());
        assertTrue(c.get(1).custom());
        assertEquals("MAGIC_HAT", c.get(1).value(), "custom item id kept as written");
        assertEquals("FLAME", c.get(2).value());
        assertEquals("BLAZE_POWDER", c.get(2).icon());
        assertEquals("CAT", c.get(3).value());
        assertEquals("STRING", c.get(3).icon());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void skipsBrokenAndDuplicateEntries() throws Exception {
        List<Cosmetic> c = parse("""
                hats:
                  - { id: a, item: STONE }
                  - { id: a, item: DIRT }
                  - { name: no id }
                trails:
                  - { id: b }
                """);
        assertEquals(1, c.size());
        assertEquals(3, warnings.size(), warnings.toString());
    }

    @Test
    void bundledDefaultsParseCleanly() throws Exception {
        for (String lang : List.of("en", "pl")) {
            warnings.clear();
            YamlConfiguration y = new YamlConfiguration();
            try (var in = getClass().getResourceAsStream("/defaults/" + lang + "/cosmetics.yml")) {
                assertNotNull(in, lang);
                y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            List<Cosmetic> c = Cosmetic.parseAll(y, warnings::add);
            assertEquals(10, c.size(), lang);
            assertTrue(warnings.isEmpty(), lang + ": " + warnings);
        }
    }
}
