package elo.mainplugins.quests.model;

/** Sekcja settings z quests.yml - wygląd menu i zachowanie. */
public record QuestSettings(boolean joinReminder, String welcomeSound, ItemRef filler,
                            ItemRef available, ItemRef completed, ItemRef locked,
                            ItemRef categoryLocked, ItemRef categoryEmpty,
                            ItemRef back, ItemRef prev, ItemRef next) {

    private static ItemRef ref(String material) {
        return new ItemRef(material, null, 1);
    }

    public static final QuestSettings DEFAULTS = new QuestSettings(true, "mainplugins:quest_welcome",
            ref("BLACK_STAINED_GLASS_PANE"), ref("RED_DYE"), ref("LIME_DYE"), ref("GRAY_DYE"),
            ref("GRAY_DYE"), ref("BARRIER"), ref("DARK_OAK_DOOR"), ref("ARROW"), ref("ARROW"));
}
