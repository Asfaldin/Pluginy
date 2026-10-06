package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobDef;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Składanie moba: macierz każdej części (kości) dokładnie jak w grze przy rysowaniu mobów
 * (ModelPart.translateAndRotate: przesunięcie o punkt obrotu, obrót Z-Y-X, skala), z animacjami
 * nałożonymi na pozę spoczynkową. Czysta matematyka - testy w MobPoseTest.
 */
public final class MobPose {

    private MobPose() {}

    /** Postęp 0-1 po krzywej przejścia - te same wzory co w Kreatorze (back i elastic wychodzą poza 0-1). */
    public static float ease(String e, float f) {
        if (e == null) return f;
        switch (e) {
            case "step": return 0;
            case "smooth": return f * f * (3 - 2 * f);
            case "in": return f * f * f;
            case "out": return 1 - (1 - f) * (1 - f) * (1 - f);
            case "back": {
                double c = 1.70158;
                return (float) (1 + (c + 1) * Math.pow(f - 1, 3) + c * Math.pow(f - 1, 2));
            }
            case "elastic":
                return f <= 0 ? 0 : f >= 1 ? 1 : (float) (Math.pow(2, -10 * f) * Math.sin((f * 10 - 0.75) * 2 * Math.PI / 3) + 1);
            case "bounce": {
                double n = 7.5625, d = 2.75, x = f;
                if (x < 1 / d) return (float) (n * x * x);
                if (x < 2 / d) return (float) (n * (x -= 1.5 / d) * x + 0.75);
                if (x < 2.5 / d) return (float) (n * (x -= 2.25 / d) * x + 0.9375);
                return (float) (n * (x -= 2.625 / d) * x + 0.984375);
            }
            default: return f;
        }
    }

    /** Wariant wyglądu w chwili t animacji (ostatnie przełączenie przed t) albo null, gdy animacja nie przełącza. */
    public static String variantAt(MobDef.Anim anim, float t) {
        String v = null;
        for (MobDef.VariantKey k : anim.variants()) if (k.t() <= t + 1e-4f) v = k.variant();
        return v;
    }

    /** Wartość ścieżki w chwili t (interpolacja jak w Kreatorze, z tymi samymi przejściami). */
    public static float[] sample(List<MobDef.Key> keys, float t) {
        if (keys == null || keys.isEmpty()) return null;
        if (t <= keys.get(0).t()) return keys.get(0).v();
        MobDef.Key last = keys.get(keys.size() - 1);
        if (t >= last.t()) return last.v();
        for (int i = 0; i < keys.size() - 1; i++) {
            MobDef.Key a = keys.get(i), b = keys.get(i + 1);
            if (t < a.t() || t > b.t()) continue;
            float f = ease(a.easing(), b.t() == a.t() ? 0 : (t - a.t()) / (b.t() - a.t()));
            float[] out = new float[3];
            for (int j = 0; j < 3; j++) out[j] = a.v()[j] + (b.v()[j] - a.v()[j]) * f;
            return out;
        }
        return last.v();
    }

    /** Odchylenia od pozy spoczynkowej jednej kości (suma wszystkich grających animacji). */
    public static final class Offset {
        final float[] rotation = new float[3];
        final float[] position = new float[3];
        final float[] scale = {1, 1, 1};
    }

    /** Grająca animacja: chwila (sekundy) i waga 0-1 (płynne przejścia, np. chód gaśnie przy zatrzymaniu). */
    public record Playing(MobDef.Anim anim, float time, float weight) {}

