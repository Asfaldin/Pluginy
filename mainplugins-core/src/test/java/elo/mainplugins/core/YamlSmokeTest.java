package elo.mainplugins.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Sprawdza, że YamlConfiguration z Paper API działa w zwykłym teście, bez serwera. */
class YamlSmokeTest {

    @Test
    void yamlConfigurationWorksWithoutServer() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("a:\n  b: 5\n");
        assertEquals(5, yaml.getInt("a.b"));
    }
}
