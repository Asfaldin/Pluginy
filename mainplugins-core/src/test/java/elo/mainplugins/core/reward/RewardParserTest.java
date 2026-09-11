package elo.mainplugins.core.reward;

import elo.mainplugins.core.api.Reward;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RewardParserTest {

    private final List<String> warnings = new ArrayList<>();
    private final RewardParser parser = new RewardParser(
            m -> Set.of("DIAMOND", "STONE").contains(m.toUpperCase(Locale.ROOT)), warnings::add);

    private List<Reward> parse(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        return parser.parse(y.getList("rewards"), "test.yml rewards");
    }

    @Test
    void parsesBuiltInTypes() throws Exception {
        List<Reward> r = parse("""
                rewards:
                  - money: 500
                  - item: diamond
                    amount: 3
                  - custom: magic_sword
                  - command: "give {player} cake"
                    silent: true
                """);
        assertEquals(List.of(
                new Reward("money", 500.0, 1, false, List.of()),
                new Reward("item", "DIAMOND", 3, false, List.of()),
                new Reward("custom", "magic_sword", 1, false, List.of()),
                new Reward("command", "give {player} cake", 1, true, List.of())), r);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void keepsPluginTypesAndParsesFallback() throws Exception {
        Reward r = parse("""
                rewards:
                  - key: epic
                    fallback:
                      - money: 1000
                """).get(0);
        assertEquals("key", r.type());
        assertEquals("epic", r.value());
        assertEquals(List.of(new Reward("money", 1000.0, 1, false, List.of())), r.fallback());
    }

    @Test
    void skipsEntryWithTwoTypes() throws Exception {
        assertEquals(List.of(), parse("rewards:\n  - money: 5\n    item: STONE\n"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("test.yml rewards [1]"));
    }

    @Test
    void skipsUnknownMaterial() throws Exception {
        assertEquals(List.of(), parse("rewards:\n  - item: NOT_A_BLOCK\n"));
        assertEquals(1, warnings.size());
    }

    @Test
    void skipsMoneyNotAboveZero() throws Exception {
        assertEquals(List.of(), parse("rewards:\n  - money: 0\n"));
        assertEquals(1, warnings.size());
    }

    @Test
    void skipsAmountBelowOne() throws Exception {
        assertEquals(List.of(), parse("rewards:\n  - item: STONE\n    amount: 0\n"));
        assertEquals(1, warnings.size());
    }

    @Test
    void skipsNonMapEntryButKeepsTheRest() throws Exception {
        List<Reward> r = parse("rewards:\n  - just text\n  - money: 5\n");
        assertEquals(List.of(new Reward("money", 5.0, 1, false, List.of())), r);
        assertEquals(1, warnings.size());
    }

    @Test
    void nullListGivesEmpty() {
        assertEquals(List.of(), parser.parse(null, "x"));
        assertTrue(warnings.isEmpty());
    }
}
