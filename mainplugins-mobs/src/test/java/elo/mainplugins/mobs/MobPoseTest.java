package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobDef;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MobPoseTest {

    private static MobDef.Bone bone(String id, String parent, float[] pivot, float[] rot) {
        return new MobDef.Bone(id, parent, pivot, rot, new float[]{1, 1, 1}, false, "mainplugins:x", 1f);
    }

    @Test
    void stopyModeluStojaNaZiemi() {
        // Punkt na wysokości 24 (stopy w układzie gry) ląduje na ziemi, 16 jednostek wyżej = 1 blok w górę.
        MobDef mob = new MobDef("t", "T", List.of(bone("b", null, new float[]{0, 24, 0}, new float[]{0, 0, 0})), Map.of(), List.of(), 1, 1);
        Matrix4f m = MobPose.displayMatrix(180, MobPose.boneMatrices(mob, Map.of()).get("b"), 1);
        Vector3f feet = m.transformPosition(new Vector3f(0, 0, 0));
        assertEquals(0, feet.y, 0.01);
        Vector3f up = m.transformPosition(new Vector3f(0, -1, 0));
        assertEquals(1, up.y, 0.01);
    }

    @Test
    void przodModeluPatrzyTamGdzieMob() {
        // Przód modelu to -Z (jak w grze). Mob patrzący na południe (kąt 0) ma przód na +Z świata -
        // licząc z obrotem 180 stopni, który gra dokłada sama przy wyświetlaniu przedmiotu.
        MobDef mob = new MobDef("t", "T", List.of(bone("b", null, new float[]{0, 0, 0}, new float[]{0, 0, 0})), Map.of(), List.of(), 1, 1);
        Matrix4f m = MobPose.displayMatrix(0, MobPose.boneMatrices(mob, Map.of()).get("b"), 1).rotateY((float) Math.PI);
        Vector3f nose = m.transformPosition(new Vector3f(0, 0, -1));
        Vector3f origin = m.transformPosition(new Vector3f(0, 0, 0));
        assertTrue(nose.z > origin.z, "przód powinien być na +Z, jest " + nose);
    }

    @Test
    void dzieckoDziedziczyObrotRodzica() {
        MobDef mob = new MobDef("t", "T", List.of(
                bone("a", null, new float[]{0, 0, 0}, new float[]{0, 90, 0}),
                bone("b", "a", new float[]{16, 0, 0}, new float[]{0, 0, 0})), Map.of(), List.of(), 1, 1);
        Vector3f p = MobPose.boneMatrices(mob, Map.of()).get("b").transformPosition(new Vector3f());
        // Obrót o 90 stopni wokół Y przenosi (1, 0, 0) na (0, 0, -1).
        assertEquals(0, p.x, 0.001);
        assertEquals(-1, p.z, 0.001);
    }

    @Test
    void klatkiKluczoweJakWKreatorze() {
        List<MobDef.Key> keys = List.of(new MobDef.Key(0, new float[]{0, 0, 0}, "linear"), new MobDef.Key(1, new float[]{10, 0, 0}, "smooth"),
                new MobDef.Key(2, new float[]{20, 0, 0}, "linear"));
        assertEquals(5, MobPose.sample(keys, 0.5f)[0], 0.001);
        assertEquals(15, MobPose.sample(keys, 1.5f)[0], 0.001); // smooth w połowie = połowa
        assertEquals(20, MobPose.sample(keys, 9f)[0], 0.001);
        assertNull(MobPose.sample(List.of(), 1));
    }
}
