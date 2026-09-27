package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobDef;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jeden mob w świecie: niewidzialny husk (chodzi, ma hitbox, nie pali się w słońcu) + części modelu jako item display.
 * Statystyki i umiejętności z config.yml (sekcja moby.<id>) - patrz opis w config.yml.
 */
final class LiveMob {

    /** Umiejętność z config.yml: nazwa, typ i jej ustawienia. */
    private record Ability(String name, String type, ConfigurationSection c) {
        double num(String key, double def) {
            return c.getDouble(key, def);
        }
    }

    /** Ptak wypuszczony przez moba: leci na cel i dziobie, po czasie znika. */
    private static final class Bird {
        final Parrot entity;
        final Player target;
        final int until;
        final double damage;
        int nextHit;

        Bird(Parrot entity, Player target, int until, double damage) {
            this.entity = entity;
            this.target = target;
            this.until = until;
            this.damage = damage;
        }
    }

    private final MobDef def;
    final Husk base;
    private final Map<String, ItemDisplay> parts = new LinkedHashMap<>();
    private final Map<MobDef.Effect, Particle> particles = new HashMap<>();
    private final List<Ability> abilities = new ArrayList<>();
    private final Map<String, Integer> ready = new HashMap<>();
    private final List<Bird> birds = new ArrayList<>();
    private int ticks;
    private int attackStart = -1000;
    private Location last;
    /** Udział chodu 0-1: rośnie, gdy mob idzie, gaśnie po zatrzymaniu - płynne przejście chód/spoczynek. */
    private float walkWeight;
    /** Chwila w animacji chodu - rośnie z przebytą drogą, więc przy zatrzymaniu krok zamiera. */
    private float walkTime;
    private boolean walking;
    /** Pozycje z ostatnich ticków - chód liczymy z drogi przebytej w tym czasie, nie z pojedynczych drgnięć. */
    private final ArrayDeque<Vector> trail = new ArrayDeque<>();
    private static final int TRAIL = 6;
    private final Map<String, Transformation> lastSent = new HashMap<>();
    /** Kąt modelu - goni kąt ciała moba płynnie, bez drobnych drgnięć (stojący mob ciągle lekko się obraca). */
    private float modelYaw = Float.NaN;
    /** Macierze kości z ostatniego ticka - skąd wylatują igły i ptaki. */
    private Map<String, Matrix4f> lastBones = Map.of();

    /** Trwająca umiejętność (null = zwykłe chodzenie i bicie) i jej stan. */
    private Ability action;
    private Player target;
    private int actionStart;
    private boolean fired;
    /** Wielki skok: 0 = przysiad, 1 = lot, 2 = siedzi oszołomiony. */
    private int phase;
    private Location leapFrom, leapTo;

