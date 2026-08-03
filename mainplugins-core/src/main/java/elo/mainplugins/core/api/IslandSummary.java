package elo.mainplugins.core.api;

import java.util.UUID;

/** Skrócone dane wyspy do wyświetlania w rankingach/HUD-zie - patrz {@link IslandService}. */
public record IslandSummary(UUID ownerUUID, String ownerName, int borderSize, int memberCount) {}