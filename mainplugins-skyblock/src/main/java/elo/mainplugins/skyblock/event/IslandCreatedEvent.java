package elo.mainplugins.skyblock.event;

import elo.mainplugins.skyblock.IslandData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Odpalane raz, zaraz po tym jak gracz z sukcesem stworzył swoją pierwszą wyspę
 * (patrz IslandManager.stworzWyspe). Most do mainplugins-quests (kategoria "Główne
 * zadania") - żaden inny moduł nie musi tego słuchać, ale event jest ogólnodostępny
 * jak każdy customowy event Bukkita.
 */
public class IslandCreatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final IslandData island;

    public IslandCreatedEvent(Player player, IslandData island) {
        this.player = player;
        this.island = island;
    }

    public Player getPlayer() { return player; }
    public IslandData getIsland() { return island; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}