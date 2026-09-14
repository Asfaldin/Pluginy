package elo.mainplugins.quests.model;

/**
 * Wygląd menu: sekcja settings z quests.yml (domyślny + tło menu głównego) albo look kategorii
 * (jej ikonki zadań, ikonki w menu głównym, przyciski i tło strony).
 */
public record QuestSettings(ItemRef filler,
                            ItemRef available, ItemRef completed, ItemRef locked,
                            ItemRef categoryLocked, ItemRef categoryEmpty,
                            ItemRef back, ItemRef prev, ItemRef next) {

    private static ItemRef ref(String material) {
        return new ItemRef(material, null, 1);
    }

    public static final QuestSettings DEFAULTS = new QuestSettings(
            ref("BLACK_STAINED_GLASS_PANE"), ref("RED_DYE"), ref("LIME_DYE"), ref("GRAY_DYE"),
            ref("GRAY_DYE"), ref("BARRIER"), ref("DARK_OAK_DOOR"), ref("ARROW"), ref("ARROW"));
}
