package elo.mainplugins.quests;

import elo.mainplugins.core.api.Reward;
import elo.mainplugins.core.reward.RewardParser;
import elo.mainplugins.quests.model.After;
import elo.mainplugins.quests.model.CategoryDef;
import elo.mainplugins.quests.model.ItemRef;
import elo.mainplugins.quests.model.QuestConfig;
import elo.mainplugins.quests.model.QuestDef;
import elo.mainplugins.quests.model.QuestSettings;
import elo.mainplugins.quests.model.Requirement;
import elo.mainplugins.quests.model.SlotEntry;
import elo.mainplugins.quests.model.SlotRole;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QuestConfigParserTest {

    private final List<String> warnings = new ArrayList<>();
    private final Set<String> materials = Set.of("KNOWLEDGE_BOOK", "COBBLESTONE", "OAK_LOG", "SHIELD", "WOODEN_PICKAXE",
            "ARROW", "RED_DYE", "GRAY_STAINED_GLASS_PANE", "BOOK", "IRON_PICKAXE");

    private QuestConfig parse(String yaml) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(yaml);
        RewardParser rp = new RewardParser(m -> materials.contains(m.toUpperCase()), warnings::add);
        return QuestConfigParser.parse(y, rp::parse, m -> materials.contains(m.toUpperCase()), warnings::add);
    }

    private static final String FULL = """
            settings:
              icons:
                available: { item: ARROW }
            main-menu:
              layout:
                - { slot: 13, role: CATEGORY_SLOT }
                - { slot: 0, role: FILLER, item: GRAY_STAINED_GLASS_PANE }
            category-order: [main_path, mining]
            titles:
              beginner: "&7[Beginner] "
            categories:
              main_path:
                name: "Main Path"
                icon: { item: KNOWLEDGE_BOOK }
                description: "Start here"
                glow: true
                sequential: true
                page-layout:
                  - { slot: 10, role: QUEST_SLOT }
                  - { slot: 49, role: NAV_BACK }
                quests:
                  - id: 1
                    title: "Welcome"
                    description: ["Hi", "There"]
                    requirement: { type: free }
                    rewards:
                      - item: WOODEN_PICKAXE
                  - id: 2
                    title: "Stone"
                    requirement: { type: items, items: [ { item: COBBLESTONE, amount: 64 }, { custom: TROPHY, amount: 1 } ] }
                    rewards:
                      - money: 50
                    reward-label: "&e50$"
                  - id: 3
                    title: "Pay"
                    requirement: { type: money, amount: 200 }
                    rewards:
                      - title: beginner
                  - id: 4
                    title: "Shield"
                    requirement: { type: have-item, item: { item: SHIELD }, amount: 2 }
                    rewards: []
              mining:
                name: "Mining"
                icon: { item: IRON_PICKAXE }
                after: { category: main_path, quest: 3 }
                requires-unlock: Nether
                page-layout:
                  - { slot: 20, role: QUEST_SLOT }
                quests:
                  - id: 1
                    title: "Coal"
                    requirement: { type: items, items: [ { item: OAK_LOG, amount: 8 } ] }
                    rewards:
                      - money: 10
            """;

    @Test
    void parsesEverything() throws Exception {
        QuestConfig c = parse(FULL);
        assertTrue(warnings.isEmpty(), warnings.toString());

        QuestSettings s = c.settings();
        assertEquals(new ItemRef("ARROW", null, 1), s.available());
        assertEquals(QuestSettings.DEFAULTS.completed(), s.completed());

        assertEquals(List.of(new SlotEntry(13, SlotRole.CATEGORY_SLOT, null),
                new SlotEntry(0, SlotRole.FILLER, "GRAY_STAINED_GLASS_PANE")), c.mainMenu());
        assertEquals(List.of("main_path", "mining"), c.categoryOrder());
        assertEquals("&7[Beginner] ", c.titles().get("beginner"));

        CategoryDef main = c.categories().get("main_path");
        assertTrue(main.glow());
        assertTrue(main.sequential());
        assertNull(main.after());
        assertNull(main.requiresUnlock());
        assertEquals(4, main.quests().size());

        QuestDef q1 = main.quests().get(0);
        assertEquals(List.of("Hi", "There"), q1.description());
        assertInstanceOf(Requirement.Free.class, q1.requirement());
        assertEquals(List.of(new Reward("item", "WOODEN_PICKAXE", 1, false, List.of())), q1.rewards());
        assertNull(q1.rewardLabel());

        Requirement.Items items = (Requirement.Items) main.quests().get(1).requirement();
        assertEquals(List.of(new ItemRef("COBBLESTONE", null, 64), new ItemRef(null, "TROPHY", 1)), items.items());
        assertEquals("&e50$", main.quests().get(1).rewardLabel());

        assertEquals(200.0, ((Requirement.Money) main.quests().get(2).requirement()).amount());
        assertEquals("title", main.quests().get(2).rewards().get(0).type());

        Requirement.HaveItem have = (Requirement.HaveItem) main.quests().get(3).requirement();
        assertEquals(new ItemRef("SHIELD", null, 2), have.item());

        CategoryDef mining = c.categories().get("mining");
        assertEquals(new After("main_path", 3), mining.after());
        assertEquals("nether", mining.requiresUnlock());
        assertFalse(mining.glow());
    }

    @Test
    void badQuestsAreSkipped() throws Exception {
        QuestConfig c = parse("""
                category-order: [a]
                categories:
                  a:
                    name: A
                    icon: { item: BOOK }
                    quests:
                      - id: 1
                        requirement: { type: items, items: [ { item: NOT_AN_ITEM } ] }
                      - id: 2
                        requirement: { type: money, amount: 0 }
                      - id: 3
                        requirement: { type: kill-mobs }
                      - id: 0
                        requirement: { type: free }
                      - id: 5
                        requirement: { type: free }
                      - id: 5
                        requirement: { type: free }
                      - "not a map"
                """);
        assertEquals(List.of(5), c.categories().get("a").quests().stream().map(QuestDef::id).toList());
        // 1: zły item + brak itemów, 2: kwota, 3: typ, 0: id, drugie 5: duplikat, "not a map"
        assertEquals(7, warnings.size(), warnings.toString());
    }

    @Test
    void unknownAfterOrderAndIconAreFixedWithWarnings() throws Exception {
        QuestConfig c = parse("""
                category-order: [a, ghost]
                categories:
                  a:
                    name: A
                    icon: { item: NOT_AN_ITEM }
                    after: { category: ghost, quest: 1 }
                    quests: [ { id: 1, requirement: { type: free } } ]
                  b:
                    name: B
                    icon: { item: BOOK }
                    quests: []
                """);
        CategoryDef a = c.categories().get("a");
        assertEquals(new ItemRef("BOOK", null, 1), a.icon());
        assertNull(a.after());
        assertEquals(List.of("a"), c.categoryOrder());
        // ikona, after, "ghost" w category-order, "b" poza category-order
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void badLayoutEntriesAreSkipped() throws Exception {
        QuestConfig c = parse("""
                main-menu:
                  layout:
                    - { slot: 60, role: CATEGORY_SLOT }
                    - { slot: 1, role: DANCE }
                    - { slot: 2, role: CATEGORY_SLOT }
                    - { slot: 2, role: FILLER }
                    - { slot: 3, role: filler, item: NOT_AN_ITEM }
                """);
        assertEquals(List.of(new SlotEntry(2, SlotRole.CATEGORY_SLOT, null), new SlotEntry(3, SlotRole.FILLER, null)), c.mainMenu());
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void emptyFileGivesDefaults() throws Exception {
        QuestConfig c = parse("");
        assertEquals(QuestSettings.DEFAULTS, c.settings());
        assertTrue(c.categories().isEmpty());
        assertTrue(c.mainMenu().isEmpty());
        assertTrue(warnings.isEmpty());
    }
}
