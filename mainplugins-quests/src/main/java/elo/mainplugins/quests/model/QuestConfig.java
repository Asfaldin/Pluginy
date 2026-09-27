package elo.mainplugins.quests.model;

import java.util.List;
import java.util.Map;

/** Cały quests.yml po wczytaniu. categories w kolejności z pliku, categoryOrder = kolejność w menu, sounds = settings.sounds. */
public record QuestConfig(QuestSettings settings, List<SlotEntry> mainMenu, List<String> categoryOrder,
                          Map<String, String> titles, Map<String, CategoryDef> categories, QuestSounds sounds) {
}
