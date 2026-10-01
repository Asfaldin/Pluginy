package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobBehavior.Action;
import elo.mainplugins.mobs.model.MobBehavior.Params;
import elo.mainplugins.mobs.model.MobBehavior.Skill;
import elo.mainplugins.mobs.model.MobDef;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.LlamaSpit;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WindCharge;
import org.bukkit.entity.WitherSkull;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Umiejętności moba (MobBehavior.Skill): sprawdza wyzwalacze i warunki, prowadzi akcje w czasie.
 *
 * Umiejętność z animacją, skokiem albo oszołomieniem jest "główna": mob stoi w miejscu (bez AI),
 * gra jej animację i nic innego głównego nie rusza w tym czasie. Pozostałe (np. trucizna przy
 * trafieniu, leczenie co 10 s) idą w tle, obok chodzenia i walki.
 */
final class SkillRunner {

    /** Trwająca umiejętność. */
    private static final class Run {
        final Skill skill;
        final int start;
        final LivingEntity target;
        int next;
        /** Skok: od, do, start (tick), długość (ticki), wysokość łuku. */
        Location leapFrom, leapTo;
        int leapStart = -1, leapTicks;
        double leapHeight;
        /** Oszołomienie po umiejętności: animacja, start, długość (ticki). */
        MobDef.Anim stunAnim;
        int stunStart = -1, stunTicks;
        /** Koniec części z akcjami (tick) - potem ewentualne oszołomienie. */
        int end;

        Run(Skill skill, int start, LivingEntity target) {
            this.skill = skill;
            this.start = start;
            this.target = target;
        }
    }

    private final LiveMob mob;
    private final List<Skill> skills;
    private final Map<Skill, Integer> ready = new HashMap<>();
    private Run main;
    private final List<Run> background = new ArrayList<>();

    SkillRunner(LiveMob mob, List<Skill> skills) {
        this.mob = mob;
        this.skills = skills;
        // Pierwsze użycie po kilku sekundach, każda umiejętność w innej chwili.
        for (Skill s : skills) ready.put(s, 60 + ThreadLocalRandom.current().nextInt(100));
    }

    boolean busy() {
        return main != null;
    }

    static boolean exclusive(Skill s) {
        if (s.animation() != null) return true;
        for (Action a : s.actions()) if (a.type().equals("leap") || a.type().equals("stun")) return true;
        return false;
    }

    /** Animacja głównej umiejętności i chwila w niej (do pozy moba) albo null. */
    Object[] tick(int ticks, LivingEntity target, boolean canStartMain) {
        if (main == null && ticks > 40) {
            tryTrigger("combat", target, canStartMain);
            tryTrigger("timer", target, canStartMain);
        }
        for (Iterator<Run> it = background.iterator(); it.hasNext(); ) {
            Run r = it.next();
            advance(r, ticks);
            if (r.next >= r.skill.actions().size() && r.leapStart < 0) it.remove();
        }
        if (main == null) return null;
        return tickMain(ticks);
    }

    /** Zdarzenie (hit, hurt, spawn, death, phase) - uruchamia pasujące umiejętności. */
    void trigger(String trigger, LivingEntity target, int phase) {
        for (Skill s : skills) {
            if (!s.trigger().equals(trigger)) continue;
            if (trigger.equals("phase") && s.phase() != phase) continue;
            if (!conditionsOk(s, target, !trigger.equals("death"))) continue;
            if (trigger.equals("death")) {
                // Mob już ginie - wszystkie akcje od razu, w miejscu śmierci.
                Run r = new Run(s, mob.ticks(), target);
                for (Action a : s.actions()) if (!a.type().equals("leap") && !a.type().equals("stun")) exec(r, a);
                continue;
            }
            start(s, target);
        }
    }

    private void tryTrigger(String trigger, LivingEntity target, boolean canStartMain) {
        for (Skill s : skills) {
            if (!s.trigger().equals(trigger)) continue;
            if (trigger.equals("combat") && target == null) continue;
            if (exclusive(s) && !canStartMain) continue;
            if (!conditionsOk(s, target, true)) continue;
            start(s, target);
            if (main != null) return;
        }
    }

