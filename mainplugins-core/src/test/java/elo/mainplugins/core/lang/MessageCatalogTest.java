package elo.mainplugins.core.lang;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageCatalogTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void firstLayerWins() {
        MessageCatalog c = new MessageCatalog(List.of(Map.of("a", "PL"), Map.of("a", "EN")), warnings::add);
        assertEquals("PL", c.resolve("a", Map.of()));
    }

    @Test
    void fallsBackToLaterLayer() {
        MessageCatalog c = new MessageCatalog(List.of(Map.of(), Map.of("a", "EN")), warnings::add);
        assertEquals("EN", c.resolve("a", Map.of()));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingKeyReturnsKeyAndWarnsOnce() {
        MessageCatalog c = new MessageCatalog(List.of(Map.of()), warnings::add);
        assertEquals("x.y", c.resolve("x.y", Map.of()));
        assertEquals("x.y", c.resolve("x.y", Map.of()));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("x.y"));
    }

    @Test
    void replacesPlaceholders() {
        MessageCatalog c = new MessageCatalog(List.of(Map.of("m", "Hi {player}, +{amount}")), warnings::add);
        assertEquals("Hi Steve, +500", c.resolve("m", Map.of("player", "Steve", "amount", "500")));
    }

    @Test
    void flattenReadsNestedKeysAndJoinsLists() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("reward:\n  money: \"&aYou got {amount}\"\nhelp:\n  - line one\n  - line two\n");
        Map<String, String> flat = MessageCatalog.flatten(y);
        assertEquals("&aYou got {amount}", flat.get("reward.money"));
        assertEquals("line one\nline two", flat.get("help"));
        assertFalse(flat.containsKey("reward"));
    }
}
