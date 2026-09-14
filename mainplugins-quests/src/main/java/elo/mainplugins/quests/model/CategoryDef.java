package elo.mainplugins.quests.model;

import java.util.List;

/** Kategoria zadań. glow = blask na ikonce w menu. after/requiresUnlock null = brak blokady; requiresUnlock małymi literami. */
public record CategoryDef(String id, String name, ItemRef icon, String description, boolean glow,
                          boolean sequential, After after, String requiresUnlock,
                          List<SlotEntry> pageLayout, List<QuestDef> quests) {

    public CategoryDef withAfter(After a) {
        return new CategoryDef(id, name, icon, description, glow, sequential, a, requiresUnlock, pageLayout, quests);
    }
}