    private boolean conditionsOk(Skill s, LivingEntity target, boolean checkCooldown) {
        int ticks = mob.ticks();
        if (checkCooldown && ticks < ready.getOrDefault(s, 0)) return false;
        if (!s.trigger().equals("phase") && s.phase() > mob.phaseIndex()) return false;
        if (mob.healthFraction() > s.healthBelow() + 1e-9) return false;
        if (target != null && (s.trigger().equals("combat") || s.rangeMax() < 256)) {
            if (target.getWorld() != mob.base.getWorld()) return false;
            double d = target.getLocation().distance(mob.base.getLocation());
            if (s.trigger().equals("combat") && (d < s.rangeMin() || d > s.rangeMax())) return false;
        }
        if (s.chance() < 1 && ThreadLocalRandom.current().nextDouble() > s.chance()) {
            // Nieudany rzut też odczekuje - inaczej "szansa" byłaby losowana co tick.
            ready.put(s, ticks + (int) (Math.max(1, s.cooldown()) * 20));
            return false;
        }
        return true;
    }

    private void start(Skill s, LivingEntity target) {
        int ticks = mob.ticks();
        ready.put(s, ticks + (int) (s.cooldown() * 20));
        Run r = new Run(s, ticks, target);
        double end = 0;
        MobDef.Anim anim = mob.find(s.animation());
        if (anim != null) end = anim.length();
        for (Action a : s.actions()) end = Math.max(end, a.at() + (a.type().equals("leap") ? a.p().num("duration", 1) : 0));
        r.end = ticks + (int) Math.ceil(end * 20);
        if (exclusive(s)) {
            if (main != null) return;
            main = r;
            if (target != null) mob.face(target.getLocation());
        } else {
            background.add(r);
        }
        advance(r, ticks);
    }

    /** Akcje, których czas już minął; ruch skoku. */
    private void advance(Run r, int ticks) {
        double t = (ticks - r.start) / 20.0;
        List<Action> actions = r.skill.actions();
        while (r.next < actions.size() && actions.get(r.next).at() <= t + 1e-6) exec(r, actions.get(r.next++));
        if (r.leapStart >= 0) {
            float k = Math.min(1f, (ticks - r.leapStart) / (float) Math.max(1, r.leapTicks));
            Location at = r.leapFrom.clone().add(r.leapTo.toVector().subtract(r.leapFrom.toVector()).multiply(k));
            at.add(0, r.leapHeight * 4 * k * (1 - k), 0);
            at.setDirection(r.leapTo.getDirection());
            mob.base.teleport(at);
            mob.base.setBodyYaw(at.getYaw());
            if (k >= 1) r.leapStart = -1;
        }
    }

    private Object[] tickMain(int ticks) {
        Run r = main;
        if (r.stunStart >= 0) {
            float st = (ticks - r.stunStart) / 20f;
            if (ticks - r.stunStart >= r.stunTicks) {
                main = null;
                return null;
            }
            return r.stunAnim == null ? new Object[]{null, 0f} : new Object[]{r.stunAnim, Math.min(st, r.stunAnim.length())};
        }
        advance(r, ticks);
        if (r.leapStart < 0 && r.target != null && r.target.isValid() && r.next < r.skill.actions().size()) mob.face(r.target.getLocation());
        MobDef.Anim anim = mob.find(r.skill.animation());
        float t = (ticks - r.start) / 20f;
        if (ticks >= r.end && r.next >= r.skill.actions().size() && r.leapStart < 0) {
            if (r.stunTicks > 0) {
                r.stunStart = ticks;
                return r.stunAnim == null ? new Object[]{null, 0f} : new Object[]{r.stunAnim, 0f};
            }
            main = null;
            return null;
        }
        // Po końcu animacji (np. w locie) trzyma się jej ostatnia poza.
        return anim == null ? new Object[]{null, 0f} : new Object[]{anim, Math.min(t, anim.length())};
    }

    void cancel() {
        main = null;
        background.clear();
    }

