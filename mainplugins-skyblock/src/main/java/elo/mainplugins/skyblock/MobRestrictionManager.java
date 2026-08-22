package elo.mainplugins.skyblock;

import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Globalny zakaz spawnu Wardena i Withera - wszędzie na serwerze, niezależnie
 * od ustawień danej wyspy (patrz IslandProtectionManager, który kontroluje
 * TYLKO moby na wyspach graczy przez allowMobs). Nie dotyczy /summon admina
 * (SpawnReason.COMMAND) - to świadomy wyjątek do testów.
 */
public class MobRestrictionManager implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        EntityType typ = event.getEntityType();
        if (typ != EntityType.WARDEN && typ != EntityType.WITHER) return;
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) return;

        event.setCancelled(true);
    }
}