    LiveMob(MobDef def, Location at, ConfigurationSection cfg) {
        this.def = def;
        this.base = at.getWorld().spawn(at, Husk.class, z -> {
            z.setInvisible(true);
            z.setSilent(true);
            z.setShouldBurnInDay(false);
            z.setAdult();
            z.setPersistent(false);
            z.setRemoveWhenFarAway(false);
            z.getEquipment().clear();
            set(z, Attribute.SCALE, Math.max(0.1, def.hitboxHeight() / 1.95));
            set(z, Attribute.SPAWN_REINFORCEMENTS, 0);
            if (cfg != null) {
                if (cfg.contains("zycie")) {
                    set(z, Attribute.MAX_HEALTH, cfg.getDouble("zycie"));
                    z.setHealth(cfg.getDouble("zycie"));
                }
                if (cfg.contains("szybkosc")) set(z, Attribute.MOVEMENT_SPEED, cfg.getDouble("szybkosc"));
                if (cfg.contains("obrazenia")) set(z, Attribute.ATTACK_DAMAGE, cfg.getDouble("obrazenia"));
                if (cfg.contains("odpornosc-na-odrzut")) set(z, Attribute.KNOCKBACK_RESISTANCE, cfg.getDouble("odpornosc-na-odrzut"));
            }
        });
        ConfigurationSection list = cfg == null ? null : cfg.getConfigurationSection("umiejetnosci");
        if (list != null) {
            for (String name : list.getKeys(false)) {
                ConfigurationSection c = list.getConfigurationSection(name);
                if (c == null) continue;
                abilities.add(new Ability(name, c.getString("typ", ""), c));
                // Pierwsze użycie po kilku sekundach, każda umiejętność w innej chwili.
                ready.put(name, 60 + ThreadLocalRandom.current().nextInt(100));
            }
        }
        // Części powstają „na prosto” (kąt 0, bez pochylenia) - ich cały obrót liczymy sami w macierzy.
        // Z kierunkiem gracza gra dokładała go do każdej części: mob chodził bokiem i był pochylony.
        Location straight = straight(at);
        for (MobDef.Bone b : def.bones()) {
            if (!b.visible()) continue;
            ItemStack item = new ItemStack(Material.PAPER);
            String[] key = b.item().split(":", 2);
            item.setData(DataComponentTypes.ITEM_MODEL, Key.key(key[0], key[1]));
            ItemDisplay d = at.getWorld().spawn(straight, ItemDisplay.class, e -> {
                e.setItemStack(item);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setPersistent(false);
                // Pierwsze ułożenie od razu (bez wygładzania) - inaczej części lecą na miejsce z pozycji startowej.
                e.setInterpolationDuration(0);
                // Przesunięcie za mobem wygładzane przez 2 ticki (gra wygładza ruch moba podobnie).
                e.setTeleportDuration(2);
                // Niewidoczne przez pierwsze ticki - aż ułożenie części dojdzie do gracza (patrz tick).
                e.setViewRange(0f);
                if (b.glow()) e.setBrightness(new Display.Brightness(15, 15));
            });
            parts.put(b.id(), d);
        }
        for (MobDef.Effect e : def.effects()) {
            NamespacedKey k = NamespacedKey.fromString(e.particle().toLowerCase(Locale.ROOT));
            Particle p = k == null ? null : Registry.PARTICLE_TYPE.get(k);
            // Tylko cząsteczki bez dodatkowych danych (kolor, blok...) - reszta pomijana w teście.
            if (p != null && p.getDataType() == Void.class) particles.put(e, p);
        }
        last = at.clone();
        tick();
    }

    private static void set(Husk z, Attribute attribute, double value) {
        AttributeInstance a = z.getAttribute(attribute);
        if (a != null) a.setBaseValue(value);
    }

    private static Location straight(Location l) {
        Location s = l.clone();
        s.setYaw(0);
        s.setPitch(0);
        return s;
    }

    boolean alive() {
        return base.isValid() && !base.isDead();
    }

    /** Ptak tego moba (bez łupów po śmierci). */
    boolean ownsBird(Entity e) {
        for (Bird b : birds) if (b.entity.equals(e)) return true;
        return false;
    }

    void attack() {
        if (action != null) return; // obrażenia z umiejętności to nie zwykłe uderzenie
        attackStart = ticks;
    }

    void remove() {
        parts.values().forEach(ItemDisplay::remove);
        parts.clear();
        birds.forEach(b -> b.entity.remove());
        birds.clear();
        if (base.isValid()) base.remove();
    }

    private MobDef.Anim find(String... names) {
        for (String n : names) {
            if (n == null) continue;
            for (MobDef.Anim a : def.animations().values()) if (a.name().equalsIgnoreCase(n)) return a;
        }
        return null;
    }

