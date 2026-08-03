package elo.mainplugins.core.api;

import java.util.Map;
import java.util.UUID;

/**
 * Skrócone dane wyspy do wyświetlania w rankingach/HUD-zie - patrz {@link IslandService}.
 * spawnerLevels: poziom (domyślnie 1, max ustala moduł spawnerów) każdego typu customowego
 * spawnera wykupionego na tej wyspie, kluczowany po {@code SpawnerType.name()} - czytane
 * przez mainplugins-spawners bez twardej zależności od mainplugins-skyblock.
 */
public record IslandSummary(UUID ownerUUID, String ownerName, int borderSize, int memberCount, Map<String, Integer> spawnerLevels) {}