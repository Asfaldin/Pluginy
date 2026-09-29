package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobDef;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.FluidCollisionMode;
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
import org.bukkit.entity.Interaction;
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
import java.util.UUID;
import java.util.regex.Pattern;
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

    // ---- ruch żywy, wygląd, śmierć (MobRig, warianty, rozpad) ----
    private final MobRig rig;
    /** Punkt odniesienia dla sprężyn (duże współrzędne świata tracą precyzję we float). */
    private final Location origin;
    /** Wygląd (wariant) - zostaje po animacji, która go przełączyła (np. faza 2). */
    private String variant = "base";
    private String shownVariant = "base";
    /** Jednorazowa animacja bez umiejętności (losowe zachowanie, wejście w fazę 2). */
    private MobDef.Anim oneShot;
    private int oneShotStart;
    private int nextRandom = 200;
    private boolean phase2;
    private double damageMultiplier = 1;
    private float runWeight;
    private final ConfigurationSection cfg;
    /** Hitboxy części: obiekt interakcji -> kość. */
    private final Map<UUID, String> hitParts = new HashMap<>();
    private final Map<String, Interaction> hitboxes = new LinkedHashMap<>();
    /** Śmierć: -1 = żyje, potem tick rozpoczęcia; rozpad części po animacji śmierci. */
    private int deathStart = -1;
    private Location deathLoc;
    private final Map<String, float[]> shards = new HashMap<>();
    private boolean finished;
    private static final Pattern BACKGROUND_EXCLUDE = Pattern.compile("^(run|fly|glide|sleep|swim|sit|lie|move|death|hurt|takeoff|land|charge|phase.*)$", Pattern.CASE_INSENSITIVE);

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
        origin = at.clone();
        this.cfg = cfg;
        rig = new MobRig(def);
        if (cfg != null) {
            rig.lookEnabled = cfg.getBoolean("patrzenie", true);
            rig.ikEnabled = cfg.getBoolean("stopy-na-terenie", true);
            rig.springsEnabled = cfg.getBoolean("sprezyny", true);
        }
        if (cfg == null || cfg.getBoolean("hitboxy-czesci", true)) spawnHitboxes(at);
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
        hitboxes.values().forEach(Interaction::remove);
        hitboxes.clear();
        hitParts.clear();
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
        // Lista w config.yml: tylko te pętle grają stale (np. orbita kryształów); bez listy - wszystkie
        // pętle poza stanami (bieg, lot, sen...), które plugin włącza sam albo wcale.
        List<String> bg = cfg == null ? null : cfg.getStringList("animacje-w-tle");
        if (bg != null && !bg.isEmpty()) return bg.stream().noneMatch(x -> x.equalsIgnoreCase(n));
        if (BACKGROUND_EXCLUDE.matcher(n).matches()) return true;
        for (String x : cfg == null ? List.<String>of() : cfg.getStringList("animacje-losowe")) if (x.equalsIgnoreCase(n)) return true;
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

    boolean dying() {
        return deathStart >= 0;
    }

    boolean finished() {
        return finished;
    }

    /** Kość trafionej części (null = to nie hitbox tego moba). */
    String partOf(Entity e) {
        return hitParts.get(e.getUniqueId());
    }

    /** Cios gracza w część: obrażenia jak zwykły cios (z naładowaniem ataku), słaby punkt mocniej. */
    void hitPart(Player p, String bone) {
        if (!alive()) return;
        double dmg = 1;
        var a = p.getAttribute(Attribute.ATTACK_DAMAGE);
        if (a != null) dmg = a.getValue();
        float charge = p.getAttackCooldown();
        dmg *= 0.2 + charge * charge * 0.8;
        boolean crit = charge > 0.9f && p.getFallDistance() > 0 && !p.isOnGround();
        if (crit) dmg *= 1.5;
        double mult = 1;
        String name = def.bones().stream().filter(b -> b.id().equals(bone)).map(MobDef.Bone::name).findFirst().orElse(bone);
        ConfigurationSection weak = cfg == null ? null : cfg.getConfigurationSection("slabe-punkty");
        if (weak != null && weak.contains(name)) mult = weak.getDouble(name);
        else if (name.equalsIgnoreCase("head") || name.equalsIgnoreCase("glowa")) mult = 1.5;
        base.damage(dmg * mult, p);
        Interaction box = hitboxes.get(bone);
        Location at = box != null ? box.getLocation().add(0, box.getInteractionHeight() / 2, 0) : base.getLocation();
        at.getWorld().spawnParticle(mult > 1 || crit ? Particle.CRIT : Particle.DAMAGE_INDICATOR, at, mult > 1 ? 10 : 3, 0.2, 0.2, 0.2, 0.1);
        p.resetCooldown();
    }

    private void spawnHitboxes(Location at) {
        // największe części (objętość pudełek), maks. 12 - każda dostaje własny obszar trafienia
        List<MobDef.Bone> big = new ArrayList<>();
        for (MobDef.Bone b : def.bones()) {
            if (b.boxes().isEmpty() || !b.visible()) continue;
            float vol = 0, ext = 0;
            for (float[] x : b.boxes()) {
                vol += Math.max(0.5f, x[3]) * Math.max(0.5f, x[4]) * Math.max(0.5f, x[5]);
                ext = Math.max(ext, Math.max(x[3], Math.max(x[4], x[5])));
            }
            if (ext >= 5) big.add(b);
        }
        big.sort((x, y) -> Float.compare(volume(y), volume(x)));
        for (MobDef.Bone b : big.subList(0, Math.min(12, big.size()))) {
            Interaction i = at.getWorld().spawn(at, Interaction.class, e -> {
                e.setPersistent(false);
                e.setResponsive(false);
                e.setInteractionWidth(0.5f);
                e.setInteractionHeight(0.5f);
            });
            hitboxes.put(b.id(), i);
            hitParts.put(i.getUniqueId(), b.id());
        }
    }

    private static float volume(MobDef.Bone b) {
        float v = 0;
        for (float[] x : b.boxes()) v += Math.max(0.5f, x[3]) * Math.max(0.5f, x[4]) * Math.max(0.5f, x[5]);
        return v;
    }

    /** Hitboxy za częściami: prostopadłościan otaczający pudełka części w świecie (bez obrotu - jak w grze). */
    private void moveHitboxes(Location loc, float yaw, Map<String, Matrix4f> bones) {
        if (hitboxes.isEmpty() || ticks % 2 != 0) return;
        Matrix4f toWorld = MobRig.modelToWorld(yaw);
        for (Map.Entry<String, Interaction> e : hitboxes.entrySet()) {
            MobDef.Bone b = def.bones().stream().filter(x -> x.id().equals(e.getKey())).findFirst().orElse(null);
            Matrix4f m = bones.get(e.getKey());
            if (b == null || m == null) continue;
            Matrix4f w = new Matrix4f(toWorld).mul(m);
            float minX = 1e9f, minY = 1e9f, minZ = 1e9f, maxX = -1e9f, maxY = -1e9f, maxZ = -1e9f;
            for (float[] x : b.boxes())
                for (int c = 0; c < 8; c++) {
                    Vector3f p = w.transformPosition(new Vector3f((x[0] + ((c & 1) != 0 ? x[3] : 0)) / 16f, (x[1] + ((c & 2) != 0 ? x[4] : 0)) / 16f, (x[2] + ((c & 4) != 0 ? x[5] : 0)) / 16f));
                    minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                    minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
                    minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
                }
            Interaction i = e.getValue();
            i.setInteractionWidth(Math.max(0.3f, Math.min(4f, Math.max(maxX - minX, maxZ - minZ))));
            i.setInteractionHeight(Math.max(0.3f, Math.min(4f, maxY - minY)));
            i.teleport(loc.clone().add((minX + maxX) / 2, minY, (minZ + maxZ) / 2));
        }
    }

    /** Wygląd części dla wariantu (przedmiot z modelem wariantu, świecenie). */
    private void applyVariant() {
        if (variant.equals(shownVariant)) return;
        shownVariant = variant;
        MobDef.Variant v = def.variants().get(variant);
        for (MobDef.Bone b : def.bones()) {
            ItemDisplay d = parts.get(b.id());
            if (d == null) continue;
            String item = b.variantItems().getOrDefault(variant, b.item());
            ItemStack stack = new ItemStack(Material.PAPER);
            String[] key = item.split(":", 2);
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(key[0], key[1]));
            d.setItemStack(stack);
            d.setBrightness(b.glow() && (v == null || !v.noGlow()) ? new Display.Brightness(15, 15) : null);
        }
    }

    /** Losowe zachowania i faza 2 (config.yml) - jednorazowe animacje bez umiejętności. */
    private void tickMood() {
        if (cfg == null) return;
        ConfigurationSection p2 = cfg.getConfigurationSection("faza-2");
        if (p2 != null && !phase2) {
            AttributeInstance max = base.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && base.getHealth() / max.getValue() < p2.getDouble("ponizej-zycia", 0.5)) {
                phase2 = true;
                damageMultiplier = p2.getDouble("obrazenia-mnoznik", 1.5);
                AttributeInstance dmg = base.getAttribute(Attribute.ATTACK_DAMAGE);
                if (dmg != null) dmg.setBaseValue(dmg.getBaseValue() * damageMultiplier);
                MobDef.Anim a = find(p2.getString("animacja"));
                if (a != null) {
                    oneShot = a;
                    oneShotStart = ticks;
                    base.setAI(false);
                }
                if (p2.contains("wariant")) variant = p2.getString("wariant");
                sound(base.getLocation(), p2.getString("dzwiek", "entity.lightning_bolt.thunder"));
            }
        }
        List<String> randoms = cfg.getStringList("animacje-losowe");
        if (oneShot == null && action == null && !walking && !randoms.isEmpty() && ticks >= nextRandom && base.getTarget() == null) {
            MobDef.Anim a = find(randoms.get(ThreadLocalRandom.current().nextInt(randoms.size())));
            if (a != null) {
                oneShot = a;
                oneShotStart = ticks;
            }
            nextRandom = ticks + (int) (cfg.getDouble("co-ile-sekund-losowe", 14) * 20 * (0.6 + ThreadLocalRandom.current().nextDouble() * 0.8));
        }
        if (oneShot != null && (ticks - oneShotStart) / 20f >= oneShot.length()) {
            oneShot = null;
            if (!base.hasAI() && action == null) base.setAI(true);
        }
    }

    void tick() {
        ticks++;
        if (deathStart >= 0) {
            tickDeath();
            return;
        }
        tickMood();
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
        walking = action == null && oneShot == null && net > (walking ? 0.012f : 0.03f);
        if (action != null) walkWeight = 0;
        walkWeight = Math.max(0, Math.min(1, walkWeight + (walking ? 0.12f : -0.1f)));
        MobDef.Anim walk = find("walk", "move"), idle = find("idle"), run = find("run");
        // bieg: szybki marsz przechodzi płynnie w animację biegu
        runWeight = Math.max(0, Math.min(1, runWeight + (walking && run != null && net > 0.16f ? 0.1f : -0.1f)));
        if (walking) walkTime += 0.05f * Math.max(0.5f, Math.min(2f, net / (runWeight > 0.5f ? 0.2f : 0.08f)));
        if (walk != null) playing.add(new MobPose.Playing(walk, walkTime % Math.max(0.05f, walk.length()), walkWeight * (1 - runWeight)));
        if (run != null && runWeight > 0) playing.add(new MobPose.Playing(run, walkTime % Math.max(0.05f, run.length()), walkWeight * runWeight));
        // W trakcie umiejętności spoczynek nie gra - ruchy (np. machanie rękami) gryzłyby się z nią.
        if (idle != null && action == null) playing.add(new MobPose.Playing(idle, sec % Math.max(0.05f, idle.length()), (1 - walkWeight) * (oneShot != null ? 0.2f : 1f)));
        for (MobDef.Anim a : def.animations().values()) {
            if (!special(a.name()) && a.loop()) playing.add(new MobPose.Playing(a, sec % Math.max(0.05f, a.length()), 1));
        }
        MobDef.Anim current = null;
        float currentT = 0;
        if (act != null && act[0] instanceof MobDef.Anim a) {
            playing.add(new MobPose.Playing(a, (Float) act[1], 1));
            current = a;
            currentT = (Float) act[1];
        }
        if (oneShot != null) {
            float t = (ticks - oneShotStart) / 20f;
            playing.add(new MobPose.Playing(oneShot, t, 1));
            current = oneShot;
            currentT = t;
        }
        MobDef.Anim attack = find("attack");
        if (attack != null && (ticks - attackStart) / 20f < attack.length()) {
            playing.add(new MobPose.Playing(attack, (ticks - attackStart) / 20f, 1));
            if (current == null) {
                current = attack;
                currentT = (ticks - attackStart) / 20f;
            }
        }
        if (current != null) {
            String v = MobPose.variantAt(current, currentT);
            if (v != null) variant = v;
        }
        applyVariant();

        // Pierwsze 3 ticki części są niewidoczne i bez wygładzania; potem pokazują się od razu na miejscu.
        if (ticks == 3) parts.values().forEach(d -> {
            d.setInterpolationDuration(3);
            d.setViewRange(2f);
        });
        float body = base.getBodyYaw();
        if (Float.isNaN(modelYaw)) modelYaw = body;
        float diff = ((body - modelYaw) % 360 + 540) % 360 - 180;
        if (Math.abs(diff) > 3) modelYaw += diff * (walking || action != null ? 0.5f : 0.3f);
        float yaw = modelYaw;

        // Ruch żywy: głowa do celu, stopy na terenie, sprężyny (ogon, uszy, peleryna) z prawdziwego ruchu.
        Map<String, MobPose.Offset> offsets = MobPose.offsets(playing);
        Map<String, Matrix4f> animated = MobPose.boneMatrices(def, offsets);
        Entity lookAt = base.getTarget();
        if (lookAt == null) {
            Player near = null;
            double best = 144;
            for (Player p : loc.getNearbyPlayers(12)) {
                double d = p.getLocation().distanceSquared(loc);
                if (d < best && !p.isDead()) {
                    best = d;
                    near = p;
                }
            }
            lookAt = near;
        }
        Vector3f target = null;
        if (lookAt instanceof org.bukkit.entity.LivingEntity le) {
            Location eye = le.getEyeLocation();
            target = new Vector3f((float) (eye.getX() - loc.getX()), (float) (eye.getY() - loc.getY()), (float) (eye.getZ() - loc.getZ()));
        }
        rig.applyLook(offsets, animated, yaw, target);
        var world = loc.getWorld();
        rig.applyFeet(offsets, animated, yaw, loc.getX(), loc.getY(), loc.getZ(), base.isOnGround() && action == null, (x, yFrom, z) -> {
            var hit = world.rayTraceBlocks(new Location(world, x, yFrom, z), new Vector(0, -1, 0), 3.0, FluidCollisionMode.NEVER, true);
            return hit == null ? Double.NaN : hit.getHitPosition().getY();
        });
        Matrix4f toWorld = new Matrix4f().translate((float) (loc.getX() - origin.getX()), (float) (loc.getY() - origin.getY()), (float) (loc.getZ() - origin.getZ()))
                .mul(MobRig.modelToWorld(yaw));
        rig.applySprings(offsets, animated, toWorld);
        Map<String, Matrix4f> bones = MobPose.boneMatrices(def, offsets);
        lastBones = bones;
        moveHitboxes(loc, yaw, bones);
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
        effects(loc, yaw, bones, current, currentT, walk, idle, sec);
    }

    /** Cząsteczki: tylko w swojej animacji i oknie czasu, w swoim wariancie; lot w kierunku części. */
    private void effects(Location loc, float yaw, Map<String, Matrix4f> bones, MobDef.Anim current, float currentT, MobDef.Anim walk, MobDef.Anim idle, float sec) {
        MobDef.Anim playingAnim = current != null ? current : walking ? walk : idle;
        float t = current != null ? currentT : playingAnim == null ? 0 : (walking ? walkTime : sec) % Math.max(0.05f, playingAnim.length());
        for (Map.Entry<MobDef.Effect, Particle> e : particles.entrySet()) {
            MobDef.Effect f = e.getKey();
            if (f.anim() != null && (playingAnim == null || !(f.anim().equals(playingAnim.id()) || f.anim().equals(playingAnim.name())))) continue;
            if (f.anim() != null && f.from() >= 0 && t < f.from()) continue;
            if (f.anim() != null && f.to() >= 0 && t > f.to()) continue;
            if (f.variant() != null && !f.variant().equals(variant)) continue;
            float chance = f.rate() / 20f;
            int count = (int) chance + (ThreadLocalRandom.current().nextFloat() < chance - (int) chance ? 1 : 0);
            if (count == 0) continue;
            Matrix4f at = f.bone() != null && bones.containsKey(f.bone()) ? new Matrix4f(bones.get(f.bone())) : new Matrix4f();
            at.translate(f.offset()[0] / 16f, f.offset()[1] / 16f, f.offset()[2] / 16f);
            Matrix4f disp = MobPose.displayMatrix(yaw, at, 1);
            Vector3f p = disp.getTranslation(new Vector3f());
            double s = f.spread() / 16.0;
            Vector3f v = new Vector3f();
            if (f.velocity() != null) {
                // kierunek lotu w układzie części -> świat (bez końcowego obrotu przedmiotu i skali)
                Matrix4f dirM = new Matrix4f().rotateY((float) Math.toRadians(180 - yaw)).scale(-1, -1, 1).mul(at);
                dirM.transformDirection(new Vector3f(f.velocity()[0], f.velocity()[1], f.velocity()[2]), v);
                v.div(16f * 20f);
            }
            v.y += f.rise() / (16f * 20f);
            for (int i = 0; i < Math.min(count, 8); i++) {
                Location pl = loc.clone().add(p.x + (ThreadLocalRandom.current().nextDouble() - 0.5) * 2 * s, p.y + (ThreadLocalRandom.current().nextDouble() - 0.5) * 2 * s,
                        p.z + (ThreadLocalRandom.current().nextDouble() - 0.5) * 2 * s);
                if (v.lengthSquared() > 1e-8) base.getWorld().spawnParticle(e.getValue(), pl, 0, v.x, v.y, v.z, 1);
                else base.getWorld().spawnParticle(e.getValue(), pl, 1, 0, 0, 0, 0);
            }
        }
    }

    // ---- śmierć: animacja śmierci, potem rozpad na części ----

    /** Mob zginął: części zostają, gra animacja "death" (jeśli jest), potem rozpadają się z fizyką. */
    void startDeath() {
        if (deathStart >= 0) return;
        deathStart = ticks;
        deathLoc = base.getLocation().clone();
        hitboxes.values().forEach(Interaction::remove);
        hitboxes.clear();
        hitParts.clear();
        birds.forEach(b -> b.entity.remove());
        birds.clear();
        if (cfg != null && !cfg.getBoolean("rozpad", true)) {
            MobDef.Anim d = find("death");
            if (d == null) finished = true;
        }
    }

    private void tickDeath() {
        MobDef.Anim death = find("death");
        float t = (ticks - deathStart) / 20f;
        float len = death == null ? 0 : Math.min(4f, death.length());
        boolean shatter = cfg == null || cfg.getBoolean("rozpad", true);
        if (t < len) {
            // poza z animacji śmierci (bez chodu, bez patrzenia)
            Map<String, Matrix4f> bones = MobPose.boneMatrices(def, MobPose.offsets(List.of(new MobPose.Playing(death, t, 1))));
            lastBones = bones;
            float yaw = Float.isNaN(modelYaw) ? 0 : modelYaw;
            for (MobDef.Bone b : def.bones()) {
                ItemDisplay d = parts.get(b.id());
                if (d == null) continue;
                Matrix4f m = MobPose.displayMatrix(yaw, bones.get(b.id()), b.modelScale());
                Transformation tr = new Transformation(m.getTranslation(new Vector3f()), m.getNormalizedRotation(new Quaternionf()), m.getScale(new Vector3f()), new Quaternionf());
                if (tr.equals(lastSent.put(b.id(), tr))) continue;
                d.setInterpolationDelay(0);
                d.setTransformation(tr);
            }
            return;
        }
        if (!shatter) {
            finished = true;
            return;
        }
        int k = (int) ((t - len) * 20);
        if (k == 0) {
            // start rozpadu: każda część dostaje prędkość od środka moba, w górę, i obrót
            deathLoc.getWorld().spawnParticle(Particle.POOF, deathLoc.clone().add(0, def.hitboxHeight() / 2, 0), 25, def.hitboxWidth() / 2, def.hitboxHeight() / 3, def.hitboxWidth() / 2, 0.03);
            for (Map.Entry<String, ItemDisplay> e : parts.entrySet()) {
                Transformation tr = lastSent.get(e.getKey());
                if (tr == null) continue;
                Vector3f p = new Vector3f(tr.getTranslation());
                Vector3f out = new Vector3f(p.x, 0, p.z);
                if (out.lengthSquared() < 1e-4f) out.set(ThreadLocalRandom.current().nextFloat() - 0.5f, 0, ThreadLocalRandom.current().nextFloat() - 0.5f);
                out.normalize().mul(0.08f + ThreadLocalRandom.current().nextFloat() * 0.12f);
                float[] st = {p.x, p.y, p.z, out.x, 0.18f + ThreadLocalRandom.current().nextFloat() * 0.2f, out.z,
                        ThreadLocalRandom.current().nextFloat() * 2 - 1, ThreadLocalRandom.current().nextFloat() * 2 - 1, ThreadLocalRandom.current().nextFloat() * 2 - 1,
                        6 + ThreadLocalRandom.current().nextFloat() * 14, 0};
                shards.put(e.getKey(), st);
            }
        }
        for (Map.Entry<String, ItemDisplay> e : parts.entrySet()) {
            float[] st = shards.get(e.getKey());
            Transformation tr0 = lastSent.get(e.getKey());
            if (st == null || tr0 == null) continue;
            // ruch: grawitacja, odbicie od ziemi (blok pod częścią), obrót słabnie po uderzeniu
            st[4] -= 0.045f;
            float nx = st[0] + st[3], ny = st[1] + st[4], nz = st[2] + st[5];
            Location w = deathLoc.clone().add(nx, ny, nz);
            if (w.getBlock().getType().isSolid() && st[4] < 0) {
                ny = (float) (Math.floor(w.getY()) + 1 - deathLoc.getY()) + 0.02f;
                st[4] = -st[4] * 0.3f;
                st[3] *= 0.5f;
                st[5] *= 0.5f;
                st[9] *= 0.4f;
            }
            st[0] = nx;
            st[1] = ny;
            st[2] = nz;
            st[10] += st[9];
            Quaternionf spin = new Quaternionf().rotateAxis((float) Math.toRadians(st[10]), new Vector3f(st[6], st[7], st[8]).normalize());
            float fade = k < 30 ? 1 : Math.max(0, 1 - (k - 30) / 10f);
            Transformation tr = new Transformation(new Vector3f(nx, ny, nz), spin.mul(new Quaternionf(tr0.getLeftRotation())), new Vector3f(tr0.getScale()).mul(fade), new Quaternionf());
            ItemDisplay d = e.getValue();
            d.setInterpolationDuration(1);
            d.setInterpolationDelay(0);
            d.setTransformation(tr);
        }
        if (k >= 41) finished = true;
    }
}
