package elo.mainplugins.skyblock.event;

import elo.mainplugins.skyblock.IslandData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Odpalane po każdej udanej wpłacie do banku wyspy (patrz IslandManager.wplacDoBankuKomenda / "/is deposit"). */
public class IslandBankDepositEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final IslandData island;
    private final double kwota;

    public IslandBankDepositEvent(Player player, IslandData island, double kwota) {
        this.player = player;
        this.island = island;
        this.kwota = kwota;
    }

    public Player getPlayer() { return player; }
    public IslandData getIsland() { return island; }
    public double getKwota() { return kwota; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}