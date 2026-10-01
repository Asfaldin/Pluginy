package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobDef;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Ruch "żywy" liczony na serwerze na bieżąco, nałożony na animacje z Kreatora:
 * <ul>
 *   <li>patrzenie - głowa (i szyja, rozłożone na segmenty) obraca się do celu, z granicami i płynnie;</li>
 *   <li>stopy na terenie - pod każdą stopą promień w dół, noga (2-3 segmenty) dopasowuje się do
 *       wysokości gruntu (IK liczone numerycznie, działa dla nóg pionowych i rozłożonych na boki);</li>
 *   <li>sprężyny - ogon, uszy, peleryna, macki reagują na prawdziwy ruch moba w świecie (zakręt, skok,
 *       hamowanie) - bezwładność, której nie da się wypalić w animacji, bo zależy od tego, co mob robi.</li>
 * </ul>
 * Części rozpoznawane po nazwach z Kreatora (head/neck, *leg*, tail/ogon/ear/cape...). Czysta matematyka
 * z dostępem do świata tylko przez {@link Ground} - testowalne bez serwera.
 */
final class MobRig {

    /** Wysokość gruntu pod punktem (świat, bloki) albo NaN, gdy pod spodem pusto. */
    interface Ground {
        double groundY(double x, double yFrom, double z);
    }

