package elo.mainplugins.skyblock;

import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Warden, Wither i bałwan (Snow Golem) - na całym serwerze, według zasad serwera
 * (wyspy-config.yml: moby.warden / moby.wither / moby.balwan, domyślnie zablokowane).
 * Bałwan łapany jest tu mimo że powstaje przez zbudowanie (SpawnReason.BUILD_SNOWMAN) - to ten sam event.
 * Nie dotyczy /summon admina (SpawnReason.COMMAND) - świadomy wyjątek do testów.
 */
public class MobRestrictionManager implements Listener {

    private final IslandManager islandManager;

    public MobRestrictionManager(IslandManager islandManager) {
        this.islandManager = islandManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) return;
        var t = islandManager.getTuning();
        EntityType typ = event.getEntityType();
        boolean zablokowany = (typ == EntityType.WARDEN && !t.mobyWarden())
                || (typ == EntityType.WITHER && !t.mobyWither())
                || (typ == EntityType.SNOW_GOLEM && !t.mobyBalwan());
        if (zablokowany) event.setCancelled(true);
    }
}
