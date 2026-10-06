package elo.mainplugins.mobs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import elo.mainplugins.mobs.model.MobBehavior;
import elo.mainplugins.mobs.model.MobBehavior.Action;
import elo.mainplugins.mobs.model.MobBehavior.Params;
import elo.mainplugins.mobs.model.MobBehavior.Skill;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Zachowanie moba: z pola "behavior" pliku moba (zakładka Behavior w aplikacji) albo - dla mobów
 * zrobionych przed nią - z config.yml (moby.&lt;id&gt;, stare polskie klucze). Bez jednego i drugiego
 * mob ma statystyki zwykłego zombie i żadnych umiejętności.
 */
public final class BehaviorLoader {

    static final Set<String> MOVEMENTS = Set.of("walk", "fly", "swim", "stationary");
    static final Set<String> ATTITUDES = Set.of("hostile", "neutral", "passive");
    static final Set<String> ATTACKS = Set.of("melee", "ranged", "none");
    static final Set<String> TRIGGERS = Set.of("combat", "timer", "hit", "hurt", "spawn", "death", "phase", "item", "trade", "signal");

    private BehaviorLoader() {}

    /** Domyślne zachowanie (zwykły zombie, bez umiejętności). */
    public static MobBehavior defaults(String name) {
        return fromJson(new JsonObject(), name);
    }

    /** Pole "behavior" z pliku moba; null = brak (stary mob). */
    public static JsonObject behaviorOf(String mobJson) {
        JsonObject m = JsonParser.parseString(mobJson).getAsJsonObject();
        return m.has("behavior") && m.get("behavior").isJsonObject() ? m.getAsJsonObject("behavior") : null;
    }

