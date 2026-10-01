package elo.mainplugins.blocks;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlocksTest {

    @Test
    void stanyJakWAplikacji() {
        // Te same wartości co w desktop-app/src/lib/blockModel.test.ts (noteState).
        assertEquals("minecraft:note_block[instrument=harp,note=0,powered=false]", BlockStates.VANILLA);
        assertEquals("minecraft:note_block[instrument=harp,note=1,powered=false]", BlockStates.state(1));
        assertEquals("minecraft:note_block[instrument=harp,note=0,powered=true]", BlockStates.state(25));
        assertEquals("minecraft:note_block[instrument=basedrum,note=0,powered=false]", BlockStates.state(50));
        assertEquals("minecraft:note_block[instrument=pling,note=24,powered=true]", BlockStates.state(BlockStates.MAX_STATE));
        Set<String> all = new HashSet<>();
        for (int i = 0; i <= BlockStates.MAX_STATE; i++) all.add(BlockStates.state(i));
        assertEquals(BlockStates.MAX_STATE + 1, all.size());
        assertThrows(IllegalArgumentException.class, () -> BlockStates.state(BlockStates.MAX_STATE + 1));
    }

    @Test
    void czytaBlokZAplikacji() {
        BlockDef d = BlockLoader.parse("""
                {"format_version":1,"id":"ruby_ore","name":"Ruby ore","bones":[],"animations":[],
                 "block":{"state":7,"hardness":3,"tool":"pickaxe","requiresTool":true,
                          "drop":{"type":"item","item":"minecraft:diamond","min":3,"max":1},"sound":"deepslate"}}
                """);
        assertEquals("ruby_ore", d.id());
        assertEquals("Ruby ore", d.name());
        assertEquals(7, d.state());
        assertEquals(3.0, d.hardness());
        assertEquals("pickaxe", d.tool());
        assertTrue(d.requiresTool());
        assertEquals("item", d.dropType());
        assertEquals(1, d.dropMin());
        assertEquals(3, d.dropMax());
        assertEquals("deepslate", d.sound());
    }

    @Test
    void zlePlikiIDomyslne() {
        assertThrows(IllegalArgumentException.class, () -> BlockLoader.parse("{\"id\":\"Zly Id\",\"block\":{\"state\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> BlockLoader.parse("{\"id\":\"a\"}"));
        assertThrows(IllegalArgumentException.class, () -> BlockLoader.parse("{\"id\":\"a\",\"block\":{\"state\":0}}"));
        BlockDef d = BlockLoader.parse("{\"id\":\"a\",\"block\":{\"state\":2,\"tool\":\"none\",\"requiresTool\":true,\"sound\":\"??\"}}");
        assertFalse(d.requiresTool());
        assertEquals("self", d.dropType());
        assertEquals("stone", d.sound());
    }

    @Test
    void tempoKopania() {
        // Ręka na bloku o twardości note blocka = bez zmian.
        assertEquals(1.0, BreakSpeed.multiplier(1, 1, 0.8, true), 1e-9);
        // Kamień (1.5) kopany ręką bez wymaganego narzędzia: 0.8/1.5 * 30/100.
        assertEquals(0.8 / 1.5 * 0.3, BreakSpeed.multiplier(1, 1, 1.5, false), 1e-9);
        // Żelazny kilof (6) na bloku jak kamień, a na note blocku kilof nic nie daje (1).
        assertEquals(6 * 0.8 / 1.5, BreakSpeed.multiplier(1, 6, 1.5, true), 1e-9);
        // Siekiera przyspiesza note block - blok "na kilof" kopany siekierą musi to odjąć.
        assertEquals(0.8 / 1.5 / 6 * 0.3, BreakSpeed.multiplier(6, 1, 1.5, false), 1e-9);
        assertEquals(0, BreakSpeed.multiplier(1, 1, -1, true));
        assertEquals(BreakSpeed.MAX_MULTIPLIER, BreakSpeed.multiplier(1, 1, 0, true));
        assertEquals(BreakSpeed.MAX_MULTIPLIER, BreakSpeed.multiplier(1, 1000, 0.0001, true));
    }
}
