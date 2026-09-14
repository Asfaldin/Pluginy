package elo.mainplugins.quests;

import elo.mainplugins.core.reward.RewardParser;
import elo.mainplugins.quests.model.CategoryDef;
import elo.mainplugins.quests.model.QuestConfig;
import elo.mainplugins.quests.model.QuestDef;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Domyślne pliki z jara muszą czytać się bez żadnego ostrzeżenia i mieć ten sam układ w EN i PL. */
class DefaultContentTest {

    private static final Predicate<String> LOOKS_LIKE_MATERIAL = m -> m.matches("[A-Z0-9_]+");
    private static final Set<String> REWARD_TYPES = Set.of("money", "item", "custom", "command", "crate", "key", "title", "unlock");

    private YamlConfiguration yaml(String resource) throws Exception {
        try (Reader r = new InputStreamReader(getClass().getClassLoader().getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(r);
        }
    }

    private QuestConfig load(String resource, List<String> warnings) throws Exception {
        RewardParser rp = new RewardParser(LOOKS_LIKE_MATERIAL, warnings::add);
        return QuestConfigParser.parse(yaml(resource), rp::parse, LOOKS_LIKE_MATERIAL, warnings::add);
    }

    @Test
    void englishAndPolishParseCleanlyWithTheSameShape() throws Exception {
        List<String> warnings = new ArrayList<>();
        QuestConfig en = load("defaults/quests-en.yml", warnings);
        QuestConfig pl = load("defaults/quests-pl.yml", warnings);
        assertTrue(warnings.isEmpty(), warnings.toString());

        assertEquals(List.of("main_path", "mining", "farming", "hunting", "fishing", "woodcutting"), en.categoryOrder());
        assertEquals(en.categoryOrder(), pl.categoryOrder());
        assertEquals("main_path", en.mainPath().id());
        assertEquals(10, en.mainPath().quests().size());
        for (String id : en.categoryOrder()) {
            CategoryDef e = en.categories().get(id);
            CategoryDef p = pl.categories().get(id);
            if (!e.mainPath()) assertEquals(5, e.quests().size(), id);
            assertEquals(e.quests().stream().map(QuestDef::id).toList(), p.quests().stream().map(QuestDef::id).toList(), id);
            assertEquals(e.after(), p.after(), id);
            for (int i = 0; i < e.quests().size(); i++) {
                assertEquals(e.quests().get(i).requirement(), p.quests().get(i).requirement(), id + "#" + i);
                assertEquals(e.quests().get(i).rewards(), p.quests().get(i).rewards(), id + "#" + i);
                assertFalse(e.quests().get(i).rewards().isEmpty(), id + "#" + i);
                e.quests().get(i).rewards().forEach(r -> assertTrue(REWARD_TYPES.contains(r.type()), r.type()));
                // Skrzynka w domyślnych questach zawsze z nagrodą zastępczą - plugin działa bez Skrzynek.
                final String at = id + "#" + i;
                e.quests().get(i).rewards().stream().filter(r -> r.type().equals("crate"))
                        .forEach(r -> assertFalse(r.fallback().isEmpty(), at + " crate without fallback"));
            }
        }
        assertEquals(en.titles().keySet(), pl.titles().keySet());
    }

    @Test
    void langFilesHaveTheSameKeys() throws Exception {
        Set<String> en = yaml("lang/en.yml").getKeys(true);
        Set<String> pl = yaml("lang/pl.yml").getKeys(true);
        assertEquals(en, pl);
    }
}
