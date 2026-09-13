package elo.mainplugins.crates;

import elo.mainplugins.core.api.Reward;
import elo.mainplugins.core.reward.RewardParser;
import elo.mainplugins.crates.model.CrateConfig;
import elo.mainplugins.crates.model.CrateDef;
import elo.mainplugins.crates.model.ItemRef;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CrateConfigParserTest {

    private final List<String> warnings = new ArrayList<>();
    private final Set<String> materials = Set.of("ENDER_CHEST", "TRIPWIRE_HOOK", "DIAMOND", "NETHER_STAR");

    private CrateConfig parse(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        RewardParser rp = new RewardParser(m -> materials.contains(m.toUpperCase()), warnings::add);
        return CrateConfigParser.parse(y, rp::parse, m -> materials.contains(m.toUpperCase()), warnings::add);
    }

    private static final String FULL = """
            settings:
              hologram-height: 1.2
            keys:
              basic_key:
                name: "&eBasic Key"
                item: { item: TRIPWIRE_HOOK }
                lore: ["&7one"]
              universal_key:
                name: "&6Universal"
                item: { custom: MAGIC_KEY }
            crates:
              basic:
                name: "&6Mystery"
                item: { item: ENDER_CHEST }
                keys: [basic_key, universal_key]
                hologram: ["&6Mystery", "&7Click me"]
                prizes:
                  - name: "&bDiamonds"
                    icon: { item: DIAMOND, amount: 4 }
                    weight: 20
                    rewards:
                      - item: DIAMOND
                        amount: 4
                  - name: "&6Legend"
                    icon: { item: NETHER_STAR }
                    weight: 1
                    announce: true
                    rewards:
                      - money: 500
                      - key: basic_key
            """;

    @Test
    void parsesKeysCratesAndPrizes() throws Exception {
        CrateConfig c = parse(FULL);
        assertEquals(List.of("basic_key", "universal_key"), List.copyOf(c.keys().keySet()));
        assertEquals(new ItemRef(null, "MAGIC_KEY", 1), c.keys().get("universal_key").item());
        CrateDef basic = c.crates().get("basic");
        assertEquals("&6Mystery", basic.name());
        assertEquals(List.of("basic_key", "universal_key"), basic.keys());
        assertEquals(2, basic.prizes().size());
        assertEquals(new ItemRef("DIAMOND", null, 4), basic.prizes().get(0).icon());
        assertEquals(List.of(new Reward("item", "DIAMOND", 4, false, List.of())), basic.prizes().get(0).rewards());
        assertTrue(basic.prizes().get(1).announce());
        assertEquals(2, basic.prizes().get(1).rewards().size());
        assertEquals(21, basic.totalWeight());
        assertEquals(List.of("basic"), c.crateIdsInOrder());
        assertEquals(List.of("&6Mystery", "&7Click me"), basic.hologram());
        assertEquals(1.2, c.hologramHeight(), 1e-9);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void hologramDefaultsWhenMissingOrInvalid() throws Exception {
        CrateConfig c = parse("settings:\n  hologram-height: 99\n");
        assertEquals(CrateConfig.DEFAULT_HOLOGRAM_HEIGHT, c.hologramHeight(), 1e-9);
        assertEquals(1, warnings.size());
        assertEquals(CrateConfig.DEFAULT_HOLOGRAM_HEIGHT, parse("").hologramHeight(), 1e-9);
    }

    @Test
    void unknownKeyReferenceIsDroppedAndCrateWithoutKeysSkipped() throws Exception {
        CrateConfig c = parse("""
                keys:
                  k: { name: K, item: { item: TRIPWIRE_HOOK } }
                crates:
                  a:
                    name: A
                    item: { item: ENDER_CHEST }
                    keys: [k, nope]
                    prizes: [ { name: P, icon: { item: DIAMOND }, weight: 1, rewards: [ { money: 1 } ] } ]
                  b:
                    name: B
                    item: { item: ENDER_CHEST }
                    keys: [nope]
                    prizes: [ { name: P, icon: { item: DIAMOND }, weight: 1, rewards: [ { money: 1 } ] } ]
                """);
        assertEquals(List.of("k"), c.crates().get("a").keys());
        assertFalse(c.crates().containsKey("b"));
        // a: nieznany "nope"; b: nieznany "nope" + brak klucza
        assertEquals(3, warnings.size());
    }

    @Test
    void badPrizesAreSkippedAndCrateWithoutPrizesSkipped() throws Exception {
        CrateConfig c = parse("""
                keys:
                  k: { name: K, item: { item: TRIPWIRE_HOOK } }
                crates:
                  a:
                    name: A
                    item: { item: ENDER_CHEST }
                    keys: [k]
                    prizes:
                      - { name: ZeroWeight, icon: { item: DIAMOND }, weight: 0, rewards: [ { money: 1 } ] }
                      - { name: NoRewards, icon: { item: DIAMOND }, weight: 5, rewards: [] }
                      - { name: BadIcon, icon: { item: NOT_A_BLOCK }, weight: 5, rewards: [ { money: 1 } ] }
                """);
        assertFalse(c.crates().containsKey("a"));
        assertEquals(4, warnings.size());
    }

    @Test
    void keyWithBadItemIsSkipped() throws Exception {
        CrateConfig c = parse("keys:\n  k: { name: K, item: { item: NOT_A_BLOCK } }\ncrates: {}\n");
        assertTrue(c.keys().isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void defaultFileParsesWithoutWarnings() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        try (var in = getClass().getClassLoader().getResourceAsStream("crates.yml")) {
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Set<String> all = Set.of("ENDER_CHEST", "SHULKER_BOX", "BEACON", "TRIPWIRE_HOOK", "DIRT", "IRON_INGOT",
                "DIAMOND", "NETHERITE_INGOT", "NETHER_STAR", "IRON_BLOCK", "EXPERIENCE_BOTTLE", "NETHERITE_SCRAP",
                "ELYTRA", "ENCHANTED_GOLDEN_APPLE", "TOTEM_OF_UNDYING");
        RewardParser rp = new RewardParser(m -> all.contains(m.toUpperCase()), warnings::add);
        CrateConfig c = CrateConfigParser.parse(y, rp::parse, m -> all.contains(m.toUpperCase()), warnings::add);
        assertEquals(List.of("basic", "abyss", "darkstar"), c.crateIdsInOrder());
        assertEquals(4, c.keys().size());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void emptyFileGivesEmptyConfig() throws Exception {
        CrateConfig c = parse("");
        assertTrue(c.keys().isEmpty());
        assertTrue(c.crates().isEmpty());
    }
}
