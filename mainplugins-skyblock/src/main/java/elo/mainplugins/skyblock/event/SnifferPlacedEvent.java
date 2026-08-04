package elo.mainplugins.skyblock.event;

import elo.mainplugins.skyblock.IslandManager;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Odpalane po udanym postawieniu Snifferaa Farmera na wyspie (patrz SnifferManager.onUzyjJajka). */
public class SnifferPlacedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final IslandManager.IslandData island;

    public SnifferPlacedEvent(Player player, IslandManager.IslandData island) {
        this.player = player;
        this.island = island;
    }

    public Player getPlayer() { return player; }
    public IslandManager.IslandData getIsland() { return island; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}