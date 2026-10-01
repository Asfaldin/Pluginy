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
            List<float[]> boxes = new ArrayList<>();
            if (b.has("cubes")) {
                for (JsonElement ce : b.getAsJsonArray("cubes")) {
                    JsonObject c = ce.getAsJsonObject();
                    float[] f = vec(c.get("from"), 0), sz = vec(c.get("size"), 0), g = vec(c.get("grow"), 0);
                    boxes.add(new float[]{f[0] - g[0], f[1] - g[1], f[2] - g[2], sz[0] + 2 * g[0], sz[1] + 2 * g[1], sz[2] + 2 * g[2]});
                }
            }
            Map<String, String> variantItems = new LinkedHashMap<>();
            if (d != null && d.has("variants")) {
                for (Map.Entry<String, JsonElement> v : d.getAsJsonObject("variants").entrySet()) variantItems.put(v.getKey(), v.getValue().getAsString());
            }
            bones.add(new MobDef.Bone(id, b.has("name") ? b.get("name").getAsString() : id,
                    b.has("parent") && !b.get("parent").isJsonNull() ? b.get("parent").getAsString() : null,
                    vec(b.get("pivot"), 0), vec(b.get("rotation"), 0), vec(b.get("scale"), 1),
                    b.has("glow") && b.get("glow").getAsBoolean(),
                    d != null ? d.get("item").getAsString() : null,
                    d != null ? d.get("scale").getAsFloat() : 1f, List.copyOf(boxes), Map.copyOf(variantItems)));
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
                List<MobDef.VariantKey> vk = new ArrayList<>();
                if (a.has("variants")) {
                    for (JsonElement ve : a.getAsJsonArray("variants")) {
                        JsonObject v = ve.getAsJsonObject();
                        vk.add(new MobDef.VariantKey(v.get("t").getAsFloat(), v.get("v").getAsString()));
                    }
                    vk.sort((x, y) -> Float.compare(x.t(), y.t()));
                }
                String aid = a.has("id") ? a.get("id").getAsString() : name;
                List<MobDef.SoundKey> sk = new ArrayList<>();
                if (a.has("sounds") && a.get("sounds").isJsonArray()) {
                    for (JsonElement se : a.getAsJsonArray("sounds")) {
                        JsonObject so = se.getAsJsonObject();
                        if (!so.has("sound")) continue;
                        sk.add(new MobDef.SoundKey(so.has("t") ? so.get("t").getAsFloat() : 0, so.get("sound").getAsString(),
                                so.has("volume") ? so.get("volume").getAsFloat() : 1, so.has("pitch") ? so.get("pitch").getAsFloat() : 1));
                    }
                }
                anims.put(name, new MobDef.Anim(aid, name, a.get("length").getAsFloat(), a.get("loop").getAsBoolean(), tracks, List.copyOf(vk), List.copyOf(sk)));
            }
        }
        List<MobDef.Effect> effects = new ArrayList<>();
        if (m.has("effects")) {
            for (JsonElement e : m.getAsJsonArray("effects")) {
                JsonObject f = e.getAsJsonObject();
                effects.add(new MobDef.Effect(
                        f.has("bone") && !f.get("bone").isJsonNull() ? f.get("bone").getAsString() : null,
                        f.get("particle").getAsString(), vec(f.get("offset"), 0),
                        f.has("rate") ? f.get("rate").getAsFloat() : 1f, f.has("spread") ? f.get("spread").getAsFloat() : 0f,
                        str(f, "anim"), f.has("from") ? f.get("from").getAsFloat() : -1, f.has("to") ? f.get("to").getAsFloat() : -1,
                        str(f, "variant"), f.has("velocity") ? vec(f.get("velocity"), 0) : null, f.has("rise") ? f.get("rise").getAsFloat() : 0));
            }
        }
        double w = 0.6, h = 1.95;
        if (m.has("settings") && m.getAsJsonObject("settings").has("hitbox")) {
            JsonObject hb = m.getAsJsonObject("settings").getAsJsonObject("hitbox");
            w = hb.get("width").getAsDouble();
            h = hb.get("height").getAsDouble();
        }
        Map<String, MobDef.Variant> variants = new LinkedHashMap<>();
        JsonObject disp = JsonParser.parseString(displayJson).getAsJsonObject();
        if (disp.has("variants")) {
            for (JsonElement ve : disp.getAsJsonArray("variants")) {
                JsonObject v = ve.getAsJsonObject();
                String vid = v.get("id").getAsString();
                variants.put(vid, new MobDef.Variant(vid, v.has("noGlow") && v.get("noGlow").getAsBoolean()));
            }
        }
        // Role części i animacje sytuacji - ustawione albo wykryte w aplikacji (lib/mobRoles.ts).
        Map<String, String> roles = new LinkedHashMap<>(), slots = new LinkedHashMap<>();
        if (disp.has("roles") && disp.get("roles").isJsonObject())
            for (Map.Entry<String, JsonElement> e : disp.getAsJsonObject("roles").entrySet()) roles.put(e.getKey(), e.getValue().getAsString());
        if (disp.has("slots") && disp.get("slots").isJsonObject())
            for (Map.Entry<String, JsonElement> e : disp.getAsJsonObject("slots").entrySet()) slots.put(e.getKey(), e.getValue().getAsString());
        return new MobDef(m.get("id").getAsString(), m.get("name").getAsString(), List.copyOf(bones), anims, List.copyOf(effects), w, h, Map.copyOf(variants),
                Map.copyOf(roles), Map.copyOf(slots));
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
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
