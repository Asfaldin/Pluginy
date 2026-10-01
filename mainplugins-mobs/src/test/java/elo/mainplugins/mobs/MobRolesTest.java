package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Role części z aplikacji: dowolnie wiele głów i nóg, nazwy bez znaczenia. */
class MobRolesTest {

    private static MobDef.Bone bone(String id, String name, String parent, float[] box) {
        return new MobDef.Bone(id, name, parent, new float[]{0, 0, 0}, new float[]{0, 0, 0}, new float[]{1, 1, 1}, false,
                "mainplugins:x", 1, box == null ? List.of() : List.of(box), Map.of());
    }

    @Test
    void hydraSzesciuNog() {
        List<MobDef.Bone> bones = new ArrayList<>();
        Map<String, String> roles = new LinkedHashMap<>();
        bones.add(bone("body", "tulow", null, new float[]{-8, 0, -8, 16, 8, 16}));
        for (int i = 0; i < 3; i++) {
            bones.add(bone("n" + i, "cos" + i, "body", new float[]{0, -6, 0, 2, 6, 2}));
            bones.add(bone("h" + i, "lepetyna" + i, "n" + i, new float[]{-2, -4, -2, 4, 4, 4}));
            roles.put("n" + i, "neck");
            roles.put("h" + i, "head");
        }
        for (int i = 0; i < 6; i++) {
            bones.add(bone("u" + i, "a" + i, "body", new float[]{0, 0, 0, 2, 5, 2}));
            bones.add(bone("k" + i, "b" + i, "u" + i, new float[]{0, 0, 0, 2, 5, 2}));
            roles.put("u" + i, "leg");
            roles.put("k" + i, "leg");
        }
        bones.add(bone("t", "zzz", "body", new float[]{0, 0, 0, 2, 2, 8}));
        roles.put("t", "tail");
        MobDef def = new MobDef("hydra", "Hydra", bones, Map.of(), List.of(), 1, 1, Map.of(), roles, Map.of("walk", "chodzenie"));
        MobRig rig = new MobRig(def);
        assertEquals(3, rig.heads.size());
        assertEquals(List.of("n0", "h0"), rig.heads.get(0));
        assertEquals(6, rig.legs.size());
        assertTrue(rig.legs.stream().allMatch(l -> l.chain.size() == 2));
        assertEquals(List.of("t"), rig.springs);
    }

    @Test
    void staryPlikPoNazwach() {
        List<MobDef.Bone> bones = List.of(
                bone("body", "body", null, new float[]{-4, 0, -2, 8, 12, 4}),
                bone("head", "head", "body", new float[]{-4, -8, -4, 8, 8, 8}),
                bone("l", "left_leg", "body", new float[]{-2, 0, -2, 4, 6, 4}),
                bone("lk", "left_leg_lower", "l", new float[]{-2, 0, -2, 4, 6, 4}));
        MobRig rig = new MobRig(new MobDef("z", "Z", bones, Map.of(), List.of(), 1, 1));
        assertEquals(1, rig.heads.size());
        assertEquals(1, rig.legs.size());
    }
}
