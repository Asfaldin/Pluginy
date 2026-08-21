package elo.mainplugins.quests.model;

import org.bukkit.Material;

import java.util.List;

/**
 * Jedna kategoria questów - {@code id} to stabilny, WIELKIMI_LITERAMI klucz (osobny od
 * {@code displayName}, który może się zmieniać w edytorze bez gubienia postępu graczy - patrz
 * QuestManager#LEGACY_KATEGORIA_ID). Pusta lista {@code quests} = kategoria "w budowie"
 * (BARRIER), dokładnie jak dawne kategoriaMaTresc().
 *
 * {@code mainPath} zastępuje dawne porównanie do stałej KATEGORIA_GLOWNA_SCIEZKA (m.in. pod
 * powitanie nowego gracza, TytulService) - musi być ustawione na dokładnie JEDNEJ kategorii.
 * {@code sequential} zastępuje dawne "kategoria == Główna Ścieżka" w ustalStan() - quest N+1
 * zablokowany, dopóki N (poprzedni na liście quests) nieukończony.
 */
public record CategoryDefinition(String id, String displayName, Material icon, String description,
                                  boolean mainPath, boolean sequential, UnlockCondition unlock,
                                  List<SlotEntry> pageLayout, List<QuestDefinition> quests) {
}
