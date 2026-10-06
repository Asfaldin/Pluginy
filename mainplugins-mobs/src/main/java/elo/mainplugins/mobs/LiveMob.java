package elo.mainplugins.mobs;

import elo.mainplugins.mobs.model.MobBehavior;
import elo.mainplugins.mobs.model.MobDef;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.entity.TeleportFlag;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Jeden mob w świecie: niewidzialne ciało (husk chodzi, pszczoła lata, strażnik pływa - ma hitbox,
 * nie pali się w słońcu) + części modelu jako item display. Zachowanie z MobBehavior: własne AI
 * (cel, pościg, walka wręcz albo z dystansu, wędrówka, ucieczka), fazy, umiejętności (SkillRunner),
 * nazwa nad mobem, pasek bossa. Ciało zostaje w świecie (tag z id moba) - po restarcie albo
 * wczytaniu chunka plugin dokłada mu części na nowo (konstruktor z istniejącym ciałem).
 */
final class LiveMob {

    /** Stworzenie z umiejętności "swarm" (np. ptaki): leci na cel i dziobie, po czasie znika. */
    private static final class Minion {
        final LivingEntity entity;
        final LivingEntity target;
        final int until;
        final double damage;
        int nextHit;

        Minion(LivingEntity entity, LivingEntity target, int until, double damage) {
            this.entity = entity;
            this.target = target;
            this.until = until;
            this.damage = damage;
        }
    }

    private final MobDef def;
    final MobBehavior bh;
    private final MobHost host;
    final Mob base;
    private final Map<String, ItemDisplay> parts = new LinkedHashMap<>();
    private final Map<MobDef.Effect, Particle> particles = new HashMap<>();
    private final List<Minion> swarm = new ArrayList<>();
    /** Przywołane zwykłe moby z czasem życia: byt -> tick zniknięcia. */
    private final Map<Entity, Integer> expiring = new HashMap<>();
    private final SkillRunner skills;
    private int ticks;
    private int attackStart = -1000;
    private int hurtStart = -1000;
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
    /** fixedFacing: kierunek modelu ustalony przy pierwszym ticku (patrz tick). */
    private float fixedYaw = Float.NaN;
    /** Macierze kości z ostatniego ticka - skąd wylatują pociski i stworzenia. */
    private Map<String, Matrix4f> lastBones = Map.of();

    // ---- ruch żywy, wygląd, śmierć (MobRig, warianty, rozpad) ----
    private final MobRig rig;
    /** Punkt odniesienia dla sprężyn (duże współrzędne świata tracą precyzję we float). */
    private final Location origin;
    /** Wygląd (wariant) - zostaje po animacji, która go przełączyła (np. faza 2). */
    private String variant = "base";
    private String shownVariant = "base";
    /** Jednorazowa animacja bez umiejętności (losowe zachowanie, wejście w fazę). */
    private MobDef.Anim oneShot;
    private int oneShotStart;
    /** Wejście w fazę: mob stoi, dopóki gra jej animacja. */
    private boolean rooted;
    private int nextRandom = 200;
    /** Ile faz już weszło (0 = żadna). */
    private int phaseIndex;
    private double damageMultiplier = 1;
    private float runWeight;
    /** Hitboxy części: obiekt interakcji -> kość. */
    private final Map<UUID, String> hitParts = new HashMap<>();
    private final Map<String, Interaction> hitboxes = new LinkedHashMap<>();
    /** Śmierć: -1 = żyje, potem tick rozpoczęcia; rozpad części po animacji śmierci. */
    private int deathStart = -1;
    private Location deathLoc;
    private final Map<String, float[]> shards = new HashMap<>();
    private boolean finished;
    private static final Pattern BACKGROUND_EXCLUDE = Pattern.compile("^(run|fly|glide|sleep|swim|sit|lie|move|death|hurt|takeoff|land|charge|phase.*)$", Pattern.CASE_INSENSITIVE);

    // ---- AI ----
    private LivingEntity target;
    private UUID lastAttacker;
    private int lastHurt = -10000;
    private int attackReady;
    private int nextRepath;
    private int nextWander = 100;
    private int fleeUntil = -1;
    private Location fleeFrom;
    private int shieldUntil = -1;
    /** Przywołany przez innego moba: znika po tym ticku (-1 = nigdy). */
    private int expireAt = -1;
    private int nextAmbient = 200;

    // ---- NPC ----
    private Location home;
    private final Map<UUID, Integer> lastClick = new HashMap<>();
    private final Map<UUID, Integer> dialogueIndex = new HashMap<>();
    private final Map<UUID, Integer> waved = new HashMap<>();

    // ---- nazwa i pasek bossa ----
    private TextDisplay nameplate;
    private String shownPlate = "";
    private BossBar bossBar;
    private final Set<UUID> barViewers = new HashSet<>();

    /** Wysokość ciała bez skalowania (do skali atrybutu SCALE). */
    private static final Map<Class<?>, Double> BASE_HEIGHT = Map.of(Husk.class, 1.95, Bee.class, 0.6, Guardian.class, 0.85);

