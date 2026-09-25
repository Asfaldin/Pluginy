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

/**
 * Treść startowa (to, co plugin wgrywa sam na nowym serwerze): PL = Duży sklep z aplikacji, EN = dawny Mały.
 * Czyta się bez ostrzeżeń, każda kategoria z listy ma swój plik, nic nie skupuje drożej, niż sprzedaje.
 */
class DefaultContentTest {

    private static final Predicate<String> MATERIAL = m -> m.matches("[A-Z0-9_]+");

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

    @Test
    void settingsAreClean() throws Exception {
        List<String> w = new ArrayList<>();
        ShopSettings pl = settings("pl", w);
        assertEquals(10, pl.categoryOrder().size());
        assertEquals(Rounding.WHOLE, pl.rounding());
        assertTrue(pl.statsEnabled());
        assertTrue(pl.extras().rankBonuses().isEmpty());
        ShopSettings en = settings("en", w);
        assertEquals(List.of("blocks", "farming", "ores", "mob-drops", "food"), en.categoryOrder());
        assertEquals(ShopSettings.defaults().menus(), en.menus());
        for (ShopSettings s : List.of(pl, en)) {
            assertEquals(ShopSettings.defaults().buttonMaterials(), s.buttonMaterials());
            assertEquals(ShopSettings.defaults().dynamic(), s.dynamic());
        }
        assertTrue(w.isEmpty(), w.toString());
    }

    @Test
    void langFilesHaveTheSameKeys() throws Exception {
        assertEquals(yaml("lang/en.yml").getKeys(true), yaml("lang/pl.yml").getKeys(true));
    }

    @Test
    void everyListedCategoryIsCleanAndNeverBuysBackForMore() throws Exception {
        List<String> w = new ArrayList<>();
        for (String lang : List.of("pl", "en")) {
            for (String id : settings(lang, w).categoryOrder()) {
                Category c = ShopConfigParser.parseCategory(id, yaml("defaults/" + lang + "/categories/" + id + ".yml"), MATERIAL, w::add);
                List<ShopItem> all = new ArrayList<>(c.items());
                if (c.rotation() != null) all.addAll(c.rotation().pool());
                assertFalse(all.isEmpty(), lang + "/" + id);
                for (ShopItem it : all) {
                    if (!it.buyable() || !it.sellable() || it.buy() == 0) continue;
                    double buyEach = it.buy() / it.amount();
                    double sellEach = it.sell() / it.sellAmount();
                    assertTrue(sellEach < buyEach, lang + "/" + id + " " + it.key() + " sells for more than it costs");
                }
            }
        }
        assertTrue(w.isEmpty(), w.toString());
    }
}