    public static MobBehavior fromJson(JsonObject b, String fallbackName) {
        String movement = pick(str(b, "movement", "walk"), MOVEMENTS, "walk");
        String attitude = pick(str(b, "attitude", "hostile"), ATTITUDES, "hostile");
        String attack = pick(str(b, "attack", "melee"), ATTACKS, "melee");
        JsonObject bar = b.has("bossBar") && b.get("bossBar").isJsonObject() ? b.getAsJsonObject("bossBar") : new JsonObject();

        List<MobBehavior.Drop> drops = new ArrayList<>();
        for (JsonElement e : arr(b, "drops")) {
            if (!e.isJsonObject()) continue;
            JsonObject d = e.getAsJsonObject();
            String item = str(d, "item", "");
            if (item.isBlank()) continue;
            int min = (int) Math.max(0, num(d, "min", 1)), max = (int) Math.max(min, num(d, "max", min));
            drops.add(new MobBehavior.Drop(item, min, Math.min(64 * 9, max), clamp(num(d, "chance", 1), 0, 1)));
        }
        Map<String, Double> weak = new LinkedHashMap<>();
        if (b.has("weakPoints") && b.get("weakPoints").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("weakPoints").entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) weak.put(e.getKey(), clamp(e.getValue().getAsDouble(), 0, 20));
            }
        }
        List<MobBehavior.Phase> phases = new ArrayList<>();
        for (JsonElement e : arr(b, "phases")) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            phases.add(new MobBehavior.Phase(clamp(num(p, "below", 0.5), 0, 1), strOrNull(p, "animation"), strOrNull(p, "variant"),
                    clamp(num(p, "damageMultiplier", 1), 0, 100), strOrNull(p, "sound")));
        }
        // Od najwyższego progu - faza 1 zaczyna się pierwsza.
        phases.sort((x, y) -> Double.compare(y.below(), x.below()));
        List<Skill> skills = new ArrayList<>();
        for (JsonElement e : arr(b, "skills")) {
            if (e.isJsonObject()) skills.add(skill(e.getAsJsonObject()));
        }
        return new MobBehavior(
                clamp(num(b, "health", 20), 1, 100000), clamp(num(b, "damage", 3), 0, 10000), clamp(num(b, "speed", 0.23), 0, 2),
                clamp(num(b, "armor", 0), 0, 30), clamp(num(b, "knockbackResistance", 0), 0, 1), clamp(num(b, "followRange", 24), 1, 128),
                clamp(num(b, "attackCooldown", 1), 0.1, 60),
                movement, attitude, attack, params(b.has("ranged") && b.get("ranged").isJsonObject() ? b.getAsJsonObject("ranged") : new JsonObject()),
                str(b, "name", fallbackName), pick(str(b, "nameplate", "near"), Set.of("always", "near", "never"), "near"), bool(b, "healthBar", true),
                new MobBehavior.BossBar(bool(bar, "enabled", false), str(bar, "color", "red"), str(bar, "style", "progress"), clamp(num(bar, "range", 32), 4, 256)),
                (int) clamp(num(b, "xp", 5), 0, 100000), drops,
                strOrNull(b, "ambientSound"), strOrNull(b, "hurtSound"), strOrNull(b, "deathSound"),
                strOrNull(b, "attackSound"), strOrNull(b, "shootSound"), strOrNull(b, "spawnSound"), strOrNull(b, "stepSound"), strOrNull(b, "talkSound"),
                weak, bool(b, "look", true), bool(b, "feet", true), bool(b, "springs", true), bool(b, "partHitboxes", true), bool(b, "shatter", true),
                strings(b, "randomAnimations"), clamp(num(b, "randomInterval", 14), 1, 3600), strings(b, "backgroundAnimations"),
                phases, skills, bool(b, "persistent", true), npc(b), bool(b, "fixedFacing", false));
    }

    private static MobBehavior.Npc npc(JsonObject b) {
        if (!b.has("npc") || !b.get("npc").isJsonObject()) return MobBehavior.Npc.OFF;
        JsonObject n = b.getAsJsonObject("npc");
        if (!bool(n, "enabled", false)) return MobBehavior.Npc.OFF;
        List<Action> onClick = new ArrayList<>();
        for (JsonElement e : arr(n, "onClick")) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String type = str(a, "type", "");
            if (!type.isBlank()) onClick.add(new Action(type, clamp(num(a, "at", 0), 0, 600), params(a)));
        }
        onClick.sort((x, y) -> Double.compare(x.at(), y.at()));
        List<MobBehavior.Trade> trades = new ArrayList<>();
        for (JsonElement e : arr(n, "trades")) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            String buy = str(t, "buy", ""), sell = str(t, "sell", "");
            if (buy.isBlank() || sell.isBlank()) continue;
            trades.add(new MobBehavior.Trade(buy, (int) clamp(num(t, "buyAmount", 1), 1, 64), sell, (int) clamp(num(t, "sellAmount", 1), 1, 64)));
        }
        return new MobBehavior.Npc(true, bool(n, "invulnerable", true), clamp(num(n, "homeRadius", 0), 0, 64), strings(n, "dialogue"),
                pick(str(n, "dialogueMode", "chat"), Set.of("chat", "title"), "chat"), List.copyOf(onClick),
                clamp(num(n, "clickCooldown", 1), 0, 86400), bool(n, "oncePerPlayer", false), List.copyOf(trades));
    }

    private static Skill skill(JsonObject s) {
        List<Action> actions = new ArrayList<>();
        for (JsonElement e : arr(s, "actions")) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String type = str(a, "type", "");
            if (type.isBlank()) continue;
            actions.add(new Action(type, clamp(num(a, "at", 0), 0, 600), params(a)));
        }
        actions.sort((x, y) -> Double.compare(x.at(), y.at()));
        return new Skill(str(s, "name", "skill"), pick(str(s, "trigger", "combat"), TRIGGERS, "combat"), clamp(num(s, "cooldown", 10), 0, 3600),
                clamp(num(s, "rangeMin", 0), 0, 256), clamp(num(s, "rangeMax", 16), 0, 256), clamp(num(s, "chance", 1), 0, 1),
                clamp(num(s, "healthBelow", 1), 0, 1), (int) clamp(num(s, "phase", 0), 0, 100), strOrNull(s, "animation"), List.copyOf(actions),
                str(s, "item", ""));
    }

    // ---- stary config.yml ----

    /**
     * Mob sprzed zakładki Behavior: statystyki i 4 stare umiejętności z config.yml przepisane na nowy
     * system (te same ruchy i te same chwile, więc przykładowe moby zachowują się jak wcześniej).
     */
    public static MobBehavior fromLegacy(ConfigurationSection c, String name) {
        JsonObject b = new JsonObject();
        if (c.contains("zycie")) b.addProperty("health", c.getDouble("zycie"));
        if (c.contains("szybkosc")) b.addProperty("speed", c.getDouble("szybkosc"));
        if (c.contains("obrazenia")) b.addProperty("damage", c.getDouble("obrazenia"));
        if (c.contains("odpornosc-na-odrzut")) b.addProperty("knockbackResistance", c.getDouble("odpornosc-na-odrzut"));
        b.addProperty("look", c.getBoolean("patrzenie", true));
        b.addProperty("feet", c.getBoolean("stopy-na-terenie", true));
        b.addProperty("springs", c.getBoolean("sprezyny", true));
        b.addProperty("partHitboxes", c.getBoolean("hitboxy-czesci", true));
        b.addProperty("shatter", c.getBoolean("rozpad", true));
        b.addProperty("randomInterval", c.getDouble("co-ile-sekund-losowe", 14));
        b.add("randomAnimations", toArray(c.getStringList("animacje-losowe")));
        b.add("backgroundAnimations", toArray(c.getStringList("animacje-w-tle")));
        b.addProperty("persistent", false);
        ConfigurationSection weak = c.getConfigurationSection("slabe-punkty");
        if (weak != null) {
            JsonObject w = new JsonObject();
            for (String k : weak.getKeys(false)) w.addProperty(k, weak.getDouble(k));
            b.add("weakPoints", w);
        }
        ConfigurationSection p2 = c.getConfigurationSection("faza-2");
        if (p2 != null) {
            JsonObject p = new JsonObject();
            p.addProperty("below", p2.getDouble("ponizej-zycia", 0.5));
            if (p2.contains("animacja")) p.addProperty("animation", p2.getString("animacja"));
            if (p2.contains("wariant")) p.addProperty("variant", p2.getString("wariant"));
            p.addProperty("damageMultiplier", p2.getDouble("obrazenia-mnoznik", 1.5));
            p.addProperty("sound", p2.getString("dzwiek", "entity.lightning_bolt.thunder"));
            JsonArray phases = new JsonArray();
            phases.add(p);
            b.add("phases", phases);
        }
        JsonArray skills = new JsonArray();
        ConfigurationSection list = c.getConfigurationSection("umiejetnosci");
        if (list != null) {
            for (String key : list.getKeys(false)) {
                ConfigurationSection a = list.getConfigurationSection(key);
                JsonObject s = a == null ? null : legacySkill(key, a);
                if (s != null) skills.add(s);
            }
        }
        b.add("skills", skills);
        return fromJson(b, name);
    }

    private static JsonObject legacySkill(String name, ConfigurationSection a) {
        JsonObject s = new JsonObject();
        s.addProperty("name", name);
        s.addProperty("trigger", "combat");
        s.addProperty("cooldown", a.getDouble("co-ile-sekund", 10));
        s.addProperty("rangeMin", a.getDouble("zasieg-od", 0));
        s.addProperty("rangeMax", a.getDouble("zasieg-do", 16));
        if (a.contains("animacja")) s.addProperty("animation", a.getString("animacja"));
        JsonArray actions = new JsonArray();
        String sound = a.getString("dzwiek");
        switch (a.getString("typ", "")) {
            case "skok-na-gracza" -> {
                double up = a.getDouble("wybicie", 0.25), down = a.getDouble("ladowanie", 1.25);
                if (sound != null) actions.add(action("sound", 0, "sound", sound));
                actions.add(action("leap", up, "duration", Math.max(0.05, down - up), "height", 0, "stopBefore", 1.2));
                actions.add(action("impact", down, "size", 1.2));
                if (sound != null) actions.add(action("sound", down, "sound", sound));
                actions.add(action("damage", down, "radius", 2.5, "amount", a.getDouble("obrazenia", 6), "knockback", 0.8, "up", 0.45));
            }
            case "rzut-iglami" -> {
                if (sound != null) actions.add(action("sound", 0, "sound", sound));
                actions.add(action("projectile", a.getDouble("moment", 0.45), "kind", "arrow", "count", a.getInt("ile", 5),
                        "damage", a.getDouble("obrazenia", 3), "bone", a.getString("kosc", ""), "spread", 7, "speed", 1.8));
            }
            case "ptaki" -> {
                if (sound != null) actions.add(action("sound", 0, "sound", sound));
                actions.add(action("swarm", a.getDouble("moment", 0.4), "entity", "parrot", "count", a.getInt("ile", 3),
                        "damage", a.getDouble("obrazenia", 2), "lifetime", a.getDouble("czas-zycia", 12), "bone", a.getString("kosc", "")));
            }
            case "wielki-skok" -> {
                double up = a.getDouble("wybicie", 0.35), flight = a.getDouble("czas-lotu", 1.5);
                actions.add(action("sound", up, "sound", "entity.ravager.roar"));
                actions.add(action("leap", up, "duration", flight, "height", a.getDouble("wysokosc", 7), "stopBefore", 1.5));
                actions.add(action("impact", up + flight, "size", 3));
                if (sound != null) actions.add(action("sound", up + flight, "sound", sound));
                if (a.contains("dzwiek-oszolomienia")) actions.add(action("sound", up + flight, "sound", a.getString("dzwiek-oszolomienia")));
                actions.add(action("damage", up + flight, "radius", a.getDouble("zasieg-uderzenia", 5), "amount", a.getDouble("obrazenia", 8), "knockback", 1.2, "up", 0.6));
                if (a.contains("animacja-upadku")) actions.add(action("stun", up + flight, "animation", a.getString("animacja-upadku")));
            }
            default -> {
                return null;
            }
        }
        s.add("actions", actions);
        return s;
    }

    private static JsonObject action(String type, double at, Object... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("at", at);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number n) o.addProperty((String) kv[i], n);
            else if (v != null) o.addProperty((String) kv[i], String.valueOf(v));
        }
        return o;
    }

    // ---- mobs.yml ----

    /** Sekcja YAML (mobs.&lt;id&gt; z mobs.yml) jako JSON - ten sam format co pole "behavior". */
    public static JsonObject toJson(ConfigurationSection c) {
        return (JsonObject) toJsonValue(c);
    }

    private static JsonElement toJsonValue(Object v) {
        if (v instanceof ConfigurationSection s) {
            JsonObject o = new JsonObject();
            for (String k : s.getKeys(false)) {
                JsonElement e = toJsonValue(s.get(k));
                if (e != null) o.add(k, e);
            }
            return o;
        }
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                JsonElement x = toJsonValue(e.getValue());
                if (x != null) o.add(String.valueOf(e.getKey()), x);
            }
            return o;
        }
        if (v instanceof List<?> l) {
            JsonArray a = new JsonArray();
            for (Object x : l) {
                JsonElement e = toJsonValue(x);
                if (e != null) a.add(e);
            }
            return a;
        }
        if (v instanceof Number n) return new JsonPrimitive(n);
        if (v instanceof Boolean b) return new JsonPrimitive(b);
        if (v != null) return new JsonPrimitive(String.valueOf(v));
        return null;
    }

    // ---- pomocnicze ----

    private static Params params(JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) continue;
            JsonPrimitive p = e.getValue().getAsJsonPrimitive();
            m.put(e.getKey(), p.isNumber() ? (Object) p.getAsDouble() : p.isBoolean() ? (Object) p.getAsBoolean() : p.getAsString());
        }
        return new Params(Map.copyOf(m));
    }

    private static JsonArray toArray(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    private static Iterable<JsonElement> arr(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr(o, key)) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return List.copyOf(out);
    }

    private static double num(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : def;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static String strOrNull(JsonObject o, String key) {
        String s = str(o, key, null);
        return s == null || s.isBlank() ? null : s;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
    }

    private static String pick(String v, Set<String> allowed, String def) {
        return allowed.contains(v) ? v : def;
    }

    private static double clamp(double v, double min, double max) {
        return Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
    }
}