    /** Animacje grane przez plugin w konkretnych chwilach - nie zapętlają się same. */
    private boolean special(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("idle") || n.equals("walk") || n.equals("attack") || n.equals("jump")) return true;
        for (Ability a : abilities) {
            if (n.equalsIgnoreCase(a.c().getString("animacja", "")) || n.equalsIgnoreCase(a.c().getString("animacja-upadku", ""))) return true;
        }
        return false;
    }

    // ---- umiejętności ----

    /** Położenie kości w świecie (np. dziupla, z której wylatują ptaki). */
    private Location bonePos(String bone) {
        Location loc = base.getLocation();
        Matrix4f m = bone == null ? null : lastBones.get(bone);
        if (m == null) return loc.add(0, def.hitboxHeight() * 0.6, 0);
        Vector3f p = MobPose.displayMatrix(Float.isNaN(modelYaw) ? 0 : modelYaw, m, 1).getTranslation(new Vector3f());
        return loc.add(p.x, p.y, p.z);
    }

    private void face(Location to) {
        Vector d = to.toVector().subtract(base.getLocation().toVector());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        base.setRotation(yaw, 0);
        base.setBodyYaw(yaw);
    }

    private void sound(Location at, String key) {
        if (key == null || key.isBlank()) return;
        NamespacedKey k = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        Sound s = k == null ? null : Registry.SOUNDS.get(k);
        if (s != null) at.getWorld().playSound(at, s, 2f, 0.8f);
    }

    /** Czy któraś umiejętność może ruszyć teraz - jeśli tak, zaczyna ją. */
    private void tryStart() {
        if (abilities.isEmpty() || !base.isOnGround() || !(base.getTarget() instanceof Player p)
                || p.getWorld() != base.getWorld() || p.isDead()) return;
        double dist = p.getLocation().distance(base.getLocation());
        for (Ability a : abilities) {
            if (ticks < ready.getOrDefault(a.name(), 0)) continue;
            if (dist < a.num("zasieg-od", 0) || dist > a.num("zasieg-do", 16)) continue;
            if (!List.of("skok-na-gracza", "rzut-iglami", "ptaki", "wielki-skok").contains(a.type())) continue;
            action = a;
            target = p;
            actionStart = ticks;
            fired = false;
            phase = 0;
            base.setAI(false);
            face(p.getLocation());
            if (a.type().equals("skok-na-gracza")) startLeap(p, 1.2);
            if (!a.type().equals("wielki-skok")) sound(base.getLocation(), a.c().getString("dzwiek"));
            return;
        }
    }

    private void startLeap(Player p, double stopBefore) {
        Location now = base.getLocation();
        Vector dir = p.getLocation().toVector().subtract(now.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) dir = now.getDirection().setY(0);
        dir.normalize();
        leapFrom = now.clone();
        leapTo = p.getLocation().clone().subtract(dir.clone().multiply(stopBefore));
        leapFrom.setDirection(dir);
        leapTo.setDirection(dir);
    }

    private void finish() {
        ready.put(action.name(), ticks + (int) (action.num("co-ile-sekund", 10) * 20));
        action = null;
        target = null;
        base.setAI(true);
        trail.clear();
    }

    /** Prowadzi trwającą umiejętność. Zwraca [animacja, chwila] do zagrania albo null. */
    private Object[] tickAction() {
        if (action == null) {
            if (ticks > 40) tryStart();
            if (action == null) return null;
        }
        if (target != null && (!target.isValid() || target.isDead())) target = null;
        float t = (ticks - actionStart) / 20f;
        MobDef.Anim anim = find(action.c().getString("animacja"));
        float length = anim != null ? anim.length() : 1f;
        switch (action.type()) {
            case "skok-na-gracza" -> {
                float up = (float) action.num("wybicie", 0.25), down = (float) action.num("ladowanie", 1.25);
                float k = Math.max(0, Math.min(1, (t - up) / Math.max(0.05f, down - up)));
                moveAlong(k, 0);
                if (!fired && t >= down) {
                    fired = true;
                    Location at = base.getLocation();
                    at.getWorld().spawnParticle(Particle.CLOUD, at, 30, 1.2, 0.1, 1.2, 0.05);
                    sound(at, action.c().getString("dzwiek"));
                    hitAround(at, 2.5, action.num("obrazenia", 6), 0.8, 0.45);
                }
                if (t >= length) finish();
            }
            case "rzut-iglami" -> {
                if (target != null) face(target.getLocation());
                if (!fired && t >= action.num("moment", 0.45)) {
                    fired = true;
                    if (target != null) throwNeedles();
                }
                if (t >= length) finish();
            }
            case "ptaki" -> {
                if (target != null) face(target.getLocation());
                if (!fired && t >= action.num("moment", 0.4)) {
                    fired = true;
                    if (target != null) releaseBirds();
                }
                if (t >= length) finish();
            }
            case "wielki-skok" -> {
                return bigJump(t, anim);
            }
            default -> finish();
        }
        return action == null ? null : new Object[]{anim, t};
    }

    /** Ciało moba w drodze od startu do celu (k 0-1), z łukiem w górę o wysokości height (bloki). */
    private void moveAlong(float k, double height) {
        Location at = leapFrom.clone().add(leapTo.toVector().subtract(leapFrom.toVector()).multiply(k));
        at.add(0, height * 4 * k * (1 - k), 0);
        at.setDirection(leapTo.getDirection());
        base.teleport(at);
        base.setBodyYaw(at.getYaw());
    }

    private void hitAround(Location at, double radius, double damage, double push, double up) {
        for (Player p : at.getNearbyPlayers(radius)) {
            p.damage(damage, base);
            Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 1);
            p.setVelocity(away.normalize().multiply(push).setY(up));
        }
    }

    private void throwNeedles() {
        Location from = bonePos(action.c().getString("kosc"));
        int count = Math.max(1, (int) action.num("ile", 5));
        double damage = action.num("obrazenia", 3);
        Vector aim = target.getEyeLocation().toVector().subtract(from.toVector());
        // Lekko w górę na dalszy dystans - strzała opada.
        aim.setY(aim.getY() + aim.length() * 0.08);
        for (int i = 0; i < count; i++) {
            Arrow a = base.getWorld().spawnArrow(from, aim, 1.8f, 7f);
            a.setShooter(base);
            a.setDamage(damage / 1.8);
            a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            a.setLifetimeTicks(1100); // znika po ok. 5 s w ziemi
        }
    }

    private void releaseBirds() {
        Location from = bonePos(action.c().getString("kosc"));
        int count = Math.max(1, (int) action.num("ile", 3));
        int life = (int) (action.num("czas-zycia", 12) * 20);
        double damage = action.num("obrazenia", 2);
        for (int i = 0; i < count; i++) {
            Location at = from.clone().add(ThreadLocalRandom.current().nextDouble(-0.4, 0.4), 0, ThreadLocalRandom.current().nextDouble(-0.4, 0.4));
            Parrot p = base.getWorld().spawn(at, Parrot.class, e -> {
                e.setVariant(Parrot.Variant.GRAY);
                e.setPersistent(false);
                e.setAI(false);
            });
            birds.add(new Bird(p, target, ticks + life, damage));
            base.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 3, 0.2, 0.2, 0.2, 0.01);
        }
    }

    /** Wielki skok: przysiad, lot łukiem na gracza, upadek (kurz, obrażenia), siedzenie oszołomionym, wstanie. */
    private Object[] bigJump(float t, MobDef.Anim jumpAnim) {
        float up = (float) action.num("wybicie", 0.35), flight = (float) action.num("czas-lotu", 1.5);
        if (phase == 0) {
            if (target != null) face(target.getLocation());
            if (t >= up) {
                if (target == null) {
                    finish();
                    return null;
                }
                startLeap(target, 1.5);
                phase = 1;
                sound(base.getLocation(), "entity.ravager.roar");
            }
        }
        if (phase == 1) {
            float k = Math.min(1, (t - up) / Math.max(0.1f, flight));
            moveAlong(k, action.num("wysokosc", 7));
            if (k >= 1) {
                phase = 2;
                actionStart = ticks;
                Location at = base.getLocation();
                dust(at);
                sound(at, action.c().getString("dzwiek"));
                sound(at, action.c().getString("dzwiek-oszolomienia"));
                hitAround(at, action.num("zasieg-uderzenia", 5), action.num("obrazenia", 8), 1.2, 0.6);
            }
            // Poza z końca animacji skoku trzyma się przez cały lot.
            return jumpAnim == null ? new Object[]{null, 0f} : new Object[]{jumpAnim, Math.min(t, jumpAnim.length())};
        }
        if (phase == 2) {
            MobDef.Anim fall = find(action.c().getString("animacja-upadku"));
            float ft = (ticks - actionStart) / 20f;
            if (fall == null || ft >= fall.length()) {
                finish();
                return null;
            }
            return new Object[]{fall, ft};
        }
        return jumpAnim == null ? new Object[]{null, 0f} : new Object[]{jumpAnim, Math.min(t, jumpAnim.length())};
    }

    private void dust(Location at) {
        var w = at.getWorld();
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 40, 2.5, 0.3, 2.5, 0.02);
        w.spawnParticle(Particle.CLOUD, at, 80, 3, 0.2, 3, 0.15);
        Material ground = at.clone().subtract(0, 0.5, 0).getBlock().getType();
        if (ground.isSolid()) w.spawnParticle(Particle.BLOCK, at, 150, 3, 0.2, 3, 0.1, ground.createBlockData());
    }

    private void tickBirds() {
        Iterator<Bird> it = birds.iterator();
        while (it.hasNext()) {
            Bird b = it.next();
            Parrot p = b.entity;
            boolean targetGone = !b.target.isValid() || b.target.isDead() || b.target.getWorld() != p.getWorld();
            if (!p.isValid() || ticks >= b.until || targetGone) {
                if (p.isValid()) {
                    p.getWorld().spawnParticle(Particle.POOF, p.getLocation(), 5, 0.2, 0.2, 0.2, 0.02);
                    p.remove();
                }
                it.remove();
                continue;
            }
            Location from = p.getLocation();
            Vector to = b.target.getEyeLocation().toVector().subtract(new Vector(0, 0.3, 0)).subtract(from.toVector());
            double dist = to.length();
            if (dist > 1.1) {
                // Leci na gracza lekko falując (jak trzepoczący ptak).
                Vector step = to.normalize().multiply(0.4);
                step.setY(step.getY() + Math.sin((ticks + p.getEntityId()) * 0.5) * 0.08);
                Location next = from.clone().add(step);
                next.setDirection(step);
                p.teleport(next);
            } else if (ticks >= b.nextHit) {
                b.nextHit = ticks + 20;
                b.target.damage(b.damage, p);
            }
        }
    }

    // ---- ruch i wygląd ----

    void tick() {
        ticks++;
        Object[] act = tickAction();
        tickBirds();
        Location loc = base.getLocation();
        double moved = loc.toVector().setY(0).distanceSquared(last.toVector().setY(0));
        last = loc.clone();
        float sec = ticks / 20f;

        // Chód i spoczynek (mieszane) + wszystkie pozostałe zapętlone animacje (orbita, muszki...) + atak na chwilę.
        List<MobPose.Playing> playing = new ArrayList<>();
        // Chód: udział rośnie i gaśnie stopniowo (ok. pół sekundy), krok przesuwa się z drogą, nie z czasem.
        // Mob przepychający się przy graczu drga w przód i w tył - te drgnięcia się znoszą, więc liczy się
        // tylko droga od położenia sprzed kilku ticków (prawdziwy marsz).
        trail.addLast(loc.toVector().setY(0));
        if (trail.size() > TRAIL + 1) trail.removeFirst();
        float net = trail.size() > TRAIL ? (float) trail.getFirst().distance(trail.getLast()) / TRAIL : 0;
        walking = action == null && net > (walking ? 0.012f : 0.03f);
        if (action != null) walkWeight = 0;
        walkWeight = Math.max(0, Math.min(1, walkWeight + (walking ? 0.12f : -0.1f)));
        if (walking) walkTime += 0.05f * Math.max(0.5f, Math.min(2f, net / 0.08f));
        MobDef.Anim walk = find("walk"), idle = find("idle");
        if (walk != null) playing.add(new MobPose.Playing(walk, walkTime % Math.max(0.05f, walk.length()), walkWeight));
        // W trakcie umiejętności spoczynek nie gra - ruchy (np. machanie rękami) gryzłyby się z nią.
        if (idle != null && action == null) playing.add(new MobPose.Playing(idle, sec % Math.max(0.05f, idle.length()), 1 - walkWeight));
        for (MobDef.Anim a : def.animations().values()) {
            if (!special(a.name()) && a.loop()) playing.add(new MobPose.Playing(a, sec % Math.max(0.05f, a.length()), 1));
        }
        if (act != null && act[0] instanceof MobDef.Anim a) playing.add(new MobPose.Playing(a, (Float) act[1], 1));
        MobDef.Anim attack = find("attack");
        if (attack != null && (ticks - attackStart) / 20f < attack.length()) playing.add(new MobPose.Playing(attack, (ticks - attackStart) / 20f, 1));

        // Pierwsze 3 ticki części są niewidoczne i bez wygładzania; potem pokazują się od razu na miejscu.
        if (ticks == 3) parts.values().forEach(d -> {
            d.setInterpolationDuration(3);
            d.setViewRange(2f);
        });
        Map<String, Matrix4f> bones = MobPose.boneMatrices(def, MobPose.offsets(playing));
        lastBones = bones;
        float body = base.getBodyYaw();
        if (Float.isNaN(modelYaw)) modelYaw = body;
        float diff = ((body - modelYaw) % 360 + 540) % 360 - 180;
        if (Math.abs(diff) > 3) modelYaw += diff * (walking || action != null ? 0.5f : 0.3f);
        float yaw = modelYaw;
        for (MobDef.Bone b : def.bones()) {
            ItemDisplay d = parts.get(b.id());
            if (d == null) continue;
            // Część stoi w stopach moba, „na prosto” (kąt 0) - obraca się wokół stóp, więc stopy prawie się nie ruszają.
            if (d.getLocation().distanceSquared(loc) > 0.00001) d.teleport(straight(loc));
            Matrix4f m = MobPose.displayMatrix(yaw, bones.get(b.id()), b.modelScale());
            // Rozłożone na położenie, obrót i skalę - gotową macierz gra rozkłada przy wygładzaniu za każdym
            // razem trochę inaczej (przy równej skali rozkład nie jest jednoznaczny) i części skaczą.
            Transformation tr = new Transformation(m.getTranslation(new Vector3f()), m.getNormalizedRotation(new Quaternionf()),
                    m.getScale(new Vector3f()), new Quaternionf());
            // Tylko gdy ułożenie się zmieniło: sam sygnał „zacznij wygładzanie od nowa” przy niezmienionym ułożeniu
            // cofa część w grze do początku poprzedniego ruchu - co tick, więc stojąca część drży.
            if (tr.equals(lastSent.put(b.id(), tr))) continue;
            d.setInterpolationDelay(0);
            d.setTransformation(tr);
        }

        for (Map.Entry<MobDef.Effect, Particle> e : particles.entrySet()) {
            MobDef.Effect f = e.getKey();
            if (ThreadLocalRandom.current().nextFloat() > f.rate() / 20f) continue;
            Matrix4f at = f.bone() != null && bones.containsKey(f.bone()) ? new Matrix4f(bones.get(f.bone())) : new Matrix4f();
            at.translate(f.offset()[0] / 16f, f.offset()[1] / 16f, f.offset()[2] / 16f);
            Vector3f p = MobPose.displayMatrix(yaw, at, 1).getTranslation(new Vector3f());
            double s = f.spread() / 16.0;
            base.getWorld().spawnParticle(e.getValue(), loc.clone().add(p.x, p.y, p.z), 1, s, s, s, 0);
        }
    }
}
