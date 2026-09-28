package elo.mainplugins.skyblock.event;

import elo.mainplugins.skyblock.IslandData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Odpalane, gdy nowy członek zaakceptuje zaproszenie i dołączy do wyspy (patrz IslandManager.zaakceptujZaproszenie). */
public class IslandMemberJoinedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player newMember;
    private final IslandData island;

    public IslandMemberJoinedEvent(Player newMember, IslandData island) {
        this.newMember = newMember;
        this.island = island;
    }

    public Player getNewMember() { return newMember; }
    public IslandData getIsland() { return island; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}