package elo.mainplugins.core.command;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CommandSettingsParserTest {

    private final List<String> warnings = new ArrayList<>();

    private Map<String, CommandSetting> parse(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        return CommandSettingsParser.parse(y, warnings::add);
    }

    @Test
    void readsNameAliasesAndEnabled() throws Exception {
        Map<String, CommandSetting> s = parse("""
                commands:
                  przelej:
                    name: Pay
                    aliases: [przelej, PRZELEW]
                  "@reloadsklep":
                    enabled: false
                """);
        assertEquals(new CommandSetting("przelej", true, "pay", List.of("przelej", "przelew")), s.get("przelej"));
        assertEquals(new CommandSetting("@reloadsklep", false, "@reloadsklep", List.of()), s.get("@reloadsklep"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void invalidLabelIsDropped() throws Exception {
        Map<String, CommandSetting> s = parse("commands:\n  sklep:\n    name: \"my shop\"\n    aliases: [ok, \"bad one\"]\n");
        assertEquals(new CommandSetting("sklep", true, "sklep", List.of("ok")), s.get("sklep"));
        assertEquals(2, warnings.size());
    }

    @Test
    void labelUsedTwiceIsKeptForFirstOnly() throws Exception {
        Map<String, CommandSetting> s = parse("""
                commands:
                  przelej:
                    name: pay
                  portfel:
                    name: balance
                    aliases: [pay, money]
                """);
        assertEquals(List.of("money"), s.get("portfel").aliases());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("pay"));
    }

    @Test
    void missingSectionGivesEmptyMap() throws Exception {
        assertEquals(Map.of(), parse("other: 1\n"));
    }
}
