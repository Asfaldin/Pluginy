package elo.mainplugins.shop;

import elo.mainplugins.shop.model.Category;
import elo.mainplugins.shop.model.Rounding;
import elo.mainplugins.shop.model.ShopItem;
import elo.mainplugins.shop.model.ShopSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Treść startowa "Mały" (EN i PL) czyta się bez ostrzeżeń i ma w obu językach te same pozycje i ceny. */
class DefaultContentTest {

    private static final Predicate<String> MATERIAL = m -> m.matches("[A-Z0-9_]+");
    static final List<String> CATEGORIES = List.of("blocks", "farming", "ores", "mob-drops", "food");

    private YamlConfiguration yaml(String resource) throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream(resource);
        assertNotNull(in, resource);
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(r);
        }
    }

    private ShopSettings settings(String lang, List<String> w) throws Exception {
        return ShopConfigParser.parseSettings(yaml("defaults/" + lang + "/shop.yml"), MATERIAL, w::add);
    }

    private Category category(String lang, String id, List<String> w) throws Exception {
        return ShopConfigParser.parseCategory(id, yaml("defaults/" + lang + "/categories/" + id + ".yml"), MATERIAL, w::add);
    }

    @Test
    void settingsAreCleanAndMatchTheCodeDefaults() throws Exception {
        List<String> w = new ArrayList<>();
        for (String lang : List.of("en", "pl")) {
            ShopSettings s = settings(lang, w);
            assertEquals(CATEGORIES, s.categoryOrder());
            assertEquals(Rounding.CENTS, s.rounding());
            assertEquals(ShopSettings.defaults().menus(), s.menus(), lang);
            assertEquals(ShopSettings.defaults().buttonMaterials(), s.buttonMaterials(), lang);
            assertEquals(ShopSettings.defaults().dynamic(), s.dynamic(), lang);
        }
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test
    void categoriesAreCleanAndTheSameInBothLanguages() throws Exception {
        List<String> w = new ArrayList<>();
        for (String id : CATEGORIES) {
            Category en = category("en", id, w);
            Category pl = category("pl", id, w);
            assertEquals(en.items(), pl.items(), id);
            assertEquals(en.iconMaterial(), pl.iconMaterial(), id);
            assertNotEquals(en.name(), pl.name(), id);
            assertEquals(8, en.items().size(), id);
            for (ShopItem it : en.items()) {
                assertTrue(it.buyable(), id + " " + it.key());
                if (it.sellable()) assertTrue(it.sell() < it.buy(), id + " " + it.key() + " sells for more than it costs");
            }
        }
        assertTrue(w.isEmpty(), w.toString());
    }
}
