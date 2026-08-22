package elo.mainplugins.skyblock;

import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Globalny zakaz spawnu Wardena, Withera i bałwana (Snow Golem) - wszędzie na
 * serwerze, niezależnie od ustawień danej wyspy (patrz IslandProtectionManager,
 * który kontroluje TYLKO moby na wyspach graczy przez allowMobs). Bałwan łapany
 * jest tu samo jak reszta mimo że powstaje przez zbudowanie (SpawnReason.BUILD_SNOWMAN),
 * nie naturalny spawn - to nadal ten sam event. Nie dotyczy /summon admina
 * (SpawnReason.COMMAND) - to świadomy wyjątek do testów.
 */
public class MobRestrictionManager implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        EntityType typ = event.getEntityType();
        if (typ != EntityType.WARDEN && typ != EntityType.WITHER && typ != EntityType.SNOW_GOLEM) return;
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) return;

        event.setCancelled(true);
    }
}
