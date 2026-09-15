package elo.mainplugins.market;

import elo.mainplugins.market.model.MarketSettings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Domyślne pliki z jara: market.yml bez ostrzeżeń i równy wartościom domyślnym, języki z tymi samymi kluczami. */
class DefaultContentTest {

    private YamlConfiguration yaml(String resource) throws Exception {
        try (Reader r = new InputStreamReader(getClass().getClassLoader().getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(r);
        }
    }

    @Test
    void defaultMarketYmlParsesCleanly() throws Exception {
        List<String> warnings = new ArrayList<>();
        MarketSettings s = MarketSettingsParser.parse(yaml("market.yml"), m -> m.matches("[A-Z0-9_]+"), warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(MarketSettings.defaults(), s);
    }

    @Test
    void langFilesHaveTheSameKeys() throws Exception {
        assertEquals(yaml("lang/en.yml").getKeys(true), yaml("lang/pl.yml").getKeys(true));
    }
}
