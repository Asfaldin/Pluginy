package elo.mainplugins.quests;

import elo.mainplugins.core.api.Reward;
import elo.mainplugins.quests.model.CategoryDef;
import elo.mainplugins.quests.model.QuestDef;
import elo.mainplugins.quests.model.SlotEntry;
import elo.mainplugins.quests.model.SlotRole;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Czyste zasady questów (bez serwera): stany zadań i kategorii, rozkład pól w menu, plik startowy. */
public final class QuestRules {

    public enum QuestState { LOCKED, AVAILABLE, DONE }

    public enum CategoryState { AVAILABLE, LOCKED, EMPTY }

    private QuestRules() {}

    /** Przy sequential zadanie czeka na poprzednie z listy; bez sequential zawsze dostępne. */
    public static QuestState state(CategoryDef c, int index, Set<Integer> done) {
        QuestDef q = c.quests().get(index);
        if (done.contains(q.id())) return QuestState.DONE;
        if (!c.sequential() || index == 0) return QuestState.AVAILABLE;
        return done.contains(c.quests().get(index - 1).id()) ? QuestState.AVAILABLE : QuestState.LOCKED;
    }

    public static boolean afterDone(CategoryDef c, Function<String, Set<Integer>> doneIn) {
        return c.after() == null || doneIn.apply(c.after().category()).contains(c.after().quest());
    }

    public static CategoryState categoryState(CategoryDef c, Function<String, Set<Integer>> doneIn, Predicate<String> hasUnlock) {
        if (c.quests().isEmpty()) return CategoryState.EMPTY;
        if (!afterDone(c, doneIn)) return CategoryState.LOCKED;
        if (c.requiresUnlock() != null && !hasUnlock.test(c.requiresUnlock())) return CategoryState.LOCKED;
        return CategoryState.AVAILABLE;
    }

    /** Typy nagród, które lądują w ekwipunku gracza. */
    private static final Set<String> ITEM_REWARDS = Set.of("item", "custom", "crate", "key");

    /** Czy któraś nagroda to przedmiot (item, custom item, skrzynka, klucz). */
    public static boolean givesItems(List<Reward> rewards) {
        return rewards.stream().anyMatch(r -> ITEM_REWARDS.contains(r.type()));
    }

    /** Plik startowy w jarze według języka serwera z core. */
    public static String defaultContentResource(String language) {
        return language != null && language.trim().equalsIgnoreCase("pl") ? "defaults/quests-pl.yml" : "defaults/quests-en.yml";
    }

    public static int questSlotsPerPage(List<SlotEntry> layout) {
        return (int) layout.stream().filter(e -> e.role() == SlotRole.QUEST_SLOT).count();
    }

    public static int pageCount(List<SlotEntry> layout, int questCount) {
        int per = questSlotsPerPage(layout);
        return per == 0 ? 1 : Math.max(1, (questCount + per - 1) / per);
    }

    /** slot -> indeks zadania: i-te pole QUEST_SLOT w kolejności listy = i-te zadanie strony. */
    public static Map<Integer, Integer> questSlots(List<SlotEntry> layout, int page, int questCount) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        int per = questSlotsPerPage(layout);
        if (per == 0) return out;
        int index = page * per;
        for (SlotEntry e : layout) {
            if (e.role() != SlotRole.QUEST_SLOT) continue;
            if (index < questCount) out.put(e.slot(), index);
            index++;
        }
        return out;
    }

    /** slot -> id kategorii: i-te pole CATEGORY_SLOT = i-ta kategoria z category-order. */
    public static Map<Integer, String> categorySlots(List<SlotEntry> layout, List<String> order) {
        Map<Integer, String> out = new LinkedHashMap<>();
        int i = 0;
        for (SlotEntry e : layout) {
            if (e.role() != SlotRole.CATEGORY_SLOT) continue;
            if (i < order.size()) out.put(e.slot(), order.get(i));
            i++;
        }
        return out;
    }
}
