package elo.mainplugins.skyblock.event;

import elo.mainplugins.skyblock.IslandManager;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Odpalane, gdy nowy członek zaakceptuje zaproszenie i dołączy do wyspy (patrz IslandManager.zaakceptujZaproszenie). */
public class IslandMemberJoinedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player newMember;
    private final IslandManager.IslandData island;

    public IslandMemberJoinedEvent(Player newMember, IslandManager.IslandData island) {
        this.newMember = newMember;
        this.island = island;
    }

    public Player getNewMember() { return newMember; }
    public IslandManager.IslandData getIsland() { return island; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}