    /** Akcje od razu (kliknięcie NPC): z animacją jako główna (NPC stoi i mówi), bez - w tle. */
    void runNow(String name, List<Action> actions, LivingEntity target, String animation) {
        Skill s = new Skill(name, "click", 0, 0, 256, 1, 1, 0, animation, actions);
        if (animation != null && main != null) main = null;
        start(s, target);
    }

    // ---- akcje ----

    private void exec(Run r, Action a) {
        Params p = a.p();
        LivingEntity target = r.target != null && r.target.isValid() && !r.target.isDead() ? r.target : null;
        Mob base = mob.base;
        World w = base.getWorld();
        try {
            switch (a.type()) {
                case "leap" -> {
                    if (target == null) return;
                    Location now = base.getLocation();
                    Vector dir = target.getLocation().toVector().subtract(now.toVector()).setY(0);
                    if (dir.lengthSquared() < 0.01) dir = now.getDirection().setY(0);
                    if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
                    dir.normalize();
                    r.leapFrom = now.clone();
                    r.leapTo = target.getLocation().clone().subtract(dir.clone().multiply(p.num("stopBefore", 1.2)));
                    r.leapFrom.setDirection(dir);
                    r.leapTo.setDirection(dir);
                    r.leapTicks = Math.max(1, (int) Math.round(p.num("duration", 1) * 20));
                    r.leapHeight = p.num("height", 0);
                    r.leapStart = mob.ticks();
                }
                case "damage" -> {
                    double amount = p.num("amount", 4), radius = p.num("radius", 0);
                    if (radius <= 0) {
                        if (target != null) hit(target, amount, base.getLocation(), p.num("knockback", 0.4), p.num("up", 0.3));
                    } else {
                        Location c = p.bool("atTarget", false) && target != null ? target.getLocation() : base.getLocation();
                        for (Player pl : c.getNearbyPlayers(radius)) hit(pl, amount, c, p.num("knockback", 0.8), p.num("up", 0.45));
                    }
                }
                case "impact" -> mob.dust(base.getLocation(), p.num("size", 2.5));
                case "projectile" -> {
                    if (target != null) shoot(p, target);
                }
                case "summon" -> summon(p, target);
                case "swarm" -> {
                    if (target != null) mob.releaseSwarm(p, target);
                }
                case "potion" -> {
                    PotionEffectType type = effect(p.str("effect", "slowness"));
                    if (type == null) return;
                    PotionEffect e = new PotionEffect(type, (int) (p.num("seconds", 5) * 20), Math.max(0, (int) p.num("level", 1) - 1));
                    for (LivingEntity le : targets(p, target, "target")) le.addPotionEffect(e);
                }
                case "particles" -> {
                    Particle particle = particle(p.str("particle", "flame"));
                    if (particle == null) return;
                    Location at = p.bool("atTarget", false) && target != null ? target.getLocation().add(0, target.getHeight() / 2, 0) : mob.bonePos(blank(p.str("bone", null)));
                    double s = p.num("spread", 0.5);
                    w.spawnParticle(particle, at, (int) Math.min(500, p.num("count", 20)), s, s, s, p.num("speed", 0.05));
                }
                case "sound" -> mob.sound(mob.bonePos(blank(p.str("bone", null))), p.str("sound", ""), (float) p.num("volume", 2), (float) p.num("pitch", 0.8));
                case "heal" -> {
                    // target: self (domyślnie) albo allies - inne moby z paczki w promieniu (uzdrowiciel)
                    List<LivingEntity> who = new ArrayList<>();
                    if (p.str("target", "self").equals("allies")) {
                        for (LiveMob m : mob.allies(p.num("radius", 8))) who.add(m.base);
                    } else {
                        who.add(base);
                    }
                    for (LivingEntity le : who) {
                        var maxAttr = le.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                        double max = maxAttr != null ? maxAttr.getValue() : 20;
                        double amount = p.bool("percent", false) ? max * p.num("amount", 10) / 100.0 : p.num("amount", 10);
                        le.setHealth(Math.min(max, le.getHealth() + amount));
                        w.spawnParticle(Particle.HEART, le.getLocation().add(0, le.getHeight(), 0), 6, 0.5, 0.3, 0.5, 0);
                    }
                }
                case "teleport" -> teleport(p, target);
                case "pull" -> {
                    Location c = base.getLocation();
                    for (Player pl : c.getNearbyPlayers(p.num("radius", 8))) {
                        Vector v = c.toVector().subtract(pl.getLocation().toVector());
                        if (v.lengthSquared() < 0.01) continue;
                        pl.setVelocity(v.normalize().multiply(p.num("strength", 1)).setY(0.3));
                    }
                }
                case "lightning" -> {
                    Location at = target != null && p.bool("atTarget", true) ? target.getLocation() : base.getLocation();
                    w.strikeLightningEffect(at);
                    double dmg = p.num("damage", 6);
                    if (dmg > 0) for (Player pl : at.getNearbyPlayers(2.5)) pl.damage(dmg, base);
                }
                case "ignite" -> {
                    int fire = (int) (p.num("seconds", 4) * 20);
                    for (LivingEntity le : targets(p, target, "target")) le.setFireTicks(Math.max(le.getFireTicks(), fire));
                }
                case "explode" -> w.createExplosion(base.getLocation(), (float) Math.min(10, p.num("power", 2)), p.bool("fire", false), p.bool("breakBlocks", false), base);
                case "die" -> mob.dieSilently();
                case "shield" -> mob.shield((int) (p.num("seconds", 3) * 20));
                case "variant" -> mob.setVariant(p.str("variant", "base"));
                case "stun" -> {
                    r.stunAnim = mob.find(blank(p.str("animation", null)));
                    double sec = p.num("seconds", 0);
                    if (sec <= 0) sec = r.stunAnim != null ? r.stunAnim.length() : 2;
                    r.stunTicks = Math.max(1, (int) (sec * 20));
                }
                case "message" -> {
                    String text = p.str("text", "").replace("{mob}", mob.displayName()).replace("{player}", target != null ? target.getName() : "");
                    var msg = LegacyComponentSerializer.legacyAmpersand().deserialize(text);
                    for (Player pl : base.getLocation().getNearbyPlayers(p.num("radius", 32))) pl.sendMessage(msg);
                }
                case "open_shop" -> {
                    if (target instanceof Player pl) pl.performCommand(("sklep " + p.str("category", "")).trim());
                }
                case "open_quests" -> {
                    if (target instanceof Player pl) pl.performCommand("zadania");
                }
                case "player_command" -> {
                    String cmd = p.str("command", "").replace("{player}", target != null ? target.getName() : "");
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    if (target instanceof Player pl && !cmd.isBlank()) pl.performCommand(cmd);
                }
                case "reward" -> {
                    if (!(target instanceof Player pl)) return;
                    // Nagroda z Core: money, item, custom, crate, key, unlock... (jak w skrzynkach i questach)
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    Object value = p.values().get("value");
                    entry.put(p.str("reward", "money"), value instanceof Double d && d == Math.floor(d) ? (Object) d.intValue() : value);
                    entry.put("amount", (int) Math.max(1, p.num("amount", 1)));
                    var rewards = elo.mainplugins.core.CoreAPI.getRewardService();
                    rewards.give(pl, rewards.parse(List.of(entry), "mob " + mob.id()));
                }
                case "teleport_player" -> {
                    if (!(target instanceof Player pl)) return;
                    World tw = Bukkit.getWorld(p.str("world", pl.getWorld().getName()));
                    if (tw == null) return;
                    pl.teleport(new Location(tw, p.num("x", 0), p.num("y", 64), p.num("z", 0), pl.getLocation().getYaw(), pl.getLocation().getPitch()));
                }
                case "command" -> {
                    Location l = base.getLocation();
                    String cmd = p.str("command", "").replace("{player}", target != null ? target.getName() : "").replace("{mob}", mob.id())
                            .replace("{x}", String.valueOf(l.getBlockX())).replace("{y}", String.valueOf(l.getBlockY())).replace("{z}", String.valueOf(l.getBlockZ()))
                            .replace("{world}", l.getWorld().getName());
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    if (!cmd.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
                default -> { /* nieznany typ (nowsza aplikacja) - pomijamy */ }
            }
        } catch (RuntimeException e) {
            mob.warn("Umiejętność " + r.skill.name() + ", akcja " + a.type() + ": " + e.getMessage());
        }
    }

    private void hit(LivingEntity le, double amount, Location from, double knockback, double up) {
        le.damage(amount * mob.damageMultiplier(), mob.base);
        Vector away = le.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 1);
        if (knockback > 0 || up > 0) le.setVelocity(away.normalize().multiply(knockback).setY(up));
    }

    private List<LivingEntity> targets(Params p, LivingEntity target, String def) {
        List<LivingEntity> out = new ArrayList<>();
        switch (p.str("target", def)) {
            case "self" -> out.add(mob.base);
            case "allies" -> {
                for (LiveMob m : mob.allies(p.num("radius", 8))) out.add(m.base);
            }
            case "area" -> out.addAll(mob.base.getLocation().getNearbyPlayers(p.num("radius", 5)));
            default -> {
                if (target != null) out.add(target);
            }
        }
        return out;
    }

    /** Pocisk (albo salwa) w cel - obrażenia zapisane na pocisku, MobListener ustawia je przy trafieniu. */
    void shoot(Params p, LivingEntity target) {
        Mob base = mob.base;
        Location from = mob.shootOrigin(blank(p.str("bone", null)));
        int count = (int) Math.max(1, Math.min(50, p.num("count", 1)));
        double speed = p.num("speed", 1.6), spread = p.num("spread", 4), damage = p.num("damage", 4) * mob.damageMultiplier();
        String kind = p.str("kind", "arrow").toLowerCase(Locale.ROOT);
        Vector aim = target.getEyeLocation().toVector().subtract(from.toVector());
        // Lekko w górę na dalszy dystans - strzała opada.
        if (kind.contains("arrow") || kind.equals("trident") || kind.equals("snowball") || kind.equals("egg")) aim.setY(aim.getY() + aim.length() * 0.08);
        for (int i = 0; i < count; i++) {
            Vector v = aim.clone().normalize();
            if (spread > 0) v.add(new Vector(rnd() * spread * 0.0175, rnd() * spread * 0.0175, rnd() * spread * 0.0175)).normalize();
            Projectile proj = switch (kind) {
                case "arrow" -> {
                    Arrow a = base.getWorld().spawnArrow(from, v, (float) speed, 0f);
                    a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    a.setLifetimeTicks(1100);
                    yield a;
                }
                case "spectral_arrow" -> spawn(from, SpectralArrow.class);
                case "trident" -> spawn(from, Trident.class);
                case "snowball" -> spawn(from, Snowball.class);
                case "egg" -> spawn(from, Egg.class);
                case "fireball" -> spawn(from, Fireball.class);
                case "small_fireball" -> spawn(from, SmallFireball.class);
                case "dragon_fireball" -> spawn(from, DragonFireball.class);
                case "wither_skull" -> spawn(from, WitherSkull.class);
                case "shulker_bullet" -> spawn(from, ShulkerBullet.class);
                case "llama_spit" -> spawn(from, LlamaSpit.class);
                case "wind_charge" -> spawn(from, WindCharge.class);
                default -> null;
            };
            if (proj == null) return;
            proj.setShooter(base);
            if (proj instanceof AbstractArrow aa) {
                aa.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                aa.setDamage(Math.max(0, damage / Math.max(0.1, speed)));
            }
            if (proj instanceof Fireball fb) {
                fb.setYield((float) Math.max(0, Math.min(6, p.num("explosion", 0))));
                fb.setIsIncendiary(p.bool("fire", false));
                fb.setDirection(v.clone().multiply(0.1));
            }
            if (proj instanceof ShulkerBullet sb) sb.setTarget(target);
            if (!(proj instanceof Arrow)) proj.setVelocity(v.multiply(speed));
            proj.getPersistentDataContainer().set(mob.damageKey(), PersistentDataType.DOUBLE, damage);
        }
    }

    private <T extends Entity> T spawn(Location from, Class<T> type) {
        return mob.base.getWorld().spawn(from, type);
    }

    private void summon(Params p, LivingEntity target) {
        String what = p.str("mob", "zombie").toLowerCase(Locale.ROOT);
        if (what.equals("self")) what = mob.id().toLowerCase(Locale.ROOT); // rój: kopie samego siebie
        int count = (int) Math.max(1, Math.min(20, p.num("count", 1)));
        double radius = p.num("radius", 2);
        int life = (int) (p.num("lifetime", 0) * 20);
        for (int i = 0; i < count; i++) {
            Location at = ground(mob.base.getLocation().add(rnd() * radius, 0, rnd() * radius), 4);
            if (at == null) at = mob.base.getLocation();
            Entity spawned = mob.summonCustom(what, at, target, life);
            if (spawned == null) {
                NamespacedKey k = NamespacedKey.fromString(what.contains(":") ? what : "minecraft:" + what);
                EntityType type = k == null ? null : Registry.ENTITY_TYPE.get(k);
                if (type == null || !type.isSpawnable() || !type.isAlive()) return;
                spawned = at.getWorld().spawnEntity(at, type);
                if (spawned instanceof Mob m && target != null) m.setTarget(target);
                if (life > 0) mob.expireLater(spawned, life);
            }
            at.getWorld().spawnParticle(Particle.POOF, at.clone().add(0, 0.5, 0), 8, 0.3, 0.3, 0.3, 0.02);
        }
    }

    private void teleport(Params p, LivingEntity target) {
        Mob base = mob.base;
        Location to = null;
        switch (p.str("to", "target")) {
            case "behind" -> {
                if (target != null) to = target.getLocation().subtract(target.getLocation().getDirection().setY(0).normalize().multiply(1.8));
            }
            case "random" -> {
                double radius = p.num("radius", 8);
                for (int i = 0; i < 12 && to == null; i++) to = ground(base.getLocation().add(rnd() * radius, 0, rnd() * radius), (int) radius);
            }
            default -> {
                if (target != null) to = target.getLocation();
            }
        }
        if (to == null) return;
        Location safe = ground(to, 4);
        if (safe == null) return;
        Location from = base.getLocation();
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.3);
        safe.setDirection(target != null ? target.getLocation().toVector().subtract(safe.toVector()) : from.getDirection());
        base.teleport(safe);
        mob.sound(safe, "entity.enderman.teleport", 1.5f, 1f);
        safe.getWorld().spawnParticle(Particle.PORTAL, safe.clone().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.3);
    }

