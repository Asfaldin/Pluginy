package elo.mainplugins.mobs;

import elo.mainplugins.core.api.CustomMobService;
import elo.mainplugins.mobs.model.MobDef;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Miniatura moba z Kreatora: same części modelu w pozie spoczynkowej, pomniejszone do "size" bloków,
 * bez ciała, AI i hitboxów - np. kręcąca się w klatce spawnera (Spawnery wołają spin co kilka ticków).
 * Części nie są zapisywane w świecie (setPersistent(false)) - po restarcie albo wyładowaniu chunka
 * po prostu znikają, a wołający tworzy miniaturę od nowa (valid() == false).
 */
final class MobPreview implements CustomMobService.Preview {

    private final MobDef def;
    private final Map<String, ItemDisplay> parts = new LinkedHashMap<>();
    private final Map<String, Matrix4f> rest;
    private final float scale;

    MobPreview(MobDef def, Location feet, float size) {
        this.def = def;
        this.rest = MobPose.boneMatrices(def, new HashMap<>());
        double biggest = Math.max(0.3, Math.max(def.hitboxHeight(), def.hitboxWidth()));
        this.scale = (float) Math.min(1.0, size / biggest);
        Location at = feet.clone();
        at.setYaw(0);
        at.setPitch(0);
        // Światło jak w miejscu miniatury (w klatce spawnera bywa ciemno - wtedy bierzemy sąsiada nad nią).
        Block b = at.getBlock().getRelative(0, 1, 0);
        Display.Brightness light = new Display.Brightness(Math.max(4, b.getLightFromBlocks()), Math.max(4, b.getLightFromSky()));
        for (MobDef.Bone bone : def.bones()) {
            if (!bone.visible()) continue;
            ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
                e.setItemStack(LiveMob.partStack(bone.item(), false));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setPersistent(false);
                e.setInterpolationDuration(0);
                e.setBrightness(bone.glow() ? new Display.Brightness(15, 15) : light);
            });
            parts.put(bone.id(), d);
        }
        spin(0, 0);
    }

    @Override
    public void spin(float yawDegrees, int ticks) {
        for (MobDef.Bone bone : def.bones()) {
            ItemDisplay d = parts.get(bone.id());
            Matrix4f boneMatrix = rest.get(bone.id());
            if (d == null || boneMatrix == null || !d.isValid()) continue;
            Matrix4f m = new Matrix4f().scale(scale).mul(MobPose.displayMatrix(yawDegrees, boneMatrix, bone.modelScale()));
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(Math.max(0, ticks));
            d.setTransformation(new Transformation(m.getTranslation(new Vector3f()), MobPose.rotationOf(m), m.getScale(new Vector3f()), new Quaternionf()));
        }
    }

    @Override
    public boolean valid() {
        if (parts.isEmpty()) return false;
        for (ItemDisplay d : parts.values()) if (!d.isValid()) return false;
        return true;
    }

    @Override
    public void remove() {
        parts.values().forEach(ItemDisplay::remove);
        parts.clear();
    }
}
