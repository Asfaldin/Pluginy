package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobBehavior.Action;
import elo.mainplugins.mobs.model.MobBehavior.Cond;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Item;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
        /** Wyzwalacz "item": przedmiot, który mob podniesie (akcja take_item). */
        Item item;
        int next;
        /** Skok: od, do, start (tick), długość (ticki), wysokość łuku. */
        Location leapFrom, leapTo;
        int leapStart = -1, leapTicks;
        double leapHeight;
        /** Akcja launch: lot pionowy z przyspieszeniem, jak rakieta. */
        boolean rocket;
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
    /** Zmienne moba (akcje var_set / var_add, warunek var, {var:nazwa} w tekstach) - jak w programie. */
    private final Map<String, Double> vars = new HashMap<>();
    /** Efekty trwające w czasie (wzmocnienia, świecenie, bloki, fala uderzeniowa) - tick co tick, end przy końcu. */
    private final List<Effect> effects = new ArrayList<>();
    /** Główna umiejętność wywołana akcją cast, gdy inna główna jeszcze trwa - rusza zaraz po niej (combo). */
    private Skill queued;
    private LivingEntity queuedTarget;
    private LivingEntity lastTarget;
    private double damageBuff = 1;
    private int depth, buffSeq;

    private interface Effect {
        /** true = koniec. */
        boolean tick(int now);

        default void end() {}
    }

    SkillRunner(LiveMob mob, List<Skill> skills) {
        this.mob = mob;
        this.skills = skills;
        // Walka i "co jakiś czas": pierwsze użycie po kilku sekundach, każda umiejętność w innej chwili.
        // Reakcje na zdarzenia (handel, sygnał, rzucony przedmiot...) działają od razu po postawieniu moba.
        for (Skill s : skills) {
            boolean repeating = s.on("combat") || s.on("timer");
            ready.put(s, repeating ? 60 + ThreadLocalRandom.current().nextInt(100) : 0);
        }
    }

    boolean busy() {
        return main != null;
    }

    static boolean exclusive(Skill s) {
        if (s.animation() != null) return true;
        for (Action a : s.actions()) if (moves(a) || a.type().equals("stun")) return true;
        return false;
    }

    /** Akcje, które przesuwają moba w czasie (skok, wystrzał w górę). */
    private static boolean moves(Action a) {
        return a.type().equals("leap") || a.type().equals("launch");
    }

    /** Animacja głównej umiejętności i chwila w niej (do pozy moba) albo null. */
    Object[] tick(int ticks, LivingEntity target, boolean canStartMain) {
        for (Effect e : new ArrayList<>(effects)) {
            boolean done;
            try {
                done = e.tick(ticks);
            } catch (RuntimeException ex) {
                done = true;
            }
            if (done) {
                e.end();
                effects.remove(e);
            }
        }
        // Wyzwalacz "target": mob właśnie wybrał nowy cel.
        if (target != null && target != lastTarget) {
            lastTarget = target;
            if (ticks > 20) trigger("target", target, 0);
        } else if (target == null) {
            lastTarget = null;
        }
        if (main == null && queued != null && canStartMain) {
            Skill q = queued;
            queued = null;
            start(q, alive(queuedTarget) != null ? queuedTarget : target);
        }
        if (main == null && ticks > 40) {
            tryTrigger("combat", target, canStartMain);
            tryTrigger("timer", target, canStartMain);
            if (main == null && ticks % 10 == 0) tryNear(canStartMain);
            if (main == null && ticks % 5 == 0) tryItem(canStartMain);
        }
        // Kopia: akcja cast może dodać nową umiejętność w tle w trakcie tej pętli (combo).
        for (Run r : new ArrayList<>(background)) advance(r, ticks);
        background.removeIf(r -> r.next >= r.skill.actions().size() && r.leapStart < 0);
        if (main == null) return null;
        return tickMain(ticks);
    }

    /** Zdarzenie (hit, hurt, spawn, death, phase) - uruchamia pasujące umiejętności. */
    void trigger(String trigger, LivingEntity target, int phase) {
        for (Skill s : skills) {
            if (!s.on(trigger)) continue;
            if (trigger.equals("phase") && s.phase() != phase) continue;
            if (!conditionsOk(s, target, !trigger.equals("death"), trigger)) continue;
            if (trigger.equals("death")) {
                // Mob już ginie - wszystkie akcje od razu, w miejscu śmierci.
                Run r = new Run(s, mob.ticks(), target);
                for (Action a : s.actions()) if (!moves(a) && !a.type().equals("stun")) exec(r, a);
                continue;
            }
            start(s, target);
        }
    }

    private void tryTrigger(String trigger, LivingEntity target, boolean canStartMain) {
        for (Skill s : skills) {
            if (!s.on(trigger)) continue;
            if (trigger.equals("combat") && target == null) continue;
            if (exclusive(s) && !canStartMain) continue;
            if (!conditionsOk(s, target, true, trigger)) continue;
            start(s, target);
            if (main != null) return;
        }
    }

    /** Wyzwalacz "signal": inny mob wysłał sygnał o nazwie tej umiejętności (akcja signal); cel = nadawca. */
    void signal(String name, LivingEntity from) {
        for (Skill s : skills) {
            if (!s.on("signal") || !s.name().equalsIgnoreCase(name)) continue;
            if (!conditionsOk(s, from, true, "signal")) continue;
            start(s, from);
        }
    }

    /** Wyzwalacz "near": gracz podszedł bliżej niż rangeMax (cel = najbliższy taki gracz). */
    private void tryNear(boolean canStartMain) {
        for (Skill s : skills) {
            if (!s.on("near")) continue;
            if (exclusive(s) && !canStartMain) continue;
            Location at = mob.base.getLocation();
            Player best = null;
            double bestDist = Double.MAX_VALUE;
            for (Player p : players(at, s.rangeMax())) {
                double d = p.getLocation().distance(at);
                if (d >= s.rangeMin() && d < bestDist) {
                    best = p;
                    bestDist = d;
                }
            }
            if (best == null || !conditionsOk(s, best, true, "near")) continue;
            start(s, best);
            if (main != null) return;
        }
    }

    /** Wyzwalacz "item": najbliższy przedmiot leżący na ziemi w zasięgu (np. rzucony emerald). */
    private void tryItem(boolean canStartMain) {
        for (Skill s : skills) {
            if (!s.on("item")) continue;
            if (exclusive(s) && !canStartMain) continue;
            Item found = nearestItem(s);
            if (found == null || !conditionsOk(s, null, true, "item")) continue;
            start(s, null, found);
            if (main != null) return;
        }
    }

    private Item nearestItem(Skill s) {
        Material want = s.item().isBlank() ? null : Material.matchMaterial(s.item());
        if (want == null && !s.item().isBlank()) return null;
        Location at = mob.base.getLocation();
        Item best = null;
        double bestDist = Double.MAX_VALUE;
        for (Item it : at.getNearbyEntitiesByType(Item.class, s.rangeMax())) {
            if (!it.isValid() || !it.isOnGround() || (want != null && it.getItemStack().getType() != want)) continue;
            double d = it.getLocation().distance(at);
            if (d >= s.rangeMin() && d <= s.rangeMax() && d < bestDist) {
                best = it;
                bestDist = d;
            }
        }
        return best;
    }

    /** why = wyzwalacz, który właśnie zadziałał (umiejętność może mieć kilka). */
    private boolean conditionsOk(Skill s, LivingEntity target, boolean checkCooldown, String why) {
        int ticks = mob.ticks();
        if (checkCooldown && ticks < ready.getOrDefault(s, 0)) return false;
        if (!why.equals("phase") && s.phase() > mob.phaseIndex()) return false;
        if (mob.healthFraction() > s.healthBelow() + 1e-9) return false;
        if (target != null && (why.equals("combat") || s.rangeMax() < 256)) {
            if (target.getWorld() != mob.base.getWorld()) return false;
            double d = target.getLocation().distance(mob.base.getLocation());
            if (why.equals("combat") && (d < s.rangeMin() || d > s.rangeMax())) return false;
        }
        if (!condsOk(s.conditions(), target)) return false;
        if (s.chance() < 1 && ThreadLocalRandom.current().nextDouble() > s.chance()) {
            // Nieudany rzut też odczekuje - inaczej "szansa" byłaby losowana co tick.
            ready.put(s, ticks + (int) (Math.max(1, s.cooldown()) * 20));
            return false;
        }
        return true;
    }

    private void start(Skill s, LivingEntity target) {
        start(s, target, null);
    }

    private void start(Skill s, LivingEntity target, Item item) {
        int ticks = mob.ticks();
        ready.put(s, ticks + (int) (s.cooldown() * 20));
        Run r = new Run(s, ticks, target);
        r.item = item;
        double end = 0;
        MobDef.Anim anim = mob.find(s.animation());
        if (anim != null) end = anim.length();
        for (Action a : s.actions()) end = Math.max(end, a.at() + (moves(a) ? a.p().num("duration", 1) : 0));
        r.end = ticks + (int) Math.ceil(end * 20);
        if (exclusive(s)) {
            if (main != null) return;
            main = r;
            if (target != null) mob.face(target.getLocation());
            else if (item != null) mob.face(item.getLocation());
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
            float along = r.rocket ? k * k : k; // rakieta rusza powoli i przyspiesza
            Location at = r.leapFrom.clone().add(r.leapTo.toVector().subtract(r.leapFrom.toVector()).multiply(along));
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
                startQueued();
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
            startQueued();
            return null;
        }
        // Po końcu animacji (np. w locie) trzyma się jej ostatnia poza.
        return anim == null ? new Object[]{null, 0f} : new Object[]{anim, Math.min(t, anim.length())};
    }

    /** Combo: następna główna umiejętność od razu po skończonej (bez przerwy na chodzenie). */
    private void startQueued() {
        if (queued == null) return;
        Skill q = queued;
        queued = null;
        start(q, alive(queuedTarget));
    }

    void cancel() {
        main = null;
        queued = null;
        background.clear();
        effects.forEach(Effect::end);
        effects.clear();
        damageBuff = 1;
    }

    /** Akcje od razu (kliknięcie NPC): z animacją jako główna (NPC stoi i mówi), bez - w tle. */
    void runNow(String name, List<Action> actions, LivingEntity target, String animation) {
        Skill s = new Skill(name, "click", 0, 0, 256, 1, 1, 0, animation, actions, "");
        if (animation != null && main != null) main = null;
        start(s, target);
    }

    // ---- akcje ----

    /** Akcja: najpierw jej warunki (if), potem na kim (on) - raz na każdą wybraną istotę. */
    private void exec(Run r, Action a) {
        LivingEntity target = alive(r.target);
        if (!a.conditions().isEmpty() && !condsOk(a.conditions(), target)) return;
        String on = a.p().str("on", "target");
        if (on.isBlank() || on.equals("target")) {
            execOne(r, a, target);
            return;
        }
        for (LivingEntity le : who(on, a.p().num("onRadius", 8), target)) execOne(r, a, le);
    }

    private void execOne(Run r, Action a, LivingEntity target) {
        Params p = a.p();
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
                case "launch" -> {
                    // Pionowo w górę jak rakieta (np. wieśniak-fajerwerk), lot z przyspieszeniem.
                    r.leapFrom = base.getLocation();
                    r.leapTo = r.leapFrom.clone().add(0, Math.max(1, Math.min(64, p.num("height", 20))), 0);
                    r.leapTicks = Math.max(1, (int) Math.round(p.num("duration", 1.5) * 20));
                    r.leapHeight = 0;
                    r.rocket = true;
                    r.leapStart = mob.ticks();
                    mob.sound(base.getLocation(), p.str("sound", "entity.firework_rocket.launch"), 2f, 1f);
                }
                case "firework" -> mob.firework(p);
                case "signal" -> {
                    for (LiveMob m : mob.allies(p.num("radius", 16))) m.signal(p.str("name", ""), base);
                }
                case "watch" -> {
                    if (target != null) mob.watch(target, p.num("seconds", 4));
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
                    String shape = p.str("shape", "point");
                    if (!shape.equals("point")) {
                        shape(particle, shape, at, target, p);
                        return;
                    }
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
                case "take_item" -> {
                    // Przedmiot z wyzwalacza "item" leci do moba jak przy zwykłym podnoszeniu, ubywa jedna sztuka.
                    Item it = r.item;
                    if (it == null || !it.isValid()) return;
                    base.playPickupItemAnimation(it, 1);
                    ItemStack stack = it.getItemStack();
                    if (stack.getAmount() <= 1) it.remove();
                    else {
                        stack.setAmount(stack.getAmount() - 1);
                        it.setItemStack(stack);
                    }
                    mob.sound(base.getLocation(), p.str("sound", "entity.item.pickup"), 0.6f, 1.2f);
                    r.item = null;
                }
                case "stun" -> {
                    r.stunAnim = mob.find(blank(p.str("animation", null)));
                    double sec = p.num("seconds", 0);
                    if (sec <= 0) sec = r.stunAnim != null ? r.stunAnim.length() : 2;
                    r.stunTicks = Math.max(1, (int) (sec * 20));
                }
                case "message" -> {
                    String text = fill(p.str("text", "").replace("{mob}", mob.displayName()), target);
                    var msg = LegacyComponentSerializer.legacyAmpersand().deserialize(text);
                    double radius = p.num("radius", 32);
                    if (radius <= 0 && target instanceof Player pl) pl.sendMessage(msg); // 0 = tylko do celu
                    else for (Player pl : base.getLocation().getNearbyPlayers(radius)) pl.sendMessage(msg);
                }
                case "open_shop" -> {
                    if (target instanceof Player pl) pl.performCommand(("sklep " + p.str("category", "")).trim());
                }
                case "open_trade" -> {
                    if (target instanceof Player pl) mob.openTrade(pl);
                }
                case "open_quests" -> {
                    if (target instanceof Player pl) pl.performCommand("zadania");
                }
                case "player_command" -> {
                    String cmd = fill(p.str("command", ""), target);
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
                    String cmd = fill(p.str("command", "").replace("{mob}", mob.id()), target);
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    if (!cmd.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
                // ---- logika: zmienne, wywołania, przerwanie ----
                case "var_set" -> vars.put(p.str("name", "x"), p.num("value", 0));
                case "var_add" -> vars.merge(p.str("name", "x"), p.num("value", 1), Double::sum);
                case "cast" -> cast(p.str("skill", ""), target, p.bool("force", false));
                case "cast_random" -> {
                    List<String> names = new ArrayList<>();
                    for (String n : p.str("skills", "").split(",")) if (!n.isBlank()) names.add(n.trim());
                    if (!names.isEmpty()) cast(names.get(ThreadLocalRandom.current().nextInt(names.size())), target, p.bool("force", false));
                }
                case "stop" -> {
                    r.next = r.skill.actions().size();
                    r.end = Math.min(r.end, mob.ticks());
                }
                // ---- ruch i cel ----
                case "dash" -> {
                    Vector dir = target != null && target != base ? target.getLocation().toVector().subtract(base.getLocation().toVector()).setY(0)
                            : base.getLocation().getDirection().setY(0);
                    if (dir.lengthSquared() < 0.01) dir = new Vector(0, 0, 1);
                    base.setVelocity(dir.normalize().multiply(p.num("forward", 1.2)).setY(p.num("up", 0.3)));
                }
                case "push" -> {
                    if (target == null || target == base) return;
                    Vector away = target.getLocation().toVector().subtract(base.getLocation().toVector()).setY(0);
                    if (away.lengthSquared() < 0.01) away = base.getLocation().getDirection().setY(0);
                    if (away.lengthSquared() < 0.01) away = new Vector(0, 0, 1);
                    target.setVelocity(away.normalize().multiply(p.num("strength", 1.2)).setY(p.num("up", 0.5)));
                }
                case "retarget" -> {
                    if (p.bool("clear", false)) mob.setTarget(null);
                    else if (target != null && target != base) mob.setTarget(target);
                }
                // ---- wygląd i statystyki ----
                case "buff" -> buff(p);
                case "glow" -> {
                    if (target == null) return;
                    LivingEntity le = target;
                    int until = mob.ticks() + (int) (p.num("seconds", 5) * 20);
                    le.setGlowing(true);
                    effects.add(new Effect() {
                        public boolean tick(int now) {
                            return now >= until || !le.isValid();
                        }

                        public void end() {
                            if (le.isValid()) le.setGlowing(false);
                        }
                    });
                }
                case "equip" -> {
                    Material m = Material.matchMaterial(p.str("item", "iron_sword"));
                    var eq = base.getEquipment();
                    if (eq == null) return;
                    EquipmentSlot slot = switch (p.str("slot", "hand")) {
                        case "offhand" -> EquipmentSlot.OFF_HAND;
                        case "head" -> EquipmentSlot.HEAD;
                        case "chest" -> EquipmentSlot.CHEST;
                        case "legs" -> EquipmentSlot.LEGS;
                        case "feet" -> EquipmentSlot.FEET;
                        default -> EquipmentSlot.HAND;
                    };
                    eq.setItem(slot, m == null || m.isAir() ? null : new ItemStack(m));
                }
                // ---- efekty obszarowe ----
                case "beam" -> beam(p, target);
                case "shockwave" -> shockwave(p, target != null ? target : base);
                case "blocks" -> blocks(p, target != null ? target : base);
                default -> { /* nieznany typ (nowsza aplikacja) - pomijamy */ }
            }
        } catch (RuntimeException e) {
            mob.warn("Umiejętność " + r.skill.name() + ", akcja " + a.type() + ": " + e.getMessage());
        }
    }

    private void hit(LivingEntity le, double amount, Location from, double knockback, double up) {
        le.damage(amount * mob.damageMultiplier() * damageBuff, mob.base);
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
        // ring: rowno na okregu (kat startowy "angle", srodek przesuniety o centerX/centerZ pikseli modelu), przodem do srodka
        boolean ring = p.bool("ring", false);
        Location center = ring ? mob.modelPoint((float) p.num("centerX", 0), 24, (float) p.num("centerZ", 0)) : null;
        for (int i = 0; i < count; i++) {
            Location at;
            if (ring) {
                double a = Math.toRadians(p.num("angle", 0)) + Math.PI * 2 * i / count;
                at = ground(center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius), 4);
                if (at == null) at = center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
                Vector d = center.toVector().subtract(at.toVector());
                at.setYaw((float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())));
                at.setPitch(0);
            } else {
                at = ground(mob.base.getLocation().add(rnd() * radius, 0, rnd() * radius), 4);
                if (at == null) at = mob.base.getLocation();
            }
            Entity spawned = mob.summonCustom(what, at, target, life);
            if (ring && spawned instanceof Mob m) {
                m.setRotation(at.getYaw(), 0);
                m.setBodyYaw(at.getYaw());
            }
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

    // ---- logika v2: cele, warunki, zmienne, combo ----

    private static LivingEntity alive(LivingEntity e) {
        return e != null && e.isValid() && !e.isDead() ? e : null;
    }

    /** Gracze w promieniu, którzy grają (bez trybu kreatywnego i obserwatora). */
    private static List<Player> players(Location at, double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : at.getNearbyPlayers(radius)) {
            if (p.isValid() && !p.isDead() && p.getGameMode() != org.bukkit.GameMode.CREATIVE && p.getGameMode() != org.bukkit.GameMode.SPECTATOR) out.add(p);
        }
        return out;
    }

    /** Cel akcji (pole "on"): self, attacker, nearest_player, random_player, players, allies, mobs, everyone, target. */
    List<LivingEntity> who(String on, double radius, LivingEntity target) {
        Location at = mob.base.getLocation();
        List<LivingEntity> out = new ArrayList<>();
        switch (on) {
            case "self" -> out.add(mob.base);
            case "attacker" -> {
                Player p = mob.lastAttacker();
                if (p != null && p.getWorld() == at.getWorld()) out.add(p);
            }
            case "nearest_player" -> {
                Player best = null;
                for (Player p : players(at, radius)) if (best == null || p.getLocation().distanceSquared(at) < best.getLocation().distanceSquared(at)) best = p;
                if (best != null) out.add(best);
            }
            case "random_player" -> {
                List<Player> ps = players(at, radius);
                if (!ps.isEmpty()) out.add(ps.get(ThreadLocalRandom.current().nextInt(ps.size())));
            }
            case "players" -> out.addAll(players(at, radius));
            case "allies" -> {
                for (LiveMob m : mob.allies(radius)) out.add(m.base);
            }
            case "mobs", "everyone" -> {
                for (LivingEntity le : at.getNearbyLivingEntities(radius)) {
                    if (le == mob.base || le instanceof ArmorStand || !le.isValid() || le.isDead()) continue;
                    if (le instanceof Player p && (on.equals("mobs") || p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR)) continue;
                    out.add(le);
                }
            }
            default -> {
                if (target != null) out.add(target);
            }
        }
        return out.size() > 64 ? out.subList(0, 64) : out;
    }

    private boolean condsOk(List<Cond> conds, LivingEntity target) {
        for (Cond c : conds) if (cond(c, target) == c.not()) return false;
        return true;
    }

    /** Jeden warunek (bez "not"). Nieznany typ = spełniony (nowsza aplikacja). */
    boolean cond(Cond c, LivingEntity target) {
        Params p = c.p();
        Mob base = mob.base;
        double v = p.num("value", 0);
        double dist = target != null && target.getWorld() == base.getWorld() ? target.getLocation().distance(base.getLocation()) : -1;
        long time = base.getWorld().getTime();
        return switch (c.type()) {
            case "health_below" -> mob.healthFraction() * 100 < v;
            case "health_above" -> mob.healthFraction() * 100 > v;
            case "target_health_below" -> target != null && healthPercent(target) < v;
            case "target_health_above" -> target != null && healthPercent(target) > v;
            case "distance_below" -> dist >= 0 && dist < v;
            case "distance_above" -> dist > v;
            case "chance" -> ThreadLocalRandom.current().nextDouble() * 100 < v;
            case "has_target" -> target != null;
            case "target_is_player" -> target instanceof Player;
            case "day" -> time < 12300 || time > 23850;
            case "night" -> time >= 12300 && time <= 23850;
            case "raining" -> base.getWorld().hasStorm();
            case "in_water" -> base.isInWater();
            case "on_ground" -> base.isOnGround();
            case "phase_at_least" -> mob.phaseIndex() >= v;
            case "players_nearby" -> players(base.getLocation(), p.num("radius", 16)).size() >= Math.max(1, v);
            case "allies_nearby" -> mob.allies(p.num("radius", 16)).size() >= Math.max(1, v);
            case "var" -> compare(vars.getOrDefault(p.str("name", "x"), 0.0), p.str("op", "="), v);
            case "target_has_effect" -> {
                PotionEffectType t = effect(p.str("effect", "poison"));
                yield target != null && t != null && target.hasPotionEffect(t);
            }
            default -> true;
        };
    }

    static boolean compare(double a, String op, double b) {
        return switch (op) {
            case ">" -> a > b;
            case "<" -> a < b;
            case ">=" -> a >= b;
            case "<=" -> a <= b;
            case "!=" -> Math.abs(a - b) > 1e-9;
            default -> Math.abs(a - b) <= 1e-9;
        };
    }

    private static double healthPercent(LivingEntity le) {
        var max = le.getAttribute(Attribute.MAX_HEALTH);
        double m = max != null ? max.getValue() : 20;
        return m <= 0 ? 0 : le.getHealth() / m * 100;
    }

    double var(String name) {
        return vars.getOrDefault(name, 0.0);
    }

    /** Teksty akcji: {player} {target} {x} {y} {z} {world} {hp} {hp%} {var:nazwa}. */
    String fill(String s, LivingEntity target) {
        Location l = mob.base.getLocation();
        String name = target != null ? target.getName() : "";
        String out = s.replace("{player}", name).replace("{target}", name)
                .replace("{x}", String.valueOf(l.getBlockX())).replace("{y}", String.valueOf(l.getBlockY())).replace("{z}", String.valueOf(l.getBlockZ()))
                .replace("{world}", l.getWorld().getName()).replace("{hp}", fmt(mob.base.getHealth()))
                .replace("{hp%}", String.valueOf(Math.round(mob.healthFraction() * 100)));
        if (out.contains("{var:")) {
            for (Map.Entry<String, Double> e : vars.entrySet()) out = out.replace("{var:" + e.getKey() + "}", fmt(e.getValue()));
            out = out.replaceAll("\\{var:[^}]*}", "0");
        }
        return out;
    }

    static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.2f", d);
    }

    /** Akcja cast: inna umiejętność po nazwie. Główna przy trwającej głównej - w kolejce (combo). */
    private void cast(String name, LivingEntity target, boolean force) {
        if (depth > 8 || name.isBlank()) return;
        for (Skill s : skills) {
            if (!s.name().equalsIgnoreCase(name)) continue;
            if (!force && !conditionsOk(s, target, true, s.trigger())) return;
            if (exclusive(s) && main != null) {
                queued = s;
                queuedTarget = target;
                return;
            }
            depth++;
            try {
                start(s, target);
            } finally {
                depth--;
            }
            return;
        }
    }

    /** Wzmocnienie na czas: damage/speed (mnożnik), armor/knockback (dodane punkty). */
    private void buff(Params p) {
        String stat = p.str("stat", "damage");
        double value = p.num("value", 1.5);
        int until = mob.ticks() + (int) (Math.max(0.05, p.num("seconds", 5)) * 20);
        Attribute attr = switch (stat) {
            case "speed" -> Attribute.MOVEMENT_SPEED;
            case "armor" -> Attribute.ARMOR;
            case "knockback" -> Attribute.KNOCKBACK_RESISTANCE;
            default -> Attribute.ATTACK_DAMAGE;
        };
        boolean multiply = stat.equals("damage") || stat.equals("speed");
        if (multiply) value = Math.max(0, Math.min(20, value));
        AttributeInstance inst = mob.base.getAttribute(attr);
        NamespacedKey key = new NamespacedKey("mainplugins", "skill_buff_" + (buffSeq++));
        if (inst != null) {
            inst.addTransientModifier(new AttributeModifier(key, multiply ? value - 1 : value,
                    multiply ? AttributeModifier.Operation.MULTIPLY_SCALAR_1 : AttributeModifier.Operation.ADD_NUMBER));
        }
        double mult = stat.equals("damage") ? value : 1;
        damageBuff *= mult;
        effects.add(new Effect() {
            public boolean tick(int now) {
                return now >= until;
            }

            public void end() {
                if (inst != null) inst.removeModifier(key);
                if (mult > 0) damageBuff /= mult;
            }
        });
    }

    /** Cząsteczki w kształcie: circle, sphere, line (do celu), spiral, ring (wiele okręgów w górę). */
    private void shape(Particle particle, String shape, Location at, LivingEntity target, Params p) {
        World w = at.getWorld();
        int n = (int) Math.max(4, Math.min(600, p.num("count", 40)));
        double r = Math.max(0.1, Math.min(64, p.num("radius", 2)));
        double speed = p.num("speed", 0);
        switch (shape) {
            case "circle" -> {
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    w.spawnParticle(particle, at.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, speed);
                }
            }
            case "sphere" -> {
                double golden = Math.PI * (3 - Math.sqrt(5));
                for (int i = 0; i < n; i++) {
                    double y = 1 - 2.0 * (i + 0.5) / n, rr = Math.sqrt(1 - y * y), a = golden * i;
                    w.spawnParticle(particle, at.clone().add(Math.cos(a) * rr * r, y * r, Math.sin(a) * rr * r), 1, 0, 0, 0, speed);
                }
            }
            case "line" -> {
                if (target == null) return;
                line(particle, at, target.getLocation().add(0, target.getHeight() / 2, 0), n, speed);
            }
            case "spiral" -> {
                double height = p.num("height", 3), turns = Math.max(0.5, p.num("turns", 3));
                for (int i = 0; i < n; i++) {
                    double t = (double) i / n, a = t * turns * Math.PI * 2;
                    w.spawnParticle(particle, at.clone().add(Math.cos(a) * r, t * height, Math.sin(a) * r), 1, 0, 0, 0, speed);
                }
            }
            case "ring" -> {
                double height = p.num("height", 3);
                int rings = Math.max(2, (int) Math.round(height * 2));
                int per = Math.max(6, n / rings);
                for (int k = 0; k < rings; k++) {
                    for (int i = 0; i < per; i++) {
                        double a = Math.PI * 2 * i / per;
                        w.spawnParticle(particle, at.clone().add(Math.cos(a) * r, height * k / (rings - 1), Math.sin(a) * r), 1, 0, 0, 0, speed);
                    }
                }
            }
            default -> w.spawnParticle(particle, at, n, 0.5, 0.5, 0.5, speed);
        }
    }

    private static void line(Particle particle, Location from, Location to, int n, double speed) {
        Vector step = to.toVector().subtract(from.toVector()).multiply(1.0 / Math.max(1, n - 1));
        Location at = from.clone();
        for (int i = 0; i < n; i++) {
            from.getWorld().spawnParticle(particle, at, 1, 0, 0, 0, speed);
            at.add(step);
        }
    }

    /** Promień z części modelu do celu (cząsteczki) i obrażenia celu. */
    private void beam(Params p, LivingEntity target) {
        if (target == null || target == mob.base) return;
        Location from = mob.bonePos(blank(p.str("bone", null)));
        Location to = target.getLocation().add(0, target.getHeight() / 2, 0);
        if (from.getWorld() != to.getWorld()) return;
        Particle particle = particle(p.str("particle", "end_rod"));
        if (particle != null) line(particle, from, to, (int) Math.min(300, Math.max(4, from.distance(to) * 4)), 0);
        double dmg = p.num("damage", 4);
        if (dmg > 0) hit(target, dmg, from, p.num("knockback", 0.3), 0.1);
        mob.sound(from, p.str("sound", "entity.guardian.attack"), 1.5f, 1.4f);
    }

    /** Fala uderzeniowa: okrąg rośnie od środka, każdego gracza na krawędzi trafia raz. */
    private void shockwave(Params p, LivingEntity center) {
        Location c = center.getLocation().add(0, 0.2, 0);
        Particle particle = particle(p.str("particle", "cloud"));
        double max = Math.max(1, Math.min(48, p.num("radius", 8))), perTick = Math.max(0.05, p.num("speed", 10) / 20);
        double dmg = p.num("damage", 6), knock = p.num("knockback", 0.8), up = p.num("up", 0.5);
        int start = mob.ticks();
        Set<UUID> done = new HashSet<>();
        mob.sound(c, p.str("sound", "entity.generic.explode"), 1.5f, 0.7f);
        effects.add(now -> {
            double r = (now - start + 1) * perTick;
            int n = (int) Math.max(12, Math.min(160, r * 7));
            if (particle != null) {
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    c.getWorld().spawnParticle(particle, c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0.05, 0, 0);
                }
            }
            for (Player pl : players(c, r + 1)) {
                double d = pl.getLocation().distance(c);
                if (Math.abs(d - r) <= 1.2 && Math.abs(pl.getLocation().getY() - c.getY()) < 2.5 && done.add(pl.getUniqueId())) {
                    hit(pl, dmg, c, knock, up);
                }
            }
            return r >= max;
        });
    }

    /** Bloki na czas: ring | disk | cage (pierścień i dach); solid = prawdziwe (tylko w pustym miejscu), inaczej sam wygląd. */
    private void blocks(Params p, LivingEntity center) {
        Material m = Material.matchMaterial(p.str("block", "cobweb"));
        if (m == null || !m.isBlock() || m.isAir()) return;
        BlockData data = m.createBlockData();
        Location c = center.getLocation();
        World w = c.getWorld();
        double r = Math.max(0, Math.min(12, p.num("radius", 2)));
        int height = (int) Math.max(1, Math.min(6, p.num("height", 1)));
        String shape = p.str("shape", "ring");
        boolean solid = p.bool("solid", false);
        int until = mob.ticks() + (int) (Math.max(0.5, Math.min(300, p.num("seconds", 5))) * 20);
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ(), ir = (int) Math.ceil(r);
        Set<Block> spots = new java.util.LinkedHashSet<>();
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                boolean edge = Math.abs(d - r) < 0.75;
                boolean in = d <= r + 0.25;
                if (shape.equals("disk") ? in : edge || r < 0.5) {
                    for (int y = 0; y < height; y++) spots.add(w.getBlockAt(cx + dx, cy + y, cz + dz));
                }
                if (shape.equals("cage") && in) spots.add(w.getBlockAt(cx + dx, cy + height, cz + dz));
            }
        }
        Map<Block, BlockData> placed = new java.util.LinkedHashMap<>();
        List<Entity> shown = new ArrayList<>();
        for (Block b : spots) {
            if (placed.size() + shown.size() >= 400) break;
            // Tylko puste miejsce albo coś do zastąpienia (trawa, kwiatki, śnieg) - bez niszczenia budowli.
            if (!b.getType().isAir() && !(b.isReplaceable() && !b.isLiquid())) continue;
            if (solid) {
                placed.put(b, b.getBlockData());
                b.setBlockData(data, false);
            } else {
                shown.add(w.spawn(b.getLocation(), BlockDisplay.class, d -> {
                    d.setBlock(data);
                    d.setPersistent(false);
                }));
            }
        }
        effects.add(new Effect() {
            public boolean tick(int now) {
                return now >= until;
            }

            public void end() {
                placed.forEach((b, was) -> {
                    if (b.getType() == m) b.setBlockData(was, false);
                });
                shown.forEach(Entity::remove);
            }
        });
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
