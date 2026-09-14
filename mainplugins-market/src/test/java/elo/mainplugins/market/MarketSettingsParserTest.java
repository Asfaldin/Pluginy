package elo.mainplugins.market;

import elo.mainplugins.market.model.ButtonDef;
import elo.mainplugins.market.model.MarketSettings;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class MarketSettingsParserTest {

    private static final Predicate<String> MATERIAL = m -> m.matches("[A-Z0-9_]+");

    private MarketSettings parse(String text, List<String> warnings) throws InvalidConfigurationException {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return MarketSettingsParser.parse(y, MATERIAL, warnings::add);
    }

    @Test
    void emptyFileGivesDefaultsWithoutWarnings() throws Exception {
        List<String> w = new ArrayList<>();
        assertEquals(MarketSettings.defaults(), parse("", w));
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test
    void readsEveryField() throws Exception {
        List<String> w = new ArrayList<>();
        MarketSettings s = parse("""
                limits:
                  default: 7
                min-price: 5
                max-price: 500
                expire-days: 3
                mailbox: false
                tax-percent: 10
                menu:
                  title: "&6Targ"
                  background: BLACK_STAINED_GLASS_PANE
                  buttons:
                    prev: {slot: 36, material: ARROW}
                """, w);
        assertTrue(w.isEmpty(), w.toString());
        assertEquals(7, s.defaultLimit());
        assertEquals(5, s.minPrice());
        assertEquals(500, s.maxPrice());
        assertEquals(3, s.expireDays());
        assertFalse(s.mailbox());
        assertEquals(10, s.taxPercent());
        assertEquals("&6Targ", s.title());
        assertEquals("BLACK_STAINED_GLASS_PANE", s.background());
        assertEquals(new ButtonDef(36, "ARROW"), s.buttons().get("prev"));
        // brakujące przyciski biorą wartości domyślne
        assertEquals(MarketSettings.defaults().buttons().get("next"), s.buttons().get("next"));
    }

    @Test
    void taxIsClamped() throws Exception {
        List<String> w = new ArrayList<>();
        assertEquals(100, parse("tax-percent: 150", w).taxPercent());
        assertEquals(1, w.size());
        assertTrue(w.get(0).contains("tax-percent"));
    }

    @Test
    void badPriceRangeFallsBackToDefaults() throws Exception {
        List<String> w = new ArrayList<>();
        MarketSettings s = parse("min-price: 5\nmax-price: 0", w);
        assertEquals(MarketSettings.defaults().minPrice(), s.minPrice());
        assertEquals(MarketSettings.defaults().maxPrice(), s.maxPrice());
        assertFalse(w.isEmpty());
    }

    @Test
    void buttonOnOfferSlotOrDuplicateIsDropped() throws Exception {
        List<String> w = new ArrayList<>();
        MarketSettings s = parse("""
                menu:
                  buttons:
                    mine: {slot: 10, material: HOPPER}
                    prev: {slot: 45, material: ARROW}
                    next: {slot: 45, material: ARROW}
                """, w);
        assertNull(s.buttons().get("mine"));
        assertEquals(45, s.buttons().get("prev").slot());
        assertNull(s.buttons().get("next"));
        assertTrue(w.stream().anyMatch(x -> x.contains("buttons.mine")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.contains("buttons.next")), w.toString());
    }

    @Test
    void unknownMaterialUsesDefault() throws Exception {
        List<String> w = new ArrayList<>();
        MarketSettings s = parse("menu:\n  background: not a material", w);
        assertEquals(MarketSettings.defaults().background(), s.background());
        assertEquals(1, w.size());
    }
}
