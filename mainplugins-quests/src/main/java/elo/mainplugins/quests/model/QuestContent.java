package elo.mainplugins.quests.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cała treść systemu questów wczytana z quests-content.yml (patrz QuestContentLoader) - jeden
 * niemutowalny snapshot, podmieniany w całości przy /@reloadquesty (postęp graczy w
 * QuestManager#postepyGraczy jest osobny i nietknięty przy reloadzie).
 *
 * {@code categoryOrder} determinuje, który CATEGORY_SLOT w {@code mainMenuLayout} pokazuje
 * którą kategorię (i-ty CATEGORY_SLOT w kolejności listy -> categoryOrder.get(i)) - dokładnie
 * jak dawne KATEGORIE_GWIAZDY+SLOTY_KATEGORII_BOCZNYCH razem. {@code titles} to id->tekst
 * (z kodami koloru "&"), patrz RewardEntry.TitleReward.
 */
public record QuestContent(List<SlotEntry> mainMenuLayout, List<String> categoryOrder,
                            Map<String, String> titles, Map<String, CategoryDefinition> categories) {

    public static QuestContent empty() {
        return new QuestContent(List.of(), List.of(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }
}
