package elo.mainplugins.quests.model;

import java.util.List;

/**
 * Pojedynczy quest - id musi być unikalne W RAMACH kategorii i STABILNE (nie renumerować przy
 * edycji istniejących questów) - QuestService.ukonczylGlownaSciezke oraz mainplugins-spawn's
 * /warp kowal zależą na sztywno od konkretnych ID w kategorii Głównej Ścieżki.
 *
 * {@code rewardLabel} jeśli null/puste - QuestManager sam sklei etykietę z nie-cichych
 * {@code rewards} (patrz RewardEntry#silent). Migracja zawsze wpisuje oryginalny tekst wprost,
 * żeby treść się nie zmieniła.
 */
public record QuestDefinition(int id, String title, List<String> description, Requirement requirement,
                               List<RewardEntry> rewards, String rewardLabel) {
}
