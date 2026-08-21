package elo.mainplugins.quests.model;

/**
 * Wymóg odblokowania kategorii - "ukończ quest {@code questId} w kategorii {@code categoryId}".
 * W odróżnieniu od dawnego WYMOG_ODBLOKOWANIA_KATEGORII (na sztywno tylko Główna Ścieżka) może
 * wskazywać na DOWOLNĄ kategorię, w tym inną boczną - żeby dało się w edytorze zbudować odblokowanie
 * jednej ścieżki przez ukończenie innej, nie tylko Głównej.
 */
public record UnlockCondition(String categoryId, int questId) {
}
