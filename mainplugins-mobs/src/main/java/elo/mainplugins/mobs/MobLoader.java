package elo.mainplugins.mobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import elo.mainplugins.mobs.model.MobDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Czyta moba z JSON-ów Kreatora mobów. Zły plik = wyjątek z opisem (plugin pomija moba z ostrzeżeniem). */
public final class MobLoader {

    private MobLoader() {}

    public static MobDef parse(String mobJson, String displayJson) {
        JsonObject m = JsonParser.parseString(mobJson).getAsJsonObject();
        JsonObject bonesDisplay = JsonParser.parseString(displayJson).getAsJsonObject().getAsJsonObject("bones");
        List<MobDef.Bone> bones = new ArrayList<>();
        for (JsonElement e : m.getAsJsonArray("bones")) {
            JsonObject b = e.getAsJsonObject();
            String id = b.get("id").getAsString();
            JsonObject d = bonesDisplay != null && bonesDisplay.has(id) ? bonesDisplay.getAsJsonObject(id) : null;
            bones.add(new MobDef.Bone(id,
                    b.has("parent") && !b.get("parent").isJsonNull() ? b.get("parent").getAsString() : null,
                    vec(b.get("pivot"), 0), vec(b.get("rotation"), 0), vec(b.get("scale"), 1),
                    b.has("glow") && b.get("glow").getAsBoolean(),
                    d != null ? d.get("item").getAsString() : null,
                    d != null ? d.get("scale").getAsFloat() : 1f));
        }
        Map<String, MobDef.Anim> anims = new LinkedHashMap<>();
        if (m.has("animations")) {
            for (JsonElement e : m.getAsJsonArray("animations")) {
                JsonObject a = e.getAsJsonObject();
                Map<String, MobDef.Track> tracks = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> t : a.getAsJsonObject("tracks").entrySet()) {
                    JsonObject tr = t.getValue().getAsJsonObject();
                    tracks.put(t.getKey(), new MobDef.Track(keys(tr.get("rotation")), keys(tr.get("position")), keys(tr.get("scale"))));
                }
                String name = a.get("name").getAsString();
                anims.put(name, new MobDef.Anim(name, a.get("length").getAsFloat(), a.get("loop").getAsBoolean(), tracks));
            }
        }
        List<MobDef.Effect> effects = new ArrayList<>();
        if (m.has("effects")) {
            for (JsonElement e : m.getAsJsonArray("effects")) {
                JsonObject f = e.getAsJsonObject();
                effects.add(new MobDef.Effect(
                        f.has("bone") && !f.get("bone").isJsonNull() ? f.get("bone").getAsString() : null,
                        f.get("particle").getAsString(), vec(f.get("offset"), 0),
                        f.has("rate") ? f.get("rate").getAsFloat() : 1f, f.has("spread") ? f.get("spread").getAsFloat() : 0f));
            }
        }
        double w = 0.6, h = 1.95;
        if (m.has("settings") && m.getAsJsonObject("settings").has("hitbox")) {
            JsonObject hb = m.getAsJsonObject("settings").getAsJsonObject("hitbox");
            w = hb.get("width").getAsDouble();
            h = hb.get("height").getAsDouble();
        }
        return new MobDef(m.get("id").getAsString(), m.get("name").getAsString(), List.copyOf(bones), anims, List.copyOf(effects), w, h);
    }

    private static float[] vec(JsonElement e, float fallback) {
        if (e == null || e.isJsonNull()) return new float[]{fallback, fallback, fallback};
        JsonArray a = e.getAsJsonArray();
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    private static List<MobDef.Key> keys(JsonElement e) {
        List<MobDef.Key> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) return out;
        for (JsonElement k : e.getAsJsonArray()) {
            JsonObject o = k.getAsJsonObject();
            out.add(new MobDef.Key(o.get("t").getAsFloat(), vec(o.get("v"), 0), o.has("e") ? o.get("e").getAsString() : "linear"));
        }
        out.sort((a, b) -> Float.compare(a.t(), b.t()));
        return List.copyOf(out);
    }
}
