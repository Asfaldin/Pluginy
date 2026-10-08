package elo.mainplugins.claims;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClaimStoreTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void ownerTrustedAndBypassCanUse(@TempDir File dir) {
        ClaimStore s = new ClaimStore(new File(dir, "data.yml"), warnings::add);
        UUID owner = UUID.randomUUID(), friend = UUID.randomUUID(), stranger = UUID.randomUUID();
        assertTrue(s.canUse("world", 0, 0, stranger, false), "free land");
        s.claim("World", 3, -2, owner);
        assertEquals(owner, s.ownerAt("world", 3, -2), "world name is case-insensitive");
        assertTrue(s.canUse("world", 3, -2, owner, false));
        assertFalse(s.canUse("world", 3, -2, stranger, false));
        assertTrue(s.canUse("world", 3, -2, stranger, true), "bypass");
        s.owner(owner, null).trusted.add(friend);
        assertTrue(s.canUse("world", 3, -2, friend, false));
        assertEquals(1, s.count(owner));
        assertTrue(s.unclaim("world", 3, -2));
        assertFalse(s.unclaim("world", 3, -2));
    }

    @Test
    void savesAndLoadsIncludingDottedWorldNames(@TempDir File dir) {
        File f = new File(dir, "data.yml");
        ClaimStore s = new ClaimStore(f, warnings::add);
        UUID owner = UUID.randomUUID(), friend = UUID.randomUUID();
        s.claim("my.world", 10, 20, owner);
        s.claim("world", -1, -1, owner);
        ClaimStore.Owner o = s.owner(owner, null);
        o.trusted.add(friend);
        o.pvp = true;
        s.save();

        ClaimStore loaded = new ClaimStore(f, warnings::add);
        loaded.load();
        assertEquals(owner, loaded.ownerAt("my.world", 10, 20));
        assertEquals(owner, loaded.ownerAt("world", -1, -1));
        assertEquals(2, loaded.chunksOf(owner).size());
        assertTrue(loaded.ownerIfExists(owner).pvp);
        assertTrue(loaded.canUse("world", -1, -1, friend, false));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void newOwnerGetsDefaultFlags(@TempDir File dir) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("claims: { default-flags: { pvp: true, explosions: false, mob-griefing: true } }");
        ClaimsConfig c = ClaimsConfig.parse(y, warnings::add);
        ClaimStore.Owner o = new ClaimStore(new File(dir, "d.yml"), warnings::add).owner(UUID.randomUUID(), c);
        assertTrue(o.pvp);
        assertFalse(o.explosions);
        assertTrue(o.mobGriefing);
    }

    @Test
    void bundledDefaultsParseCleanly() throws Exception {
        for (String lang : List.of("en", "pl")) {
            warnings.clear();
            YamlConfiguration y = new YamlConfiguration();
            try (var in = getClass().getResourceAsStream("/defaults/" + lang + "/claims.yml")) {
                assertNotNull(in, lang);
                y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            ClaimsConfig c = ClaimsConfig.parse(y, warnings::add);
            assertEquals(4, c.maxChunksFree(), lang);
            assertTrue(c.disabledWorlds().contains("world_nether"), lang);
            assertTrue(warnings.isEmpty(), lang + ": " + warnings);
        }
    }
}
