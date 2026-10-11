package elo.mainplugins.mobs;

import elo.mainplugins.core.api.CustomMobService;
import elo.mainplugins.mobs.model.MobDef;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Locale;
import java.util.Set;

/** Custom moby z Kreatora dla innych pluginów (Spawnery) - rejestrowane w ServicesManager w onEnable. */
final class MobServiceImpl implements CustomMobService {

    private final MainpluginsMobs plugin;

    MobServiceImpl(MainpluginsMobs plugin) {
        this.plugin = plugin;
    }

    @Override
    public Set<String> ids() {
        return plugin.mobIds();
    }

    @Override
    public String displayName(String id) {
        MobDef def = plugin.def(id);
        return def == null ? null : def.name();
    }

    @Override
    public LivingEntity spawn(String id, Location at) {
        LiveMob m = plugin.spawn(id, at);
        return m == null ? null : m.base;
    }

    @Override
    public boolean isCustomMob(Entity entity) {
        return plugin.liveOf(entity) != null;
    }

    @Override
    public void remove(Entity entity) {
        LiveMob m = plugin.liveOf(entity);
        if (m != null) m.remove();
        else if (entity != null) entity.remove();
    }

    @Override
    public Preview preview(String id, Location feet, float size) {
        MobDef def = plugin.def(id == null ? "" : id.toLowerCase(Locale.ROOT));
        return def == null ? null : new MobPreview(def, feet, size);
    }
}
