package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobDef;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobRigTest {

    private static MobDef.Bone bone(String id, String name, String parent, float[] pivot, float[] box) {
        return new MobDef.Bone(id, name, parent, pivot, new float[]{0, 0, 0}, new float[]{1, 1, 1}, false, "mainplugins:x", 1f,
                box == null ? List.of() : List.<float[]>of(box), Map.of());
    }

    /** Czworonóg: tułów, głowa z przodu, dwie nogi z kolanem, ogon. */
    private static MobDef quad() {
        return new MobDef("t", "T", List.of(
                bone("body", "body", null, new float[]{0, 12, 0}, new float[]{-4, -4, -8, 8, 8, 16}),
                bone("head", "head", "body", new float[]{0, -4, -8}, new float[]{-3, -6, -6, 6, 6, 6}),
                bone("fl", "front_left_leg", "body", new float[]{3, 4, -6}, new float[]{-1, 0, -1, 2, 5, 2}),
                bone("fl2", "front_left_shin", "fl", new float[]{0, 5, 0}, new float[]{-1, 0, -1, 2, 3, 2}),
                bone("tail", "tail", "body", new float[]{0, -2, 8}, new float[]{-1, -1, 0, 2, 2, 8})
        ), Map.of(), List.of(), 1, 1);
    }

    @Test
    void easingsStartAndEnd() {
        for (String e : List.of("linear", "smooth", "in", "out", "back", "elastic", "bounce")) {
            assertEquals(0, MobPose.ease(e, 0), 1e-4, e);
            assertEquals(1, MobPose.ease(e, 1), 1e-4, e);
        }
        assertTrue(MobPose.ease("back", 0.7f) > 1, "back przestrzeliwuje");
    }

    @Test
    void recognizesChains() {
        MobRig rig = new MobRig(quad());
        assertEquals(List.of("head"), rig.look);
        assertEquals(1, rig.legs.size());
        assertEquals(List.of("fl", "fl2"), rig.legs.get(0).chain);
        assertTrue(rig.springs.contains("tail"));
    }

    @Test
    void headTurnsTowardTarget() {
        MobDef def = quad();
        MobRig rig = new MobRig(def);
        Map<String, MobPose.Offset> off = new HashMap<>();
        Map<String, Matrix4f> bones = MobPose.boneMatrices(def, off);
        // cel z boku moba (mob patrzy na +Z świata przy yaw 0): kilka ticków, bo głowa dochodzi płynnie
        Vector3f target = new Vector3f(3, 1.5f, 1.5f);
        float yaw0 = 0;
        for (int i = 0; i < 30; i++) {
            off.clear();
            rig.applyLook(off, bones, 0, target);
            yaw0 = off.get("head").rotation[1];
        }
        assertTrue(Math.abs(yaw0) > 30, "głowa skręca: " + yaw0);
        // ta sama strona co cel: przód głowy po obrocie bliżej celu
        off.get("head").rotation[1] = yaw0;
        Matrix4f turned = MobPose.boneMatrices(def, off).get("head");
        Vector3f f = MobRig.modelToWorld(0).transformDirection(turned.transformDirection(new Vector3f(0, 0, -1)));
        assertTrue(f.x * target.x > 0, "patrzy w stronę celu");
    }

    @Test
    void footReachesHigherGround() {
        MobDef def = quad();
        MobRig rig = new MobRig(def);
        Map<String, MobPose.Offset> off = new HashMap<>();
        Map<String, Matrix4f> bones = MobPose.boneMatrices(def, off);
        MobRig.Leg leg = rig.legs.get(0);
        float y0 = rig.footModel(leg, bones, off).y;
        for (int i = 0; i < 20; i++) rig.applyFeet(off, bones, 0, 0, 64, 0, true, (x, y, z) -> 64.3);
        float y1 = rig.footModel(leg, bones, off).y;
        // model: oś Y w dół - stopa wyżej = mniejsze y (0,3 bloku)
        assertTrue(y0 - y1 > 0.2f, "stopa uniesiona o " + (y0 - y1));
    }

    @Test
    void tailSwingsOnSidewaysAcceleration() {
        MobDef def = quad();
        MobRig rig = new MobRig(def);
        float max = 0;
        for (int i = 0; i < 20; i++) {
            Map<String, MobPose.Offset> off = new HashMap<>();
            Map<String, Matrix4f> bones = MobPose.boneMatrices(def, off);
            // mob przyspiesza w bok (x = a t^2)
            float x = 0.02f * i * i;
            rig.applySprings(off, bones, new Matrix4f().translate(x, 0, 0).mul(MobRig.modelToWorld(0)));
            if (off.containsKey("tail")) max = Math.max(max, Math.abs(off.get("tail").rotation[1]));
        }
        assertTrue(max > 2, "ogon zostaje w tyle: " + max);
    }
}
