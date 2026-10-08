package elo.mainplugins.pass;

import elo.mainplugins.core.reward.RewardParser;
import elo.mainplugins.pass.model.PassConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PassConfigParserTest {

    private final List<String> warnings = new ArrayList<>();
    private final Set<String> materials = Set.of("DIAMOND", "BREAD", "GOLDEN_APPLE", "IRON_INGOT", "EXPERIENCE_BOTTLE",
            "EMERALD", "ENCHANTED_GOLDEN_APPLE", "NETHERITE_SCRAP", "NETHERITE_INGOT");

    private PassConfig parse(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        RewardParser rp = new RewardParser(m -> materials.contains(m.toUpperCase()), warnings::add);
        return PassConfigParser.parse(y, rp::parse, warnings::add);
    }

    @Test
    void readsSeasonLevelsDailyAndVote() throws Exception {
        PassConfig c = parse("""
                season: { id: s2, name: "&6S2", ends: "2026-12-31", xp-per-level: 250 }
                xp: { daily-login: 20, playtime-minutes: 5, playtime-xp: 3, mob-kill: 2, vote: 15 }
                premium-permission: my.perm
                levels:
                  - { level: 2, free: [ { money: 50 } ], premium: [ { item: DIAMOND, amount: 2 } ] }
                  - { level: 1, free: [ { item: BREAD, amount: 4 } ] }
                daily:
                  reset-if-missed: false
                  days:
                    - { rewards: [ { money: 10 } ] }
                    - { rewards: [ { item: DIAMOND } ] }
                vote: { enabled: false, rewards: [ { money: 5 } ] }
                """);
        assertEquals("s2", c.season().id());
        assertEquals(LocalDate.of(2026, 12, 31), c.season().ends());
        assertEquals(250, c.season().xpPerLevel());
        assertEquals(5, c.xp().playtimeMinutes());
        assertEquals("my.perm", c.premiumPermission());
        assertEquals(2, c.levels().size());
        assertEquals(1, c.levels().get(0).level(), "levels are sorted");
        assertTrue(c.levels().get(0).premium().isEmpty());
        assertEquals(2, c.maxLevel());
        assertEquals(1, c.level(2).premium().size());
        assertFalse(c.daily().resetIfMissed());
        assertEquals(2, c.daily().days().size());
        assertFalse(c.vote().enabled());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void badValuesAreWarnedAndDefaulted() throws Exception {
        PassConfig c = parse("""
                season: { xp-per-level: 0, ends: "next friday" }
                xp: { mob-kill: -3 }
                levels:
                  - { level: 1, free: [ { money: 1 } ] }
                  - { level: 1, free: [ { money: 2 } ] }
                  - "nonsense"
                """);
        assertEquals(100, c.season().xpPerLevel());
        assertNull(c.season().ends());
        assertEquals(1, c.xp().mobKill());
        assertEquals(1, c.levels().size(), "duplicate level skipped");
        assertTrue(warnings.size() >= 4, warnings.toString());
    }

    @Test
    void bundledDefaultsParseCleanly() throws Exception {
        for (String lang : List.of("en", "pl")) {
            warnings.clear();
            YamlConfiguration y = new YamlConfiguration();
            try (var in = getClass().getResourceAsStream("/defaults/" + lang + "/pass.yml")) {
                assertNotNull(in, lang);
                y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            RewardParser rp = new RewardParser(m -> materials.contains(m.toUpperCase()), warnings::add);
            PassConfig c = PassConfigParser.parse(y, rp::parse, warnings::add);
            assertEquals(10, c.levels().size(), lang);
            assertEquals(7, c.daily().days().size(), lang);
            assertTrue(warnings.isEmpty(), lang + ": " + warnings);
        }
    }
}
