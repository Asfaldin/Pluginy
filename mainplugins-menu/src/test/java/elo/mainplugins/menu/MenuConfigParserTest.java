package elo.mainplugins.menu;

import elo.mainplugins.menu.model.MenuConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MenuConfigParserTest {

    private final List<String> warnings = new ArrayList<>();
    private final Set<String> items = Set.of("EMERALD", "GOLD_INGOT", "BOOK", "GRASS_BLOCK", "HOPPER", "FISHING_ROD",
            "COMPASS", "PAPER", "GRAY_STAINED_GLASS_PANE");

    private MenuConfig parse(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        return MenuConfigParser.parse(y, items::contains, warnings::add);
    }

    @Test
    void parsesButtons() throws Exception {
        MenuConfig c = parse("""
                size: 18
                background: ""
                buttons:
                  - { slot: 3, item: EMERALD, name: "&aSklep", lore: ["&7a", "&7b"], command: "/sklep zmenu", requires: MainpluginsShop }
                  - { slot: 4, item: BOOK, command: zadania }
                """);
        assertEquals(18, c.size());
        assertEquals("", c.background());
        assertEquals(2, c.buttons().size());
        assertEquals("sklep zmenu", c.buttons().get(0).command());
        assertEquals(List.of("&7a", "&7b"), c.buttons().get(0).lore());
        assertEquals("MainpluginsShop", c.buttons().get(0).requires());
        assertEquals("", c.buttons().get(1).requires());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void badButtonsAreSkippedWithWarnings() throws Exception {
        MenuConfig c = parse("""
                size: 20
                buttons:
                  - { slot: 30, item: EMERALD }
                  - { slot: 1, item: NOPE }
                  - { slot: 2, item: BOOK }
                  - { slot: 2, item: PAPER }
                """);
        assertEquals(27, c.size());
        assertEquals(1, c.buttons().size());
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void defaultFilesParseWithoutWarnings() throws Exception {
        for (String language : List.of("en", "pl")) {
            warnings.clear();
            YamlConfiguration y = new YamlConfiguration();
            try (var in = getClass().getClassLoader().getResourceAsStream("defaults/" + language + "/menu.yml")) {
                y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            MenuConfig c = MenuConfigParser.parse(y, items::contains, warnings::add);
            assertFalse(c.buttons().isEmpty());
            assertTrue(warnings.isEmpty(), language + ": " + warnings);
        }
    }
}
