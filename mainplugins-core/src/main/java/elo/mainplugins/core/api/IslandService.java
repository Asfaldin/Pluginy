package elo.mainplugins.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Opcjonalny kontrakt wysp - implementuje go i rejestruje w ServicesManager
 * plugin MainpluginsSkyblock, JEŚLI jest zainstalowany. Konsumenci (np. HUD)
 * muszą liczyć się z tym, że ten serwis może w ogóle nie być zarejestrowany
 * (Skyblock niewgrany na serwer) i mieć na to sensowny fallback - w
 * przeciwieństwie do EconomyService, to NIE jest twardy wymóg rdzenia.
 */
public interface IslandService {

    int getIslandCount();

    /** Wyspy posortowane malejąco wg rozmiaru (promienia terenu). */
    List<IslandSummary> getTopIslands(int limit);

    /** Wyspa gracza (jako właściciela lub członka), albo null jeśli żadnej nie ma. */
    IslandSummary getIslandOf(UUID playerUUID);
}