    /** Nowy mob w świecie (existing == null) albo przyłączenie do ciała, które przetrwało restart. */
    LiveMob(MobDef def, MobBehavior bh, MobHost host, Location at, Mob existing, boolean summoned) {
        this.def = def;
        this.bh = bh;
        this.host = host;
        if (existing != null) {
            this.base = existing;
            setup(existing, false);
        } else {
            Class<? extends Mob> type = switch (bh.movement()) {
                case "fly" -> Bee.class;
                case "swim" -> Guardian.class;
                default -> Husk.class;
            };
            this.base = at.getWorld().spawn(at, type, m -> setup(m, true));
        }
        at = base.getLocation();
        // Części powstają „na prosto” (kąt 0, bez pochylenia) - ich cały obrót liczymy sami w macierzy.
        // Z kierunkiem gracza gra dokładała go do każdej części: mob chodził bokiem i był pochylony.
        Location straight = straight(at);
        for (MobDef.Bone b : def.bones()) {
            if (!b.visible()) continue;
            ItemStack item = partStack(b.item(), false);
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
            Particle p = SkillRunner.particle(e.particle());
            // Tylko cząsteczki bez dodatkowych danych (kolor, blok...).
            if (p != null) particles.put(e, p);
        }
        last = at.clone();
        origin = at.clone();
        rig = new MobRig(def);
        rig.lookEnabled = bh.look();
        rig.ikEnabled = bh.feet();
        rig.springsEnabled = bh.springs();
        skills = new SkillRunner(this, bh.skills());
        spawnHitboxes(at);
        if (existing != null) {
            // Po restarcie fazy wracają po cichu (bez animacji wejścia) - według aktualnego życia.
            while (phaseIndex < bh.phases().size() && healthFraction() < bh.phases().get(phaseIndex).below()) enterPhase(false);
        }
        // NPC: dom (miejsce postawienia) zapamiętany na ciele - spacer w promieniu przeżywa restart.
        NamespacedKey homeKey = new NamespacedKey(host.plugin(), "home");
        String h = base.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
        if (h == null) {
            home = base.getLocation().clone();
            base.getPersistentDataContainer().set(homeKey, PersistentDataType.STRING, home.getX() + ";" + home.getY() + ";" + home.getZ());
        } else {
            String[] xyz = h.split(";");
            home = new Location(base.getWorld(), Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]));
        }
        tick();
        if (existing == null) sound(base.getLocation(), bh.spawnSound(), 1.5f, 1f);
        if (existing == null && !summoned) skills.trigger("spawn", null, 0);
    }

    /** Ciało: niewidzialne, ciche, bez zwykłego AI (steruje nim tick), statystyki z zachowania. */
    private void setup(Mob m, boolean fresh) {
        m.setInvisible(true);
        m.setSilent(true);
        m.setPersistent(bh.persistent());
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        if (m.getEquipment() != null) m.getEquipment().clear();
        if (m instanceof Husk z) {
            z.setShouldBurnInDay(false);
            z.setAdult();
            z.setConversionTime(-1);
        }
        if (m instanceof Bee bee) {
            bee.setCannotEnterHiveTicks(Integer.MAX_VALUE);
            bee.setHive(null);
            bee.setFlower(null);
            bee.setAnger(0);
        }
        double baseHeight = 1.95;
        for (Map.Entry<Class<?>, Double> e : BASE_HEIGHT.entrySet()) if (e.getKey().isInstance(m)) baseHeight = e.getValue();
        set(m, Attribute.SCALE, Math.max(0.06, Math.min(16, def.hitboxHeight() / baseHeight)));
        set(m, Attribute.SPAWN_REINFORCEMENTS, 0);
        set(m, Attribute.MAX_HEALTH, bh.health());
        set(m, Attribute.MOVEMENT_SPEED, bh.movement().equals("stationary") ? 0 : bh.speed());
        set(m, Attribute.FLYING_SPEED, bh.speed() * 2.2);
        set(m, Attribute.ATTACK_DAMAGE, bh.damage());
        set(m, Attribute.ARMOR, bh.armor());
        set(m, Attribute.KNOCKBACK_RESISTANCE, bh.movement().equals("stationary") ? 1 : bh.knockbackResistance());
        set(m, Attribute.FOLLOW_RANGE, bh.followRange());
        if (fresh) m.setHealth(bh.health());
        else if (m.getHealth() > bh.health()) m.setHealth(bh.health());
        m.getPersistentDataContainer().set(host.mobKey(), PersistentDataType.STRING, def.id().toLowerCase(Locale.ROOT));
        // Zwykłe cele (atak, gniew pszczoły, laser strażnika, wędrówka) - zastępuje je brain().
        Bukkit.getMobGoals().removeAllGoals(m);
    }

    private static void set(LivingEntity z, Attribute attribute, double value) {
        AttributeInstance a = z.getAttribute(attribute);
        if (a != null) a.setBaseValue(value);
    }

    private static Location straight(Location l) {
        Location s = l.clone();
        s.setYaw(0);
        s.setPitch(0);
        return s;
    }

    // ---- dla SkillRunner i pluginu ----

    String id() {
        return def.id();
    }

    int ticks() {
        return ticks;
    }

    int phaseIndex() {
        return phaseIndex;
    }

    double damageMultiplier() {
        return damageMultiplier;
    }

    double maxHealth() {
        AttributeInstance max = base.getAttribute(Attribute.MAX_HEALTH);
        return max != null ? max.getValue() : bh.health();
    }

    double healthFraction() {
        return Math.max(0, Math.min(1, base.getHealth() / Math.max(1, maxHealth())));
    }

    String displayName() {
        return bh.name();
    }

    NamespacedKey damageKey() {
        return host.damageKey();
    }

    void warn(String message) {
        host.plugin().getLogger().warning("Mob " + def.id() + ": " + message);
    }

    void shield(int ticksLong) {
        shieldUntil = Math.max(shieldUntil, ticks + ticksLong);
    }

    boolean shielded() {
        return ticks < shieldUntil;
    }

    void setVariant(String v) {
        variant = v == null ? "base" : v;
    }

    LivingEntity target() {
        return target;
    }

    void setTarget(LivingEntity t) {
        target = t;
    }

    /** Przywołany mob: nie zostaje w świecie po restarcie, znika po czasie (0 = nie znika). */
    void markSummoned(int life) {
        base.setPersistent(false);
        if (life > 0) expireAt = ticks + life;
    }

    /** Inne moby z paczki w promieniu (bez tego). */
    List<LiveMob> allies(double radius) {
        List<LiveMob> out = new ArrayList<>();
        for (LiveMob m : host.nearby(base.getLocation(), radius)) if (m != this && m.alive()) out.add(m);
        return out;
    }

    /** Śmierć z umiejętności (kamikaze): bez łupów - zwykłe usunięcie ciała, części rozpadają się jak po śmierci. */
    void dieSilently() {
        base.remove();
    }

    boolean npcInvulnerable() {
        return bh.npc().enabled() && bh.npc().invulnerable();
    }

    /**
     * Kliknięcie NPC: patrzy na gracza, animacja "talk", kolejna linijka dialogu; po ostatniej
     * (albo od razu, gdy dialogu brak) akcje onClick - sklep, questy, nagroda, komenda...
     */
    void onInteract(Player p) {
        MobBehavior.Npc npc = bh.npc();
        if (!npc.enabled() || !alive()) return;
        Integer last = lastClick.get(p.getUniqueId());
        if (last != null && ticks - last < npc.clickCooldown() * 20) return;
        lastClick.put(p.getUniqueId(), ticks);
        face(p.getLocation());
        List<String> lines = npc.dialogue();
        boolean runActions = true;
        if (!lines.isEmpty()) {
            int i = dialogueIndex.getOrDefault(p.getUniqueId(), 0);
            String line = lines.get(Math.min(i, lines.size() - 1)).replace("{player}", p.getName()).replace("{mob}", bh.name());
            var text = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(line);
            if (npc.dialogueMode().equals("title")) {
                p.showTitle(net.kyori.adventure.title.Title.title(Component.text(bh.name(), NamedTextColor.GOLD), text));
            } else {
                p.sendMessage(Component.text(bh.name() + ": ", NamedTextColor.GOLD).append(text));
            }
            runActions = i >= lines.size() - 1;
            dialogueIndex.put(p.getUniqueId(), i + 1 >= lines.size() ? 0 : i + 1);
        }
        List<MobBehavior.Action> actions = runActions ? npc.onClick() : List.of();
        if (runActions && npc.oncePerPlayer() && !actions.isEmpty()) {
            NamespacedKey doneKey = new NamespacedKey(host.plugin(), "npc_done");
            String done = base.getPersistentDataContainer().getOrDefault(doneKey, PersistentDataType.STRING, "");
            if (done.contains(p.getUniqueId().toString())) actions = List.of();
            else base.getPersistentDataContainer().set(doneKey, PersistentDataType.STRING, done + p.getUniqueId() + ",");
        }
        sound(base.getLocation(), bh.talkSound(), 1f, 1f);
        MobDef.Anim talk = slot("talk", "talk", "speak");
        skills.runNow("click", actions, p, talk == null ? null : talk.name());
    }

    // ---- sygnały między mobami i śledzenie wzrokiem (akcje signal, watch) ----

    /** Akcja watch: głowa śledzi ten byt (np. lecącego wieśniaka-fajerwerk) i może patrzeć wysoko w górę. */
    private Entity watching;
    private int watchUntil = -1;
    private float normalMaxPitch = Float.NaN;

    void watch(Entity e, double seconds) {
        if (Float.isNaN(normalMaxPitch)) normalMaxPitch = rig.maxPitch;
        watching = e;
        watchUntil = ticks + (int) Math.round(seconds * 20);
        rig.maxPitch = 85;
    }

    private void stopWatching() {
        watching = null;
        if (!Float.isNaN(normalMaxPitch)) rig.maxPitch = normalMaxPitch;
    }

    /** Sygnał od moba obok (akcja signal) - umiejętności "signal" o tej samej nazwie. */
    void signal(String name, LivingEntity from) {
        if (alive()) skills.signal(name, from);
    }

    /**
     * Akcja firework: prawdziwy fajerwerk z gry wybucha w środku moba. Oznaczony - nikogo nie rani
     * (MainpluginsMobs.onFireworkDamage). shape: ball | ball_large | star | burst | creeper;
     * colors / fade: nazwy kolorów z gry (RED, ORANGE...) albo #rrggbb, po przecinku.
     */
    void firework(MobBehavior.Params p) {
        Location at = base.getLocation().add(0, def.hitboxHeight() * 0.6, 0);
        FireworkEffect.Type type;
        try {
            type = FireworkEffect.Type.valueOf(p.str("shape", "ball_large").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = FireworkEffect.Type.BALL_LARGE;
        }
        List<Color> colors = colors(p.str("colors", "RED,ORANGE,YELLOW"));
        if (colors.isEmpty()) colors = List.of(Color.RED);
        FireworkEffect effect = FireworkEffect.builder().with(type).withColor(colors).withFade(colors(p.str("fade", "WHITE")))
                .flicker(p.bool("flicker", true)).trail(p.bool("trail", true)).build();
        Firework fw = at.getWorld().spawn(at, Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.addEffect(effect);
            f.setFireworkMeta(meta);
            f.getPersistentDataContainer().set(new NamespacedKey(host.plugin(), "harmless"), PersistentDataType.BYTE, (byte) 1);
        });
        fw.detonate();
    }

    private static List<Color> colors(String list) {
        List<Color> out = new ArrayList<>();
        for (String raw : list.split(",")) {
            String c = raw.trim();
            if (c.isEmpty()) continue;
            try {
                if (c.startsWith("#")) out.add(Color.fromRGB(Integer.parseInt(c.substring(1), 16)));
                else if (Color.class.getField(c.toUpperCase(Locale.ROOT)).get(null) instanceof Color named) out.add(named);
            } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                // nieznany kolor - pomijamy
            }
        }
        return out;
    }

    // ---- handel (akcja open_trade, wyzwalacz "trade") ----

    /** Gracze z otwartym oknem handlu tego moba; traded = coś kupili, zanim je zamknęli. */
    private final Set<UUID> trading = new HashSet<>();
    private final Set<UUID> traded = new HashSet<>();

    /** Okno handlu jak u wieśniaka z gry - oferty z npc.trades, bez limitu użyć. */
    void openTrade(Player p) {
        List<org.bukkit.inventory.MerchantRecipe> recipes = new ArrayList<>();
        for (MobBehavior.Trade t : bh.npc().trades()) {
            Material buy = Material.matchMaterial(t.buy()), sell = Material.matchMaterial(t.sell());
            if (buy == null || sell == null || !buy.isItem() || !sell.isItem()) {
                warn("Handel: nieznany przedmiot w ofercie " + t.buy() + " -> " + t.sell());
                continue;
            }
            var r = new org.bukkit.inventory.MerchantRecipe(new ItemStack(sell, t.sellAmount()), Integer.MAX_VALUE);
            r.addIngredient(new ItemStack(buy, t.buyAmount()));
            recipes.add(r);
        }
        if (recipes.isEmpty()) return;
        var merchant = Bukkit.createMerchant(Component.text(bh.name()));
        merchant.setRecipes(recipes);
        // Najpierw okno, potem zapis: otwarcie zamyka poprzednie okno (np. drugie kliknięcie), a jego
        // zamknięcie czyściłoby gracza z listy handlujących.
        p.openMerchant(merchant, true);
        trading.add(p.getUniqueId());
        traded.remove(p.getUniqueId());
    }

    boolean isTradingWith(Player p) {
        return trading.contains(p.getUniqueId());
    }

    void onTraded(Player p) {
        if (trading.contains(p.getUniqueId())) traded.add(p.getUniqueId());
    }

    /** Okno zamknięte - jeśli gracz coś kupił, umiejętności "trade" (np. taniec z radości). */
    void onTradeClosed(Player p) {
        if (!trading.remove(p.getUniqueId())) return;
        boolean bought = traded.remove(p.getUniqueId());
        host.plugin().getLogger().info("Handel z " + id() + " zamknięty: " + p.getName() + (bought ? " kupił - start umiejętności trade" : " nic nie kupił"));
        if (bought && alive()) skills.trigger("trade", p, 0);
    }

    Entity summonCustom(String id, Location at, LivingEntity target, int life) {
        LiveMob child = host.spawn(id, at, true);
        if (child == null) return null;
        child.markSummoned(life);
        if (target != null) child.setTarget(target);
        return child.base;
    }

    void expireLater(Entity e, int life) {
        expiring.put(e, ticks + life);
    }

    boolean alive() {
        return base.isValid() && !base.isDead();
    }

    /** Stworzenie albo przywołany byt tego moba (bez łupów po śmierci). */
    boolean ownsMinion(Entity e) {
        for (Minion b : swarm) if (b.entity.equals(e)) return true;
        return expiring.containsKey(e);
    }

    /** Ciało zadało cios (zdarzenie obrażeń) - animacja ataku i umiejętności "hit". */
    void onHitTarget(Entity victim) {
        if (skills.busy()) return; // obrażenia z umiejętności to nie zwykłe uderzenie
        attackStart = ticks;
        sound(base.getLocation(), bh.attackSound(), 1f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f);
        if (victim instanceof LivingEntity le) skills.trigger("hit", le, 0);
    }

    /** Ciało oberwało: dźwięk, zapamiętanie napastnika, ucieczka (pasywny), umiejętności "hurt". */
    void onHurt(Entity damager) {
        Entity source = damager instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh : damager;
        // Trafienie widać i słychać: czerwony błysk, dźwięk (własny albo zwykły), cząsteczki.
        sound(base.getLocation(), bh.hurtSound() != null ? bh.hurtSound() : "entity.generic.hurt", 1f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f);
        flashUntil = ticks + 7;
        base.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, base.getLocation().add(0, def.hitboxHeight() * 0.6, 0), 4, def.hitboxWidth() * 0.3, 0.2, def.hitboxWidth() * 0.3, 0.1);
        lastHurt = ticks;
        if (!skills.busy() && oneShot == null) hurtStart = ticks;
        if (source instanceof Player p && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
            lastAttacker = p.getUniqueId();
            if (bh.attitude().equals("passive")) {
                fleeFrom = p.getLocation();
                fleeUntil = ticks + 80;
            } else if (target == null) {
                target = p;
            }
        }
        skills.trigger("hurt", source instanceof LivingEntity le ? le : target, 0);
    }

    void onDeath() {
        sound(base.getLocation(), bh.deathSound(), 1.5f, 1f);
        skills.trigger("death", target, 0);
    }

    /** Usuwa wszystko poza ciałem (wyładowanie chunka, wyłączenie pluginu) - ciało wróci z tagiem. */
    void detach() {
        parts.values().forEach(ItemDisplay::remove);
        parts.clear();
        hitboxes.values().forEach(Interaction::remove);
        hitboxes.clear();
        hitParts.clear();
        swarm.forEach(b -> b.entity.remove());
        swarm.clear();
        expiring.keySet().forEach(Entity::remove);
        expiring.clear();
        if (nameplate != null) nameplate.remove();
        nameplate = null;
        hideBossBar();
        skills.cancel();
        if (base.isValid() && !base.hasAI()) base.setAI(true);
    }

    void remove() {
        detach();
        if (base.isValid()) base.remove();
    }

    MobDef.Anim find(String... names) {
        for (String n : names) {
            if (n == null) continue;
            for (MobDef.Anim a : def.animations().values()) if (a.name().equalsIgnoreCase(n) || a.id().equals(n)) return a;
        }
        return null;
    }

    /**
     * Animacja sytuacji (idle, walk, run, fly, swim, attack, hurt, death): wybrana albo rozpoznana w
     * aplikacji (display.json). Stare pliki bez tego - po nazwach jak dawniej.
     */
    MobDef.Anim slot(String slot, String... legacyNames) {
        if (!def.slots().isEmpty()) {
            String name = def.slots().get(slot);
            return name == null ? null : find(name);
        }
        return find(legacyNames.length == 0 ? new String[]{slot} : legacyNames);
    }

    /** Animacje grane przez plugin w konkretnych chwilach - nie zapętlają się same. */
    private boolean special(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.equals("idle") || n.equals("walk") || n.equals("attack") || n.equals("jump")) return true;
        for (String s : def.slots().values()) if (s.equalsIgnoreCase(n)) return true;
        // Lista w zachowaniu: tylko te pętle grają stale (np. orbita kryształów); bez listy - wszystkie
        // pętle poza stanami (bieg, lot, sen...), które plugin włącza sam albo wcale.
        if (!bh.backgroundAnimations().isEmpty()) return bh.backgroundAnimations().stream().noneMatch(x -> x.equalsIgnoreCase(n));
        if (BACKGROUND_EXCLUDE.matcher(n).matches()) return true;
        for (String x : bh.randomAnimations()) if (x.equalsIgnoreCase(n)) return true;
        for (MobBehavior.Phase p : bh.phases()) if (n.equalsIgnoreCase(p.animation())) return true;
        for (MobBehavior.Skill s : bh.skills()) {
            if (n.equalsIgnoreCase(s.animation())) return true;
            for (MobBehavior.Action a : s.actions()) if (a.type().equals("stun") && n.equalsIgnoreCase(a.p().str("animation", ""))) return true;
        }
        return false;
    }

    // ---- pomocnicze dla umiejętności ----

    /** Punkt modelu (piksele modelu: y w dół, ziemia = 24, przód = -z) w świecie - np. środek kółka przy ławce w modelu. */
    Location modelPoint(float x, float y, float z) {
        float yaw = Float.isNaN(modelYaw) ? base.getLocation().getYaw() : modelYaw;
        Vector3f p = MobRig.modelToWorld(yaw).transformPosition(new Vector3f(x / 16f, y / 16f, z / 16f));
        return base.getLocation().add(p.x, p.y, p.z);
    }

    /** Położenie kości w świecie (np. ręka, z której leci pocisk). */
    Location bonePos(String bone) {
        Location loc = base.getLocation();
        Matrix4f m = null;
        if (bone != null) {
            m = lastBones.get(bone);
            if (m == null) for (MobDef.Bone b : def.bones()) if (b.name().equalsIgnoreCase(bone)) m = lastBones.get(b.id());
        }
        if (m == null) return loc.add(0, def.hitboxHeight() * 0.6, 0);
        Vector3f p = MobPose.displayMatrix(Float.isNaN(modelYaw) ? 0 : modelYaw, m, 1).getTranslation(new Vector3f());
        return loc.add(p.x, p.y, p.z);
    }

    /** Skąd lecą pociski: wybrana część, inaczej pierwsza ręka/łapa (rola z aplikacji), inaczej środek ciała. */
    Location shootOrigin(String bone) {
        if (bone != null) return bonePos(bone);
        List<String> arms = rig.roots("arm");
        return bonePos(arms.isEmpty() ? null : arms.get(0));
    }

    void face(Location to) {
        if (bh.fixedFacing()) return;
        Vector d = to.toVector().subtract(base.getLocation().toVector());
        if (d.lengthSquared() < 1e-4) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        base.setRotation(yaw, 0);
        base.setBodyYaw(yaw);
    }

    void sound(Location at, String key, float volume, float pitch) {
        Sound s = SkillRunner.sound(key);
        if (s != null) at.getWorld().playSound(at, s, volume, pitch);
    }

    void teleportBody(Location at) {
        base.teleport(at, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    void dust(Location at, double size) {
        World w = at.getWorld();
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, (int) (16 * size), size, 0.3, size, 0.02);
        w.spawnParticle(Particle.CLOUD, at, (int) (32 * size), size * 1.2, 0.2, size * 1.2, 0.15);
        Material ground = at.clone().subtract(0, 0.5, 0).getBlock().getType();
        if (ground.isSolid()) w.spawnParticle(Particle.BLOCK, at, (int) (60 * size), size * 1.2, 0.2, size * 1.2, 0.1, ground.createBlockData());
    }

    void releaseSwarm(MobBehavior.Params p, LivingEntity to) {
        Location from = bonePos(p.str("bone", "").isBlank() ? null : p.str("bone", ""));
        int count = (int) Math.max(1, Math.min(20, p.num("count", 3)));
        int life = (int) (p.num("lifetime", 12) * 20);
        double damage = p.num("damage", 2) * damageMultiplier;
        NamespacedKey k = NamespacedKey.fromString(p.str("entity", "parrot").toLowerCase(Locale.ROOT));
        EntityType type = k == null ? null : Registry.ENTITY_TYPE.get(k);
        if (type == null || type.getEntityClass() == null || !Mob.class.isAssignableFrom(type.getEntityClass())) type = EntityType.PARROT;
        for (int i = 0; i < count; i++) {
            Location at = from.clone().add(ThreadLocalRandom.current().nextDouble(-0.4, 0.4), 0, ThreadLocalRandom.current().nextDouble(-0.4, 0.4));
            Mob e = (Mob) at.getWorld().spawnEntity(at, type);
            e.setPersistent(false);
            e.setAI(false);
            if (e instanceof Parrot parrot) parrot.setVariant(Parrot.Variant.GRAY);
            swarm.add(new Minion(e, to, ticks + life, damage));
            base.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, 3, 0.2, 0.2, 0.2, 0.01);
        }
    }

    private void tickSwarm() {
        Iterator<Minion> it = swarm.iterator();
        while (it.hasNext()) {
            Minion b = it.next();
            LivingEntity p = b.entity;
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
                // Leci na cel lekko falując (jak trzepoczący ptak).
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
        for (Iterator<Map.Entry<Entity, Integer>> ex = expiring.entrySet().iterator(); ex.hasNext(); ) {
            Map.Entry<Entity, Integer> e = ex.next();
            if (!e.getKey().isValid()) {
                ex.remove();
            } else if (ticks >= e.getValue()) {
                e.getKey().getWorld().spawnParticle(Particle.POOF, e.getKey().getLocation().add(0, 0.5, 0), 8, 0.3, 0.3, 0.3, 0.02);
                e.getKey().remove();
                ex.remove();
            }
        }
    }

    // ---- AI ----

    private static boolean valid(LivingEntity e) {
        if (e == null || !e.isValid() || e.isDead()) return false;
        return !(e instanceof Player p) || (p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR);
    }

    /** Cel, pościg, walka i wędrówka - zamiast zwykłych celów moba z gry. */
    private void brain() {
        Location loc = base.getLocation();
        double follow = bh.followRange();
        if (target != null && (!valid(target) || target.getWorld() != base.getWorld() || target.getLocation().distanceSquared(loc) > follow * follow * 2.25)) {
            target = null;
        }
        boolean npc = bh.npc().enabled();
        if (npc) {
            target = null;
            fleeUntil = -1;
        }
        switch (npc ? "npc" : bh.attitude()) {
            case "hostile" -> {
                if (target == null && ticks % 10 == 0) target = nearestPlayer(loc, follow, true);
            }
            case "neutral" -> {
                if (target == null && lastAttacker != null && ticks - lastHurt < 20 * 30) {
                    Player p = Bukkit.getPlayer(lastAttacker);
                    if (valid(p) && p.getWorld() == base.getWorld() && p.getLocation().distanceSquared(loc) <= follow * follow) target = p;
                }
            }
            default -> target = null;
        }
        if (base.getTarget() != target) base.setTarget(target);

        var pf = base.getPathfinder();
        boolean stationary = bh.movement().equals("stationary");
        if (fleeUntil > ticks && fleeFrom != null) {
            if (ticks >= nextRepath && !stationary) {
                Vector away = loc.toVector().subtract(fleeFrom.toVector()).setY(0);
                if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                Location dest = wanderSpot(loc.clone().add(away.normalize().multiply(8)), 3);
                if (dest != null) pf.moveTo(dest, 1.6);
                nextRepath = ticks + 10;
            }
            return;
        }
        if (target == null) {
            double radius = npc ? bh.npc().homeRadius() : 8;
            if (!stationary && radius > 0 && ticks >= nextWander) {
                Location dest = wanderSpot(npc && home != null ? home : loc, (int) Math.max(1, radius));
                if (dest != null) pf.moveTo(dest, 0.7);
                nextWander = ticks + 100 + ThreadLocalRandom.current().nextInt(160);
            }
            return;
        }
        double dist = target.getLocation().distance(loc);
        boolean sees = base.hasLineOfSight(target);
        if (bh.attack().equals("ranged")) {
            double range = bh.ranged().num("range", 16);
            if (!stationary) {
                if (dist > range * 0.85 || !sees) {
                    if (ticks >= nextRepath) {
                        pf.moveTo(target, 1.0);
                        nextRepath = ticks + 10;
                    }
                } else if (dist < range * 0.35) {
                    if (ticks >= nextRepath) {
                        Vector away = loc.toVector().subtract(target.getLocation().toVector()).setY(0);
                        if (away.lengthSquared() > 0.01) {
                            Location dest = wanderSpot(loc.clone().add(away.normalize().multiply(5)), 2);
                            if (dest != null) pf.moveTo(dest, 1.1);
                        }
                        nextRepath = ticks + 15;
                    }
                } else {
                    pf.stopPathfinding();
                    face(target.getLocation());
                }
            } else {
                face(target.getLocation());
            }
            if (sees && dist <= range && ticks >= attackReady) {
                skills.shoot(bh.ranged(), target);
                sound(base.getLocation(), bh.shootSound(), 1f, 1f);
                attackStart = ticks;
                attackReady = ticks + (int) (bh.ranged().num("cooldown", 2) * 20);
            }
            return;
        }
        if (stationary) face(target.getLocation());
        else if (ticks >= nextRepath) {
            pf.moveTo(target, 1.0);
            nextRepath = ticks + (dist < 6 ? 5 : 10);
        }
        if (bh.attack().equals("melee") && ticks >= attackReady && sees && inReach(target)) {
            face(target.getLocation());
            base.attack(target);
            attackReady = ticks + (int) (bh.attackCooldown() * 20);
        }
    }

    private boolean inReach(LivingEntity t) {
        Location a = base.getLocation(), b = t.getLocation();
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        double reach = base.getWidth() / 2 + t.getWidth() / 2 + 1.0;
        return dx * dx + dz * dz <= reach * reach && b.getY() < a.getY() + base.getHeight() + 1 && b.getY() + t.getHeight() > a.getY() - 1;
    }

    private Player nearestPlayer(Location loc, double range, boolean needSight) {
        Player best = null;
        double bestD = range * range;
        for (Player p : loc.getNearbyPlayers(range)) {
            if (!valid(p)) continue;
            double d = p.getLocation().distanceSquared(loc);
            if (d < bestD && (!needSight || base.hasLineOfSight(p))) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Losowe miejsce do wędrówki: na ziemi (chodzi), w powietrzu nad ziemią (lata), w wodzie (pływa). */
    private Location wanderSpot(Location around, int radius) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        World w = around.getWorld();
        for (int i = 0; i < 6; i++) {
            Location c = around.clone().add(r.nextDouble(-radius, radius), 0, r.nextDouble(-radius, radius));
            switch (bh.movement()) {
                case "fly" -> {
                    Location g = SkillRunner.ground(c, 12);
                    if (g == null) continue;
                    Location air = g.add(0, 2 + r.nextInt(5), 0);
                    if (air.getBlock().isPassable()) return air;
                }
                case "swim" -> {
                    Location water = c.add(0, r.nextInt(-3, 4), 0);
                    Block b = w.getBlockAt(water);
                    if (b.getType() == Material.WATER) return water;
                }
                default -> {
                    Location g = SkillRunner.ground(c, 4);
                    if (g != null) return g;
                }
            }
        }
        return null;
    }

    // ---- wygląd i hitboxy ----

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
    private static final String BODY = "__body__";
    /** Części w świecie z ostatniego ticka: kość -> {minX, minY, minZ, maxX, maxY, maxZ} (bezwzględnie). */
    private final Map<String, double[]> partBoxes = new HashMap<>();
    /** Mnożnik słabego punktu dla ciosu, który właśnie idzie (MainpluginsMobs.onHurt go zużywa). */
    private double pendingMult = 1;

    double takePendingMult() {
        double m = pendingMult;
        pendingMult = 1;
        return m;
    }

    /**
     * Cios w obszar trafienia moba = prawdziwy atak gracza w ciało (ostrość, odrzut, krytyk, zamach,
     * zużycie broni jak zawsze). Którą część trafił - promień z oka gracza do części z bieżącej pozy;
     * słaby punkt mnoży obrażenia.
     */
    void hitPart(Player p, String ignored) {
        if (!alive()) return;
        Location eye = p.getEyeLocation();
        Vector d = eye.getDirection();
        String hitBone = null;
        double best = Double.MAX_VALUE;
        for (Map.Entry<String, double[]> e : partBoxes.entrySet()) {
            double t = rayBox(eye, d, e.getValue());
            if (t >= 0 && t < best) {
                best = t;
                hitBone = e.getKey();
            }
        }
        double mult = 1;
        if (hitBone != null && bh.partHitboxes()) {
            String bone = hitBone;
            String name = def.bones().stream().filter(b -> b.id().equals(bone)).map(MobDef.Bone::name).findFirst().orElse(bone);
            Double weak = bh.weakPoints().get(name);
            if (weak == null) weak = bh.weakPoints().get(bone);
            if (weak != null) mult = weak;
            else if ("head".equals(def.role(bone)) || name.equalsIgnoreCase("head")) mult = 1.5;
        }
        pendingMult = mult;
        double before = base.getHealth();
        boolean ready = base.getNoDamageTicks() <= base.getMaximumNoDamageTicks() / 2;
        p.attack(base);
        if (ready && alive() && base.getHealth() >= before && pendingMult == mult) {
            // Zapas: atak gracza nie przeszedł (np. inna wersja serwera) - obrażenia jak zwykły cios.
            var attr = p.getAttribute(Attribute.ATTACK_DAMAGE);
            float charge = p.getAttackCooldown();
            double dmg = (attr != null ? attr.getValue() : 1) * (0.2 + charge * charge * 0.8);
            base.damage(dmg, p);
            p.resetCooldown();
        }
        pendingMult = 1;
        if (best < Double.MAX_VALUE) {
            Location at = eye.clone().add(d.clone().multiply(best));
            at.getWorld().spawnParticle(mult > 1 ? Particle.CRIT : Particle.DAMAGE_INDICATOR, at, mult > 1 ? 10 : 3, 0.15, 0.15, 0.15, 0.1);
        }
    }

    /** Odległość promienia do prostopadłościanu (metoda płyt) albo -1, gdy go nie przecina. */
    private static double rayBox(Location o, Vector d, double[] b) {
        double tMin = 0, tMax = 8;
        double[] oo = {o.getX(), o.getY(), o.getZ()}, dd = {d.getX(), d.getY(), d.getZ()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(dd[i]) < 1e-9) {
                if (oo[i] < b[i] || oo[i] > b[i + 3]) return -1;
                continue;
            }
            double t1 = (b[i] - oo[i]) / dd[i], t2 = (b[i + 3] - oo[i]) / dd[i];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
            if (tMin > tMax) return -1;
        }
        return tMin;
    }

    /** Jeden obszar trafienia na cały model (ciało moba z gry bywa mniejsze niż model - np. szeroka żaba). */
    private void spawnHitboxes(Location at) {
        Interaction i = at.getWorld().spawn(at, Interaction.class, e -> {
            e.setPersistent(false);
            e.setResponsive(false);
            e.setInteractionWidth((float) Math.max(0.3, def.hitboxWidth()));
            e.setInteractionHeight((float) Math.max(0.3, def.hitboxHeight()));
        });
        hitboxes.put(BODY, i);
        hitParts.put(i.getUniqueId(), BODY);
    }

    /** Co tick: części w świecie (do słabych punktów) i obszar trafienia obejmujący wszystkie widoczne części. */
    private void moveHitboxes(Location loc, float yaw, Map<String, Matrix4f> bones) {
        Interaction body = hitboxes.get(BODY);
        if (body == null) return;
        Matrix4f toWorld = MobRig.modelToWorld(yaw);
        partBoxes.clear();
        double uMinX = 1e9, uMinY = 1e9, uMinZ = 1e9, uMaxX = -1e9, uMaxY = -1e9, uMaxZ = -1e9;
        for (MobDef.Bone b : def.bones()) {
            Matrix4f m = bones.get(b.id());
            if (b.boxes().isEmpty() || !b.visible() || m == null) continue;
            Matrix4f w = new Matrix4f(toWorld).mul(m);
            float minX = 1e9f, minY = 1e9f, minZ = 1e9f, maxX = -1e9f, maxY = -1e9f, maxZ = -1e9f;
            for (float[] x : b.boxes())
                for (int c = 0; c < 8; c++) {
                    Vector3f p = w.transformPosition(new Vector3f((x[0] + ((c & 1) != 0 ? x[3] : 0)) / 16f, (x[1] + ((c & 2) != 0 ? x[4] : 0)) / 16f, (x[2] + ((c & 4) != 0 ? x[5] : 0)) / 16f));
                    minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                    minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
                    minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
                }
            partBoxes.put(b.id(), new double[]{loc.getX() + minX, loc.getY() + minY, loc.getZ() + minZ, loc.getX() + maxX, loc.getY() + maxY, loc.getZ() + maxZ});
            uMinX = Math.min(uMinX, minX); uMaxX = Math.max(uMaxX, maxX);
            uMinY = Math.min(uMinY, minY); uMaxY = Math.max(uMaxY, maxY);
            uMinZ = Math.min(uMinZ, minZ); uMaxZ = Math.max(uMaxZ, maxZ);
        }
        if (partBoxes.isEmpty()) return;
        // Obszar trafienia w grze jest kwadratowy w poziomie - szerokość = dłuższy bok modelu.
        body.setInteractionWidth((float) Math.max(0.3, Math.min(12, Math.max(uMaxX - uMinX, uMaxZ - uMinZ) + 0.1)));
        body.setInteractionHeight((float) Math.max(0.3, Math.min(12, uMaxY - uMinY + 0.1)));
        body.teleport(loc.clone().add((uMinX + uMaxX) / 2, uMinY - 0.05, (uMinZ + uMaxZ) / 2));
    }

    /**
     * Przedmiot części. hurt = czerwona barwa (ściany modelu mają tintindex 0, definicja przedmiotu
     * barwę "dye") - tak mob błyska przy trafieniu jak zwykłe moby. Starsze paczki: bez barwy, nic się nie psuje.
     */
    private static ItemStack partStack(String itemKey, boolean hurt) {
        ItemStack stack = new ItemStack(Material.PAPER);
        String[] key = itemKey.split(":", 2);
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(key[0], key[1]));
        if (hurt) stack.setData(DataComponentTypes.DYED_COLOR, io.papermc.paper.datacomponent.item.DyedItemColor.dyedItemColor(Color.fromRGB(255, 90, 90)));
        return stack;
    }

    /** Chwila w każdej animacji z poprzedniego ticka - dźwięk gra, gdy animacja minie jego moment. */
    private final Map<String, Float> soundClock = new HashMap<>();

    private void animationSounds(List<MobPose.Playing> playing) {
        Map<String, Float> now = new HashMap<>();
        for (MobPose.Playing p : playing) {
            MobDef.Anim a = p.anim();
            if (a.sounds().isEmpty() || p.weight() < 0.35f) continue;
            float t = p.time();
            now.put(a.id(), t);
            Float prev = soundClock.get(a.id());
            for (MobDef.SoundKey k : a.sounds()) {
                // prev -> t, z zawinięciem pętli (t mniejsze niż prev = animacja zaczęła się od nowa)
                boolean hit = prev == null ? k.t() <= t && t - k.t() < 0.06f
                        : t >= prev ? k.t() > prev && k.t() <= t : k.t() > prev || k.t() <= t;
                if (hit) sound(base.getLocation(), k.sound(), k.volume(), k.pitch());
            }
        }
        soundClock.clear();
        soundClock.putAll(now);
    }

    /** Kroki: co ~1,3 bloku drogi - własny dźwięk albo, jak w grze, dźwięk bloku pod stopami. */
    private float stepDistance;

    private void tickSteps(float net) {
        if (!walking || !bh.movement().equals("walk") || !base.isOnGround()) return;
        stepDistance += net;
        if (stepDistance < 1.3f * Math.max(0.6f, (float) def.hitboxHeight() / 1.8f)) return;
        stepDistance = 0;
        if (bh.stepSound() != null) {
            sound(base.getLocation(), bh.stepSound(), 0.6f, 1f);
            return;
        }
        Block under = base.getLocation().subtract(0, 0.2, 0).getBlock();
        if (!under.getType().isSolid()) return;
        var group = under.getBlockData().getSoundGroup();
        base.getWorld().playSound(base.getLocation(), group.getStepSound(), group.getVolume() * 0.4f, group.getPitch());
    }

    /** Czerwony błysk: przez kilka ticków po trafieniu. */
    private int flashUntil = -1;
    private boolean flashing;

    private void tickFlash() {
        boolean want = ticks < flashUntil;
        if (want == flashing) return;
        flashing = want;
        for (MobDef.Bone b : def.bones()) {
            ItemDisplay d = parts.get(b.id());
            if (d != null) d.setItemStack(partStack(b.variantItems().getOrDefault(shownVariant, b.item()), flashing));
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
            d.setItemStack(partStack(b.variantItems().getOrDefault(variant, b.item()), flashing));
            d.setBrightness(b.glow() && (v == null || !v.noGlow()) ? new Display.Brightness(15, 15) : null);
        }
    }

    /** Wejście w kolejną fazę: mocniejsze ciosy, wygląd, animacja wejścia i umiejętności "phase". */
    private void enterPhase(boolean announce) {
        MobBehavior.Phase ph = bh.phases().get(phaseIndex);
        phaseIndex++;
        damageMultiplier *= ph.damageMultiplier();
        AttributeInstance dmg = base.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null) dmg.setBaseValue(bh.damage() * damageMultiplier);
        if (ph.variant() != null) variant = ph.variant();
        if (!announce) return;
        MobDef.Anim a = find(ph.animation());
        if (a != null) {
            skills.cancel();
            oneShot = a;
            oneShotStart = ticks;
            rooted = true;
        }
        sound(base.getLocation(), ph.sound(), 2f, 0.8f);
        skills.trigger("phase", target, phaseIndex);
    }

    /** Fazy, losowe zachowania, odgłosy. */
    private void tickMood() {
        while (phaseIndex < bh.phases().size() && healthFraction() < bh.phases().get(phaseIndex).below()) enterPhase(true);
        List<String> randoms = bh.randomAnimations();
        if (oneShot == null && !skills.busy() && !walking && !randoms.isEmpty() && ticks >= nextRandom && target == null) {
            MobDef.Anim a = find(randoms.get(ThreadLocalRandom.current().nextInt(randoms.size())));
            if (a != null) {
                oneShot = a;
                oneShotStart = ticks;
            }
            nextRandom = ticks + (int) (bh.randomInterval() * 20 * (0.6 + ThreadLocalRandom.current().nextDouble() * 0.8));
        }
        if (oneShot != null && (ticks - oneShotStart) / 20f >= oneShot.length()) {
            oneShot = null;
            rooted = false;
        }
        if (bh.npc().enabled() && oneShot == null && !skills.busy() && ticks % 10 == 0) {
            MobDef.Anim wave = slot("wave", "wave", "greet");
            if (wave != null) {
                for (Player p : base.getLocation().getNearbyPlayers(5)) {
                    Integer w = waved.get(p.getUniqueId());
                    if (w != null && ticks - w < 20 * 30) continue;
                    waved.put(p.getUniqueId(), ticks);
                    face(p.getLocation());
                    oneShot = wave;
                    oneShotStart = ticks;
                    break;
                }
            }
        }
        if (bh.ambientSound() != null && ticks >= nextAmbient) {
            sound(base.getLocation(), bh.ambientSound(), 1f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.2f);
            nextAmbient = ticks + 120 + ThreadLocalRandom.current().nextInt(200);
        }
        if (shielded() && ticks % 4 == 0) {
            Location c = base.getLocation().add(0, def.hitboxHeight() / 2, 0);
            base.getWorld().spawnParticle(Particle.END_ROD, c, 3, def.hitboxWidth() * 0.6, def.hitboxHeight() * 0.4, def.hitboxWidth() * 0.6, 0.01);
        }
    }

    // ---- nazwa i pasek bossa ----

    private void tickNameplate(Location loc) {
        if (bh.nameplate().equals("never")) return;
        if (nameplate == null || !nameplate.isValid()) {
            nameplate = loc.getWorld().spawn(loc, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
                t.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
                t.setTeleportDuration(2);
                t.setViewRange(bh.nameplate().equals("always") ? 1f : 0.2f);
            });
            shownPlate = "";
        }
        nameplate.teleport(loc.clone().add(0, def.hitboxHeight() + 0.35, 0));
        int hp = (int) Math.ceil(base.getHealth());
        String key = hp + "/" + (int) maxHealth() + "/" + bh.name();
        if (key.equals(shownPlate)) return;
        shownPlate = key;
        Component text = Component.text(bh.name(), NamedTextColor.WHITE).decoration(TextDecoration.BOLD, bh.bossBar().enabled());
        if (bh.healthBar()) {
            int filled = (int) Math.round(healthFraction() * 20);
            text = text.append(Component.newline())
                    .append(Component.text("|".repeat(filled), healthFraction() > 0.5 ? NamedTextColor.GREEN : healthFraction() > 0.25 ? NamedTextColor.GOLD : NamedTextColor.RED))
                    .append(Component.text("|".repeat(20 - filled), NamedTextColor.DARK_GRAY))
                    .append(Component.text(" " + hp, NamedTextColor.GRAY));
        }
        nameplate.text(text);
    }

    private void tickBossBar(Location loc) {
        MobBehavior.BossBar cfg = bh.bossBar();
        if (!cfg.enabled() || ticks % 5 != 0) return;
        if (bossBar == null) {
            BossBar.Color color;
            BossBar.Overlay overlay;
            try {
                color = BossBar.Color.valueOf(cfg.color().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                color = BossBar.Color.RED;
            }
            try {
                overlay = BossBar.Overlay.valueOf(cfg.style().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                overlay = BossBar.Overlay.PROGRESS;
            }
            bossBar = BossBar.bossBar(Component.text(bh.name()), 1f, color, overlay);
        }
        bossBar.progress((float) healthFraction());
        double r2 = cfg.range() * cfg.range();
        Set<UUID> now = new HashSet<>();
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) > r2) continue;
            now.add(p.getUniqueId());
            if (barViewers.add(p.getUniqueId())) p.showBossBar(bossBar);
        }
        for (Iterator<UUID> it = barViewers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (now.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bossBar);
            it.remove();
        }
    }

    private void hideBossBar() {
        if (bossBar == null) return;
        for (UUID id : barViewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bossBar);
        }
        barViewers.clear();
    }

    // ---- tick ----

    void tick() {
        ticks++;
        if (deathStart >= 0) {
            tickDeath();
            return;
        }
        if (expireAt >= 0 && ticks >= expireAt) {
            base.getWorld().spawnParticle(Particle.POOF, base.getLocation().add(0, def.hitboxHeight() / 2, 0), 15, 0.4, 0.4, 0.4, 0.02);
            base.remove();
            return;
        }
        tickMood();
        if (base.hasAI()) brain();
        boolean canStartMain = oneShot == null && (!bh.movement().equals("walk") || base.isOnGround());
        Object[] act = skills.tick(ticks, target, canStartMain);
        boolean wantAI = !skills.busy() && !rooted;
        if (base.hasAI() != wantAI) {
            if (!wantAI) base.getPathfinder().stopPathfinding();
            base.setAI(wantAI);
            trail.clear();
        }
        tickSwarm();
        Location loc = base.getLocation();
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
        boolean busy = skills.busy();
        walking = !busy && oneShot == null && net > (walking ? 0.012f : 0.03f);
        if (busy) walkWeight = 0;
        walkWeight = Math.max(0, Math.min(1, walkWeight + (walking ? 0.12f : -0.1f)));
        MobDef.Anim walk = bh.movement().equals("fly") ? slot("fly", "fly", "walk", "move") : bh.movement().equals("swim") ? slot("swim", "swim", "walk", "move") : null;
        if (walk == null) walk = slot("walk", "walk", "move");
        MobDef.Anim idle = slot("idle"), run = slot("run");
        // bieg: szybki marsz przechodzi płynnie w animację biegu
        runWeight = Math.max(0, Math.min(1, runWeight + (walking && run != null && net > 0.16f ? 0.1f : -0.1f)));
        if (walking) walkTime += 0.05f * Math.max(0.5f, Math.min(2f, net / (runWeight > 0.5f ? 0.2f : 0.08f)));
        if (walk != null) playing.add(new MobPose.Playing(walk, walkTime % Math.max(0.05f, walk.length()), walkWeight * (1 - runWeight)));
        if (run != null && runWeight > 0) playing.add(new MobPose.Playing(run, walkTime % Math.max(0.05f, run.length()), walkWeight * runWeight));
        // W trakcie umiejętności spoczynek nie gra - ruchy (np. machanie rękami) gryzłyby się z nią.
        if (idle != null && !busy) playing.add(new MobPose.Playing(idle, sec % Math.max(0.05f, idle.length()), (1 - walkWeight) * (oneShot != null ? 0.2f : 1f)));
        for (MobDef.Anim a : def.animations().values()) {
            if (a != walk && !special(a.name()) && a.loop()) playing.add(new MobPose.Playing(a, sec % Math.max(0.05f, a.length()), 1));
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
        MobDef.Anim hurt = slot("hurt", "hurt");
        if (hurt != null && (ticks - hurtStart) / 20f < hurt.length()) playing.add(new MobPose.Playing(hurt, (ticks - hurtStart) / 20f, 0.8f));
        MobDef.Anim attack = slot("attack");
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
        tickFlash();
        tickSteps(net);
        animationSounds(playing);

        // Pierwsze 3 ticki części są niewidoczne i bez wygładzania; potem pokazują się od razu na miejscu.
        if (ticks == 3) parts.values().forEach(d -> {
            d.setInterpolationDuration(3);
            d.setViewRange(2f);
        });
        float body = base.getBodyYaw();
        if (bh.fixedFacing()) {
            // Scena (np. siłacz z ławką w modelu): kierunek z chwili postawienia, ciało może się kręcić, model nie.
            if (Float.isNaN(fixedYaw)) fixedYaw = base.getLocation().getYaw();
            body = fixedYaw;
            modelYaw = fixedYaw;
        }
        if (Float.isNaN(modelYaw)) modelYaw = body;
        float diff = ((body - modelYaw) % 360 + 540) % 360 - 180;
        if (Math.abs(diff) > 3) modelYaw += diff * (walking || busy ? 0.5f : 0.3f);
        float yaw = modelYaw;

        // Ruch żywy: głowa do celu, stopy na terenie, sprężyny (ogon, uszy, peleryna) z prawdziwego ruchu.
        Map<String, MobPose.Offset> offsets = MobPose.offsets(playing);
        Map<String, Matrix4f> animated = MobPose.boneMatrices(def, offsets);
        Entity lookAt = target;
        if (watching != null) {
            if (ticks < watchUntil && watching.isValid()) lookAt = watching;
            else stopWatching();
        }
        if (lookAt == null) lookAt = nearestPlayer(loc, 12, false);
        Vector3f lookTarget = null;
        if (lookAt instanceof LivingEntity le) {
            Location eye = le.getEyeLocation();
            lookTarget = new Vector3f((float) (eye.getX() - loc.getX()), (float) (eye.getY() - loc.getY()), (float) (eye.getZ() - loc.getZ()));
        }
        rig.applyLook(offsets, animated, yaw, lookTarget);
        var world = loc.getWorld();
        rig.applyFeet(offsets, animated, yaw, loc.getX(), loc.getY(), loc.getZ(), base.isOnGround() && !busy && bh.movement().equals("walk"), (x, yFrom, z) -> {
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
            Transformation tr = new Transformation(m.getTranslation(new Vector3f()), MobPose.rotationOf(m),
                    m.getScale(new Vector3f()), new Quaternionf());
            // Tylko gdy ułożenie się zmieniło: sam sygnał „zacznij wygładzanie od nowa” przy niezmienionym ułożeniu
            // cofa część w grze do początku poprzedniego ruchu - co tick, więc stojąca część drży.
            if (tr.equals(lastSent.put(b.id(), tr))) continue;
            d.setInterpolationDelay(0);
            d.setTransformation(tr);
        }
        effects(loc, yaw, bones, current, currentT, walk, idle, sec);
        tickNameplate(loc);
        tickBossBar(loc);
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
        swarm.forEach(b -> b.entity.remove());
        swarm.clear();
        if (nameplate != null) nameplate.remove();
        nameplate = null;
        hideBossBar();
        skills.cancel();
        if (!bh.shatter() && slot("death") == null) finished = true;
    }

    private void tickDeath() {
        MobDef.Anim death = slot("death");
        float t = (ticks - deathStart) / 20f;
        float len = death == null ? 0 : Math.min(4f, death.length());
        if (t < len) {
            // poza z animacji śmierci (bez chodu, bez patrzenia)
            Map<String, Matrix4f> bones = MobPose.boneMatrices(def, MobPose.offsets(List.of(new MobPose.Playing(death, t, 1))));
            lastBones = bones;
            float yaw = Float.isNaN(modelYaw) ? 0 : modelYaw;
            for (MobDef.Bone b : def.bones()) {
                ItemDisplay d = parts.get(b.id());
                if (d == null) continue;
                Matrix4f m = MobPose.displayMatrix(yaw, bones.get(b.id()), b.modelScale());
                Transformation tr = new Transformation(m.getTranslation(new Vector3f()), MobPose.rotationOf(m), m.getScale(new Vector3f()), new Quaternionf());
                if (tr.equals(lastSent.put(b.id(), tr))) continue;
                d.setInterpolationDelay(0);
                d.setTransformation(tr);
            }
            return;
        }
        if (!bh.shatter()) {
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
