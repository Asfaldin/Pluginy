package elo.mainplugins.quests;

import elo.mainplugins.quests.model.After;
import elo.mainplugins.quests.model.CategoryDef;
import elo.mainplugins.quests.model.ItemRef;
import elo.mainplugins.quests.model.QuestDef;
import elo.mainplugins.quests.model.Requirement;
import elo.mainplugins.quests.model.SlotEntry;
import elo.mainplugins.quests.model.SlotRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static elo.mainplugins.quests.QuestRules.CategoryState;
import static elo.mainplugins.quests.QuestRules.QuestState;
import static org.junit.jupiter.api.Assertions.*;

class QuestRulesTest {

    private static QuestDef q(int id) {
        return new QuestDef(id, "Q" + id, List.of(), new Requirement.Free(), List.of(), null);
    }

    private static CategoryDef cat(String id, boolean sequential, After after, String unlock, List<QuestDef> quests) {
        return new CategoryDef(id, id, new ItemRef("BOOK", null, 1), "", false, sequential, after, unlock, List.of(), quests);
    }

    @Test
    void sequentialLocksUntilPreviousIsDone() {
        CategoryDef c = cat("a", true, null, null, List.of(q(1), q(5), q(9)));
        assertEquals(QuestState.AVAILABLE, QuestRules.state(c, 0, Set.of()));
        assertEquals(QuestState.LOCKED, QuestRules.state(c, 1, Set.of()));
        assertEquals(QuestState.DONE, QuestRules.state(c, 0, Set.of(1)));
        assertEquals(QuestState.AVAILABLE, QuestRules.state(c, 1, Set.of(1)));
        assertEquals(QuestState.LOCKED, QuestRules.state(c, 2, Set.of(1)));
    }

    @Test
    void notSequentialIsAlwaysAvailable() {
        CategoryDef c = cat("a", false, null, null, List.of(q(1), q(2)));
        assertEquals(QuestState.AVAILABLE, QuestRules.state(c, 1, Set.of()));
    }

    @Test
    void categoryStates() {
        Map<String, Set<Integer>> done = Map.of("main", Set.of(3));
        CategoryDef empty = cat("e", false, null, null, List.of());
        CategoryDef afterOk = cat("m", false, new After("main", 3), null, List.of(q(1)));
        CategoryDef afterNo = cat("m", false, new After("main", 4), null, List.of(q(1)));
        CategoryDef needsUnlock = cat("n", false, null, "nether", List.of(q(1)));
        assertEquals(CategoryState.EMPTY, QuestRules.categoryState(empty, k -> done.getOrDefault(k, Set.of()), n -> true));
        assertEquals(CategoryState.AVAILABLE, QuestRules.categoryState(afterOk, k -> done.getOrDefault(k, Set.of()), n -> false));
        assertEquals(CategoryState.LOCKED, QuestRules.categoryState(afterNo, k -> done.getOrDefault(k, Set.of()), n -> true));
        assertEquals(CategoryState.LOCKED, QuestRules.categoryState(needsUnlock, k -> Set.of(), n -> false));
        assertEquals(CategoryState.AVAILABLE, QuestRules.categoryState(needsUnlock, k -> Set.of(), "nether"::equals));
    }

    @Test
    void defaultFileByLanguage() {
        assertEquals("defaults/quests-pl.yml", QuestRules.defaultContentResource("pl"));
        assertEquals("defaults/quests-pl.yml", QuestRules.defaultContentResource(" PL "));
        assertEquals("defaults/quests-en.yml", QuestRules.defaultContentResource("en"));
        assertEquals("defaults/quests-en.yml", QuestRules.defaultContentResource("de"));
        assertEquals("defaults/quests-en.yml", QuestRules.defaultContentResource(null));
    }

    @Test
    void questSlotsFollowListOrderAndPages() {
        List<SlotEntry> layout = List.of(
                new SlotEntry(12, SlotRole.QUEST_SLOT, null),
                new SlotEntry(10, SlotRole.QUEST_SLOT, null),
                new SlotEntry(49, SlotRole.NAV_BACK, null),
                new SlotEntry(11, SlotRole.QUEST_SLOT, null));
        assertEquals(3, QuestRules.questSlotsPerPage(layout));
        assertEquals(2, QuestRules.pageCount(layout, 5));
        assertEquals(1, QuestRules.pageCount(layout, 0));
        assertEquals(Map.of(12, 0, 10, 1, 11, 2), QuestRules.questSlots(layout, 0, 5));
        assertEquals(Map.of(12, 3, 10, 4), QuestRules.questSlots(layout, 1, 5));
        assertEquals(1, QuestRules.pageCount(List.of(), 7));
        assertTrue(QuestRules.questSlots(List.of(), 0, 7).isEmpty());
    }

    @Test
    void categorySlotsFollowOrder() {
        List<SlotEntry> layout = List.of(
                new SlotEntry(13, SlotRole.CATEGORY_SLOT, null),
                new SlotEntry(0, SlotRole.FILLER, null),
                new SlotEntry(29, SlotRole.CATEGORY_SLOT, null),
                new SlotEntry(30, SlotRole.CATEGORY_SLOT, null));
        assertEquals(Map.of(13, "main", 29, "mining"), QuestRules.categorySlots(layout, List.of("main", "mining")));
    }
}
