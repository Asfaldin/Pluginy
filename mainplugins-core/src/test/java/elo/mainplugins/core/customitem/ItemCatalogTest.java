package elo.mainplugins.core.customitem;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ItemCatalogTest {

    private final List<String> warnings = new ArrayList<>();

    private static ItemSpec spec(String id, String material, String file) {
        return new ItemSpec(id, material, null, List.of(), null, false, Map.of(), false, file);
    }

    @Test
    void lookupIsCaseInsensitive() {
        ItemCatalog c = ItemCatalog.build(List.of(spec("GENERATOR_BRUK_T1", "STONE", "a.yml")), warnings::add);
        assertTrue(c.contains("generator_bruk_t1"));
        assertEquals("GENERATOR_BRUK_T1", c.get("Generator_Bruk_T1").id());
        assertNull(c.get(null));
    }

    @Test
    void duplicateKeepsFirstAndWarns() {
        ItemCatalog c = ItemCatalog.build(List.of(spec("X", "STONE", "a.yml"), spec("x", "DIRT", "b.yml")), warnings::add);
        assertEquals("STONE", c.get("x").material());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("a.yml") && warnings.get(0).contains("b.yml"));
    }

    @Test
    void idsKeepOriginalSpelling() {
        ItemCatalog c = ItemCatalog.build(List.of(spec("Magic_Sword", "STONE", "a.yml")), warnings::add);
        assertEquals(Set.of("Magic_Sword"), c.ids());
    }
}