    /** Łączy kilka animacji (np. chód + orbita kryształów), każdą ze swoją chwilą i wagą. */
    public static Map<String, Offset> offsets(List<Playing> playing) {
        Map<String, Offset> out = new HashMap<>();
        for (Playing p : playing) {
            if (p.weight() <= 0) continue;
            float w = Math.min(1, p.weight());
            for (Map.Entry<String, MobDef.Track> e : p.anim().tracks().entrySet()) {
                Offset o = out.computeIfAbsent(e.getKey(), k -> new Offset());
                float t = p.time();
                float[] r = sample(e.getValue().rotation(), t), pos = sample(e.getValue().position(), t), s = sample(e.getValue().scale(), t);
                for (int i = 0; i < 3; i++) {
                    if (r != null) o.rotation[i] += r[i] * w;
                    if (pos != null) o.position[i] += pos[i] * w;
                    if (s != null) o.scale[i] *= 1 + (s[i] - 1) * w;
                }
            }
        }
        return out;
    }

    /** Macierze kości w układzie moba (jednostki = bloki, oś Y w dół jak w modelu). */
    public static Map<String, Matrix4f> boneMatrices(MobDef mob, Map<String, Offset> offsets) {
        Map<String, MobDef.Bone> byId = new HashMap<>();
        for (MobDef.Bone b : mob.bones()) byId.put(b.id(), b);
        Map<String, Matrix4f> out = new HashMap<>();
        for (MobDef.Bone b : mob.bones()) world(b, byId, offsets, out);
        return out;
    }

    private static Matrix4f world(MobDef.Bone b, Map<String, MobDef.Bone> byId, Map<String, Offset> offsets, Map<String, Matrix4f> done) {
        Matrix4f cached = done.get(b.id());
        if (cached != null) return cached;
        Offset o = offsets.get(b.id());
        float[] pivot = b.pivot().clone(), rot = b.rotation().clone(), scale = b.scale().clone();
        if (o != null) {
            for (int i = 0; i < 3; i++) {
                pivot[i] += o.position[i];
                rot[i] += o.rotation[i];
                scale[i] *= o.scale[i];
            }
        }
        Matrix4f local = new Matrix4f()
                .translate(pivot[0] / 16f, pivot[1] / 16f, pivot[2] / 16f)
                .rotateZYX((float) Math.toRadians(rot[2]), (float) Math.toRadians(rot[1]), (float) Math.toRadians(rot[0]))
                .scale(scale[0], scale[1], scale[2]);
        MobDef.Bone parent = b.parent() != null ? byId.get(b.parent()) : null;
        Matrix4f m = parent != null ? new Matrix4f(world(parent, byId, offsets, done)).mul(local) : local;
        done.put(b.id(), m);
        return m;
    }

    /**
     * Obrót z macierzy części, która ma też skalę (części powiększone/zmniejszone, skala kości, cały mob w animacji).
     * getNormalizedRotation zakłada macierz BEZ skali - przy skali innej niż 1 dawała kwaternion o długości różnej
     * od 1, a gra mnoży nim część i dokłada skalę jeszcze raz (np. nadruk 3x za mały, części moba w skali 1,8 za duże).
     */
    public static Quaternionf rotationOf(Matrix4f m) {
        return m.getUnnormalizedRotation(new Quaternionf()).normalize();
    }

    /**
     * Macierz wyświetlanego przedmiotu części: obrót moba (jak gra: 180 - kąt ciała), odwrócenie
     * osi modelu (skala -1, -1, 1 i przesunięcie o 1,501 w dół - stopy na ziemi), macierz kości i
     * powiększenie modelu części, jeśli przy eksporcie trzeba go było zmniejszyć. Na końcu obrót o 180 stopni:
     * gra sama obraca każdy wyświetlany przedmiot o 180 stopni wokół Y - bez tego mob stałby tyłem.
     */
    public static Matrix4f displayMatrix(float bodyYaw, Matrix4f bone, float modelScale) {
        return new Matrix4f()
                .rotateY((float) Math.toRadians(180 - bodyYaw))
                .scale(-1, -1, 1)
                .translate(0, -1.501f, 0)
                .mul(bone)
                .scale(1f / modelScale)
                .rotateY((float) Math.PI);
    }
}