    /** Stałe podłoże z miejscem na moba w pionie ±range od punktu (null = brak). */
    static Location ground(Location at, int range) {
        World w = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ(), y0 = at.getBlockY();
        for (int dy = 0; dy <= range * 2; dy++) {
            int y = y0 + (dy % 2 == 0 ? dy / 2 : -(dy + 1) / 2);
            if (y <= w.getMinHeight() || y >= w.getMaxHeight() - 2) continue;
            Block below = w.getBlockAt(x, y - 1, z);
            if (below.getType().isSolid() && w.getBlockAt(x, y, z).isPassable() && w.getBlockAt(x, y + 1, z).isPassable()
                    && w.getBlockAt(x, y, z).getType() != Material.LAVA) {
                return new Location(w, x + 0.5, y, z + 0.5, at.getYaw(), 0);
            }
        }
        return null;
    }

    private static PotionEffectType effect(String id) {
        NamespacedKey k = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
        return k == null ? null : Registry.EFFECT.get(k);
    }

    static Particle particle(String id) {
        NamespacedKey k = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
        Particle p = k == null ? null : Registry.PARTICLE_TYPE.get(k);
        return p != null && p.getDataType() == Void.class ? p : null;
    }

    static Sound sound(String id) {
        if (id == null || id.isBlank()) return null;
        NamespacedKey k = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
        return k == null ? null : Registry.SOUNDS.get(k);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static double rnd() {
        return ThreadLocalRandom.current().nextDouble() * 2 - 1;
    }
}