    private static final Pattern HEAD = Pattern.compile("^(head|glowa|głowa)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NECK = Pattern.compile("(neck|szyja)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEG = Pattern.compile("(leg|noga|nog[ai]|lapa|łapa)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPRING = Pattern.compile("(tail|ogon|ear|ucho|uszy|cape|peleryn|antenn|czul|macka|tentacle|hair|wlos|włos|kita|mane|grzyw)", Pattern.CASE_INSENSITIVE);

    private final MobDef def;
    private final Map<String, MobDef.Bone> byId = new HashMap<>();
    /** Łańcuchy patrzenia (od nasady szyi do głowy) - osobno dla każdej głowy. */
    final List<List<String>> heads = new ArrayList<>();
    /** Pierwsza głowa (zgodność z testami i starszym kodem). */
    final List<String> look = new ArrayList<>();
    final List<Leg> legs = new ArrayList<>();
    final List<String> springs = new ArrayList<>();

    boolean lookEnabled = true, ikEnabled = true, springsEnabled = true;
    float maxYaw = 70, maxPitch = 40;

    private final Map<String, float[]> theta = new HashMap<>(), omega = new HashMap<>();
    private final Map<String, Vector3f[]> pivotHistory = new HashMap<>();
    private final Map<String, Vector3f> springDir = new HashMap<>();

    /** Noga: kości od biodra do stopy (tylko z pudełkami + przyczep), punkt stopy w układzie ostatniej. */
    static final class Leg {
        final List<String> chain;
        final float[] foot;
        float delta;

        Leg(List<String> chain, float[] foot) {
            this.chain = chain;
            this.foot = foot;
        }
    }

    MobRig(MobDef def) {
        this.def = def;
        for (MobDef.Bone b : def.bones()) byId.put(b.id(), b);
        boolean explicit = def.hasRoles();
        // patrzenie: każda głowa + jej szyja w górę drzewa (hydra z 3 głowami patrzy każdą osobno)
        List<MobDef.Bone> headBones = new ArrayList<>();
        if (explicit) {
            for (MobDef.Bone b : def.bones()) {
                if (!is(b, "head")) continue;
                MobDef.Bone p = b.parent() == null ? null : byId.get(b.parent());
                if (p == null || !is(p, "head")) headBones.add(b);
            }
        } else {
            def.bones().stream().filter(b -> HEAD.matcher(b.name()).find()).findFirst()
                    .or(() -> def.bones().stream().filter(b -> b.name().toLowerCase(Locale.ROOT).contains("head")).findFirst())
                    .ifPresent(headBones::add);
        }
        for (MobDef.Bone head : headBones) {
            List<String> chain = new ArrayList<>();
            for (MobDef.Bone b = head; b != null; b = b.parent() == null ? null : byId.get(b.parent())) {
                if (b != head && !is(b, "neck")) break;
                chain.add(0, b.id());
            }
            heads.add(chain);
        }
        if (!heads.isEmpty()) look.addAll(heads.get(0));
        // nogi: kość "noga", której rodzic nie jest nogą; łańcuch w dół (kolano, stopa) - dowolnie wiele nóg
        for (MobDef.Bone b : def.bones()) {
            if (!is(b, "leg")) continue;
            MobDef.Bone p = b.parent() == null ? null : byId.get(b.parent());
            if (p != null && is(p, "leg")) continue;
            List<String> chain = new ArrayList<>();
            MobDef.Bone cur = b;
            while (cur != null && chain.size() < 4) {
                chain.add(cur.id());
                MobDef.Bone next = null;
                // dalej w dół: najpierw dziecko-noga z pudełkami, inaczej (stare pliki) pierwsze z pudełkami
                for (MobDef.Bone c : def.bones()) if (cur.id().equals(c.parent()) && !c.boxes().isEmpty() && (!explicit || is(c, "leg"))) { next = c; break; }
                cur = next;
            }
            MobDef.Bone last = byId.get(chain.get(chain.size() - 1));
            if (chain.size() < 2 || last.boxes().isEmpty()) continue;
            legs.add(new Leg(List.copyOf(chain), footPoint(last)));
        }
        // sprężyny: nasady łańcuchów (rodzic nie jest sprężyną) z całym poddrzewem
        for (MobDef.Bone b : def.bones()) {
            if (!isSpring(b)) continue;
            MobDef.Bone p = b.parent() == null ? null : byId.get(b.parent());
            if (p != null && isSpring(p)) continue;
            addTree(b.id());
        }
        for (String s : springs) springDir.put(s, dir(byId.get(s)));
    }

    /** Rola części: z aplikacji (nowe pliki) albo - dla starych - po nazwie. */
    private boolean is(MobDef.Bone b, String role) {
        if (def.hasRoles()) return role.equals(def.role(b.id()));
        return switch (role) {
            case "head" -> HEAD.matcher(b.name()).find();
            case "neck" -> NECK.matcher(b.name()).find();
            case "leg" -> LEG.matcher(b.name()).find();
            default -> false;
        };
    }

    private boolean isSpring(MobDef.Bone b) {
        if (def.hasRoles()) {
            String r = def.role(b.id());
            return r.equals("tail") || r.equals("ear") || r.equals("spring");
        }
        return SPRING.matcher(b.name()).find();
    }

    /** Części z daną rolą (nasady łańcuchów) - np. ręce jako domyślne miejsce wystrzału. */
    List<String> roots(String role) {
        List<String> out = new ArrayList<>();
        for (MobDef.Bone b : def.bones()) {
            if (!is(b, role)) continue;
            MobDef.Bone p = b.parent() == null ? null : byId.get(b.parent());
            if (p == null || !is(p, role)) out.add(b.id());
        }
        return out;
    }

    private void addTree(String id) {
        if (springs.contains(id)) return;
        springs.add(id);
        for (MobDef.Bone c : def.bones()) if (id.equals(c.parent())) addTree(c.id());
    }

    /** Najniższy punkt pudełek części (środek podstawy) - tam stopa dotyka ziemi (jednostki modelu). */
    static float[] footPoint(MobDef.Bone b) {
        float maxY = -1e9f, minX = 1e9f, maxX = -1e9f, minZ = 1e9f, maxZ = -1e9f;
        for (float[] x : b.boxes()) {
            maxY = Math.max(maxY, x[1] + x[4]);
            minX = Math.min(minX, x[0]);
            maxX = Math.max(maxX, x[0] + x[3]);
            minZ = Math.min(minZ, x[2]);
            maxZ = Math.max(maxZ, x[2] + x[5]);
        }
        return new float[]{(minX + maxX) / 2, maxY, (minZ + maxZ) / 2};
    }

    private static Vector3f dir(MobDef.Bone b) {
        if (b == null || b.boxes().isEmpty()) return new Vector3f(0, 1, 0);
        Vector3f c = new Vector3f();
        for (float[] x : b.boxes()) c.add(x[0] + x[3] / 2, x[1] + x[4] / 2, x[2] + x[5] / 2);
        return c.lengthSquared() < 1e-6 ? new Vector3f(0, 1, 0) : c.normalize();
    }

    /** Model (bloki, oś Y w dół) -> świat względem stóp moba, dla kąta ciała yaw. */
    static Matrix4f modelToWorld(float yaw) {
        return new Matrix4f().rotateY((float) Math.toRadians(180 - yaw)).scale(-1, -1, 1).translate(0, -1.501f, 0);
    }

    /** Macierz lokalna kości z odchyleniem (jak MobPose.world). */
    static Matrix4f local(MobDef.Bone b, MobPose.Offset o) {
        float[] pivot = b.pivot().clone(), rot = b.rotation().clone(), scale = b.scale().clone();
        if (o != null) for (int i = 0; i < 3; i++) {
            pivot[i] += o.position[i];
            rot[i] += o.rotation[i];
            scale[i] *= o.scale[i];
        }
        return new Matrix4f().translate(pivot[0] / 16f, pivot[1] / 16f, pivot[2] / 16f)
                .rotateZYX((float) Math.toRadians(rot[2]), (float) Math.toRadians(rot[1]), (float) Math.toRadians(rot[0]))
                .scale(scale[0], scale[1], scale[2]);
    }

    private static MobPose.Offset off(Map<String, MobPose.Offset> offsets, String id) {
        return offsets.computeIfAbsent(id, k -> new MobPose.Offset());
    }

    // ---- patrzenie ----

    /**
     * Głowa do celu. target - punkt w świecie względem stóp moba (null = brak celu, głowa wraca).
     * bones - macierze po animacji.
     */
    void applyLook(Map<String, MobPose.Offset> offsets, Map<String, Matrix4f> bones, float yaw, Vector3f target) {
        if (!lookEnabled || heads.isEmpty()) return;
        for (int h = 0; h < heads.size(); h++) applyLook(heads.get(h), h, offsets, bones, yaw, target);
    }

    private final Map<Integer, float[]> headAngles = new HashMap<>();

    private void applyLook(List<String> look, int index, Map<String, MobPose.Offset> offsets, Map<String, Matrix4f> bones, float yaw, Vector3f target) {
        float[] ang = headAngles.computeIfAbsent(index, k -> new float[2]);
        float lookYaw = ang[0], lookPitch = ang[1];
        float[] lookWeights = new float[look.size()];
        for (int i = 0; i < look.size(); i++) lookWeights[i] = look.size() == 1 ? 1 : i == look.size() - 1 ? 0.45f : 0.55f / (look.size() - 1);
        float wantYaw = 0, wantPitch = 0;
        Matrix4f headM = bones.get(look.get(look.size() - 1));
        if (target != null && headM != null) {
            Vector3f t = modelToWorld(yaw).invert().transformPosition(new Vector3f(target));
            Vector3f h = headM.getTranslation(new Vector3f());
            Vector3f d = t.sub(h);
            // obecny kierunek głowy (przód modelu = -Z)
            Vector3f f = headM.transformDirection(new Vector3f(0, 0, -1)).normalize();
            float curYaw = (float) Math.toDegrees(Math.atan2(-f.x, -f.z));
            float curPitch = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, f.y))));
            float yawTo = (float) Math.toDegrees(Math.atan2(-d.x, -d.z));
            float pitchTo = (float) Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
            float dy = ((yawTo - curYaw) % 360 + 540) % 360 - 180;
            // za plecami - nie wykręca głowy, patrzy przed siebie
            if (Math.abs(dy) < maxYaw + 40) {
                wantYaw = Math.max(-maxYaw, Math.min(maxYaw, dy));
                wantPitch = Math.max(-maxPitch, Math.min(maxPitch, pitchTo - curPitch));
            }
        }
        lookYaw += (wantYaw - lookYaw) * 0.22f;
        lookPitch += (wantPitch - lookPitch) * 0.22f;
        ang[0] = lookYaw;
        ang[1] = lookPitch;
        for (int i = 0; i < look.size(); i++) {
            MobPose.Offset o = off(offsets, look.get(i));
            o.rotation[1] += lookYaw * lookWeights[i];
            o.rotation[0] += lookPitch * lookWeights[i];
        }
    }

    // ---- stopy na terenie ----

    /** Położenie stopy (model, bloki) przy danych odchyleniach. */
    Vector3f footModel(Leg leg, Map<String, Matrix4f> bones, Map<String, MobPose.Offset> offsets) {
        MobDef.Bone first = byId.get(leg.chain.get(0));
        Matrix4f m = first.parent() != null && bones.containsKey(first.parent()) ? new Matrix4f(bones.get(first.parent())) : new Matrix4f();
        for (String id : leg.chain) m.mul(local(byId.get(id), offsets.get(id)));
        return m.transformPosition(new Vector3f(leg.foot[0] / 16f, leg.foot[1] / 16f, leg.foot[2] / 16f));
    }

    /**
     * Dopasowanie nóg do gruntu. base - stopy moba w świecie (x, y, z); onGround - czy mob stoi.
     * Zwraca średnie przesunięcie gruntu pod stopami (informacyjnie, testy).
     */
    float applyFeet(Map<String, MobPose.Offset> offsets, Map<String, Matrix4f> bones, float yaw, double bx, double by, double bz, boolean onGround, Ground ground) {
        if (!ikEnabled || legs.isEmpty()) return 0;
        Matrix4f toWorld = modelToWorld(yaw);
        float sum = 0;
        for (Leg leg : legs) {
            Vector3f f = footModel(leg, bones, offsets);
            Vector3f w = toWorld.transformPosition(new Vector3f(f));
            float want = 0;
            if (onGround) {
                double g = ground.groundY(bx + w.x, by + 1.0, bz + w.z);
                // grunt wyżej/niżej niż stopy moba (ograniczone: stopień w górę, blok w dół)
                want = Double.isNaN(g) ? -1f : (float) Math.max(-1.0, Math.min(0.9, g - by));
            }
            leg.delta += (want - leg.delta) * 0.35f;
            sum += leg.delta;
            if (Math.abs(leg.delta) < 0.03f) continue;
            // cel: stopa przesunięta w pionie (świat w górę = model -Y); nie niżej niż zasięg nogi
            Vector3f target = new Vector3f(f).add(0, -leg.delta, 0);
            solve(leg, bones, offsets, target);
        }
        return sum / legs.size();
    }

    /** Punkt obrotu kości w modelu (bloki) przy danych odchyleniach. */
    private Vector3f pivotModel(Leg leg, int upto, Map<String, Matrix4f> bones, Map<String, MobPose.Offset> offsets) {
        MobDef.Bone first = byId.get(leg.chain.get(0));
        Matrix4f m = first.parent() != null && bones.containsKey(first.parent()) ? new Matrix4f(bones.get(first.parent())) : new Matrix4f();
        for (int i = 0; i <= upto; i++) m.mul(local(byId.get(leg.chain.get(i)), offsets.get(leg.chain.get(i))));
        return m.getTranslation(new Vector3f());
    }

    /**
     * IK dwóch kości (udo + kolano): najpierw zgięcie kolana ustawia odległość biodro-stopa na
     * odległość biodro-cel (bisekcja), potem obrót uda kieruje stopę na cel (przeszukanie kąta).
     * Oś zgięcia X (nogi pionowe) albo Z (nogi na boki, pająki) i kierunek kolana - wybrany ten,
     * który daje najmniejszy błąd. Przyczep bez długości (ten sam punkt co udo) jest pomijany.
     */
    private void solve(Leg leg, Map<String, Matrix4f> bones, Map<String, MobPose.Offset> offsets, Vector3f target) {
        int u = 0;
        while (u < leg.chain.size() - 2 && pivotModel(leg, u, bones, offsets).distance(pivotModel(leg, u + 1, bones, offsets)) < 0.01f) u++;
        if (u + 1 >= leg.chain.size()) return;
        String upper = leg.chain.get(u), knee = leg.chain.get(u + 1);
        MobPose.Offset ou = off(offsets, upper), ok = off(offsets, knee);
        float[] u0 = ou.rotation.clone(), k0 = ok.rotation.clone();
        Vector3f hip = pivotModel(leg, u, bones, offsets);
        float want = hip.distance(target);
        float bestErr = footModel(leg, bones, offsets).distance(target);
        float[] bestU = u0.clone(), bestK = k0.clone();
        for (int axis : new int[]{0, 2}) {
            for (int sign : new int[]{1, -1}) {
                System.arraycopy(u0, 0, ou.rotation, 0, 3);
                System.arraycopy(k0, 0, ok.rotation, 0, 3);
                // kolano: odległość biodro-stopa maleje ze zgięciem - bisekcja kąta 0..150
                float lo = 0, hi = 150;
                for (int i = 0; i < 18; i++) {
                    float mid = (lo + hi) / 2;
                    ok.rotation[axis] = k0[axis] + sign * mid;
                    if (footModel(leg, bones, offsets).distance(hip) > want) lo = mid;
                    else hi = mid;
                }
                ok.rotation[axis] = k0[axis] + sign * (lo + hi) / 2;
                // udo: kąt dający stopę najbliżej celu (co 3 stopnie, potem dokładniej)
                float bestA = 0, e = Float.MAX_VALUE;
                for (float a = -90; a <= 90; a += 3) {
                    ou.rotation[axis] = u0[axis] + a;
                    float d = footModel(leg, bones, offsets).distance(target);
                    if (d < e) {
                        e = d;
                        bestA = a;
                    }
                }
                for (float a = bestA - 3; a <= bestA + 3; a += 0.25f) {
                    ou.rotation[axis] = u0[axis] + a;
                    float d = footModel(leg, bones, offsets).distance(target);
                    if (d < e) {
                        e = d;
                        bestA = a;
                    }
                }
                ou.rotation[axis] = u0[axis] + bestA;
                if (e < bestErr - 1e-4f) {
                    bestErr = e;
                    bestU = ou.rotation.clone();
                    bestK = ok.rotation.clone();
                }
            }
        }
        System.arraycopy(bestU, 0, ou.rotation, 0, 3);
        System.arraycopy(bestK, 0, ok.rotation, 0, 3);
    }

    // ---- sprężyny ----

    /**
     * Bezwładność części na bieżąco. bones - macierze (model); world - model -> świat z położeniem moba
     * (bloki). Liczone co tick (dt = 0,05 s) w 4 krokach.
     */
    void applySprings(Map<String, MobPose.Offset> offsets, Map<String, Matrix4f> bones, Matrix4f world) {
        if (!springsEnabled || springs.isEmpty()) return;
        float dt = 0.05f;
        for (String id : springs) {
            Matrix4f m = bones.get(id);
            MobDef.Bone b = byId.get(id);
            if (m == null || b == null || b.parent() == null || !bones.containsKey(b.parent())) continue;
            Vector3f p = new Matrix4f(world).mul(m).getTranslation(new Vector3f());
            Vector3f[] h = pivotHistory.computeIfAbsent(id, k -> new Vector3f[]{new Vector3f(p), new Vector3f(p)});
            Vector3f acc = new Vector3f(p).sub(h[0]).sub(h[0]).add(h[1]).div(dt * dt);
            h[1].set(h[0]);
            h[0].set(p);
            if (acc.length() > 200) acc.normalize(200); // teleport / pierwszy tick
            // przyspieszenie w układzie rodzica (tam działa obrót tej kości)
            Matrix4f parentWorld = new Matrix4f(world).mul(bones.get(b.parent()));
            Vector3f al = new Matrix4f(parentWorld).invert().transformDirection(new Vector3f(acc));
            Vector3f d = springDir.get(id);
            Vector3f torque = new Vector3f(d).cross(al).negate();
            float[] th = theta.computeIfAbsent(id, k -> new float[3]);
            float[] om = omega.computeIfAbsent(id, k -> new float[3]);
            float k = 70, c = 8, gain = 90;
            float[] tq = {torque.x, torque.y, torque.z};
            for (int s = 0; s < 4; s++) {
                float h4 = dt / 4;
                for (int i = 0; i < 3; i++) {
                    float a = gain * tq[i] - k * th[i] - c * om[i];
                    om[i] += a * h4;
                    th[i] = Math.max(-40, Math.min(40, th[i] + om[i] * h4));
                }
            }
            MobPose.Offset o = off(offsets, id);
            for (int i = 0; i < 3; i++) o.rotation[i] += th[i];
        }
    }
}
