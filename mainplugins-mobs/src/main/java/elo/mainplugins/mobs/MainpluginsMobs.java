package elo.mainplugins.mobs;

import com.google.gson.JsonObject;
import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.license.LicenseGuard;
import elo.mainplugins.mobs.model.MobBehavior;
import elo.mainplugins.mobs.model.MobDef;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Moby z Kreatora mobów (Texturepack Creator w aplikacji, dodatek Ultimate). Model i zachowanie
 * (zakładka Behavior) czyta z paczki zasobów serwera - z tego samego pliku gracz dostaje modele
 * części. Moby bez zachowania w pliku biorą je ze starego config.yml (moby.&lt;id&gt;).
 * /@mob spawn &lt;id&gt; [ilość] | list | killall | reload
 */
public final class MainpluginsMobs extends JavaPlugin implements Listener, TabExecutor, MobHost {

    private static final String PREFIX = "assets/mainplugins/mobs/";

    private final Map<String, MobDef> mobs = new LinkedHashMap<>();
    private final Map<String, MobBehavior> behaviors = new HashMap<>();
    /** Żywe moby po ciele. */
    private final Map<UUID, LiveMob> live = new LinkedHashMap<>();
    private NamespacedKey mobKey;
    private NamespacedKey damageKey;
    private LangService lang;
    private String lastProblem = null;

    @Override
    public void onEnable() {
        // Plugin płatny (Ultimate) - patrz javadoc LicenseService oraz license-server/README.md.
        if (!CoreAPI.getLicenseService().isLicensed("mobs")
                || !LicenseGuard.enable(this, "mobs", () -> CoreAPI.getLicenseService().licenseProof("mobs"))) {
            getLogger().severe("Brak ważnej licencji dla mainplugins-mobs - plugin zostanie wyłączony.");
            getLogger().severe("Moby są częścią Ultimate - skonfiguruj klucz w license.yml (folder danych MainpluginsCore) i zrestartuj serwer.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        mobKey = new NamespacedKey(this, "mob");
        damageKey = new NamespacedKey(this, "damage");
        lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        saveDefaultConfig();
        reload();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("@mob") != null) {
            getCommand("@mob").setExecutor(this);
            getCommand("@mob").setTabCompleter(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
    }

    @Override
    public void onDisable() {
        // Ciała zostają w świecie (tag z id) - po starcie dostaną części z powrotem.
        live.values().forEach(m -> {
            if (m.dying()) m.remove();
            else m.detach();
        });
        live.clear();
    }

    private void tickAll() {
        for (var it = live.values().iterator(); it.hasNext(); ) {
            LiveMob m = it.next();
            // zabity: części zostają na animację śmierci i rozpad, potem znikają
            if (!m.alive() && !m.dying()) m.startDeath();
            if (m.finished()) {
                m.remove();
                it.remove();
                continue;
            }
            m.tick();
        }
    }

    // ---- wczytywanie ----

    private void reload() {
        reloadConfig();
        mobs.clear();
        behaviors.clear();
        lastProblem = null;
        // Zachowanie z edytora "Custom mobs" w aplikacji (plugins/MainpluginsMobs/mobs.yml).
        File mobsFile = new File(getDataFolder(), "mobs.yml");
        ConfigurationSection edited = mobsFile.exists() ? YamlConfiguration.loadConfiguration(mobsFile).getConfigurationSection("mobs") : null;
        File pack = new File(getDataFolder().getParentFile(), "MainpluginsCore/resourcepack/pack.zip");
        if (!pack.exists()) {
            lastProblem = "Nie ma paczki zasobów serwera (" + pack.getPath() + ") - wyślij paczkę z aplikacji (Texturepack Creator).";
            getLogger().warning(lastProblem);
        } else {
            try (ZipFile zip = new ZipFile(pack)) {
                Map<String, String> jsons = new HashMap<>();
                zip.stream().filter(e -> !e.isDirectory() && e.getName().startsWith(PREFIX) && e.getName().endsWith(".json"))
                        .forEach(e -> jsons.put(e.getName().substring(PREFIX.length()), read(zip, e)));
                for (Map.Entry<String, String> e : jsons.entrySet()) {
                    if (e.getKey().endsWith(".display.json")) continue;
                    String id = e.getKey().substring(0, e.getKey().length() - ".json".length());
                    String display = jsons.get(id + ".display.json");
                    if (display == null) {
                        getLogger().warning("Mob " + id + ": brak " + id + ".display.json - zapisz go w aplikacji jeszcze raz (nowa wersja zapisuje modele części).");
                        continue;
                    }
                    try {
                        MobDef def = MobLoader.parse(e.getValue(), display);
                        String key = def.id().toLowerCase(Locale.ROOT);
                        mobs.put(key, def);
                        // Kolejność: mobs.yml (edytor w aplikacji) > zachowanie zapisane w paczce (starsza appka)
                        // > stary config.yml (przykładowe moby) > domyślne.
                        ConfigurationSection fromFile = edited == null ? null : edited.getConfigurationSection(key);
                        JsonObject b = fromFile != null ? BehaviorLoader.toJson(fromFile) : BehaviorLoader.behaviorOf(e.getValue());
                        ConfigurationSection legacy = getConfig().getConfigurationSection("moby." + key);
                        behaviors.put(key, b != null ? BehaviorLoader.fromJson(b, def.name())
                                : legacy != null ? BehaviorLoader.fromLegacy(legacy, def.name()) : BehaviorLoader.defaults(def.name()));
                    } catch (RuntimeException ex) {
                        getLogger().warning("Mob " + id + ": zły plik - " + ex.getMessage());
                    }
                }
            } catch (IOException ex) {
                lastProblem = "Nie udało się przeczytać paczki: " + ex.getMessage();
                getLogger().warning(lastProblem);
            }
        }
        getLogger().info("Moby z paczki (" + mobs.size() + "): " + String.join(", ", mobs.keySet()));
        // Moby już w świecie dostają nowy model i zachowanie.
        live.values().forEach(m -> {
            if (m.dying()) m.remove();
            else m.detach();
        });
        live.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) attach(e);
    }

    private static String read(ZipFile zip, ZipEntry e) {
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    /** Ciało moba z tagiem (po restarcie / wczytaniu chunka) dostaje części i zachowanie. */
    private void attach(Entity e) {
        if (!(e instanceof Mob body) || live.containsKey(e.getUniqueId()) || body.isDead()) return;
        String id = body.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
        if (id == null) return;
        MobDef def = mobs.get(id);
        if (def == null) {
            // Moba usunięto z paczki - zostałoby niewidzialne ciało.
            body.remove();
            return;
        }
        try {
            live.put(body.getUniqueId(), new LiveMob(def, behaviors.get(id), this, body.getLocation(), body, false));
        } catch (RuntimeException ex) {
            getLogger().warning("Mob " + id + ": nie udało się przywrócić - " + ex.getMessage());
        }
    }

    // ---- MobHost ----

    @Override
    public Plugin plugin() {
        return this;
    }

    @Override
    public NamespacedKey mobKey() {
        return mobKey;
    }

    @Override
    public NamespacedKey damageKey() {
        return damageKey;
    }

    @Override
    public LiveMob spawn(String id, Location at, boolean summoned) {
        String key = id.toLowerCase(Locale.ROOT);
        MobDef def = mobs.get(key);
        if (def == null) return null;
        LiveMob m = new LiveMob(def, behaviors.get(key), this, at, null, summoned);
        live.put(m.base.getUniqueId(), m);
        return m;
    }

    public LiveMob spawn(String id, Location at) {
        return spawn(id, at, false);
    }

    @Override
    public List<LiveMob> nearby(Location at, double radius) {
        List<LiveMob> out = new ArrayList<>();
        double r2 = radius * radius;
        for (LiveMob m : live.values()) {
            if (m.alive() && m.base.getWorld() == at.getWorld() && m.base.getLocation().distanceSquared(at) <= r2) out.add(m);
        }
        return out;
    }

    /** Kliknięcie prawym w ciało albo w hitbox części - NPC reaguje. */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        LiveMob m = of(event.getRightClicked());
        if (m == null) {
            for (LiveMob x : live.values()) {
                if (x.partOf(event.getRightClicked()) != null) {
                    m = x;
                    break;
                }
            }
        }
        if (m == null) return;
        if (!m.bh.npc().enabled()) {
            m.onClicked(event.getPlayer()); // umiejętności "interact" zwykłego moba
            return;
        }
        event.setCancelled(true);
        m.onInteract(event.getPlayer());
        m.onClicked(event.getPlayer());
    }

    /** Fajerwerk z akcji "firework" (np. wieśniak-fajerwerk) - sam efekt, bez obrażeń. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof org.bukkit.entity.Firework fw
                && fw.getPersistentDataContainer().has(new NamespacedKey(this, "harmless"))) event.setCancelled(true);
    }

    /** Kupno w oknie handlu moba (akcja open_trade) - zapamiętane do zamknięcia okna. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPurchase(io.papermc.paper.event.player.PlayerPurchaseEvent event) {
        for (LiveMob m : live.values()) m.onTraded(event.getPlayer());
    }

    @EventHandler
    public void onTradeClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getInventory() instanceof org.bukkit.inventory.MerchantInventory) || !(event.getPlayer() instanceof Player p)) return;
        for (LiveMob m : new ArrayList<>(live.values())) if (m.isTradingWith(p)) m.onTradeClosed(p);
    }

    private LiveMob of(Entity e) {
        return e == null ? null : live.get(e.getUniqueId());
    }

    // ---- zdarzenia ----

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) attach(e);
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            LiveMob m = live.remove(e.getUniqueId());
            if (m == null) continue;
            if (m.dying()) m.remove();
            else m.detach();
        }
    }

    /** Ciało uderzyło (animacja ataku, umiejętności "hit"); pociski z umiejętności - obrażenia z tagu. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        LiveMob attacker = of(damager);
        if (attacker != null) {
            // Strażnik (mob pływający) kłuje kolcami każdego, kto go uderzy - nasze moby tego nie robią.
            if (event.getCause() == EntityDamageEvent.DamageCause.THORNS) {
                event.setCancelled(true);
                return;
            }
            attacker.onHitTarget(event.getEntity());
            return;
        }
        if (damager instanceof Projectile proj) {
            Double dmg = proj.getPersistentDataContainer().get(damageKey, PersistentDataType.DOUBLE);
            if (dmg != null) event.setDamage(dmg);
            LiveMob shooter = proj.getShooter() instanceof Entity s ? of(s) : null;
            if (shooter != null && of(event.getEntity()) != null) event.setCancelled(true); // moby nie strzelają w siebie nawzajem
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        LiveMob m = of(event.getEntity());
        if (m == null || m.dying()) return;
        if (m.shielded()) {
            event.setCancelled(true);
            return;
        }
        // NPC nieśmiertelny - poza /kill i próżnią (admin musi móc go usunąć).
        if (m.npcInvulnerable() && event.getCause() != EntityDamageEvent.DamageCause.KILL && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
            return;
        }
        // Ciało nie tonie (pszczoła w wodzie), ani nie dusi się, gdy lata przy suficie.
        if (event.getCause() == EntityDamageEvent.DamageCause.DROWNING
                || (event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION && m.bh.movement().equals("fly"))) {
            event.setCancelled(true);
            return;
        }
        double mult = m.takePendingMult();
        if (mult != 1) event.setDamage(event.getDamage() * mult);
        m.onHurt(event instanceof EntityDamageByEntityEvent by ? by.getDamager() : null);
    }

    /**
     * Cios w obszar trafienia moba (obiekt interakcji) = cios w moba, słabe punkty mocniej. Bez
     * ignoreCancelled: Paper oznacza atak na obiekt interakcji jako anulowany z góry (on sam obrażeń nie przyjmuje).
     */
    @EventHandler
    public void onPartAttack(PrePlayerAttackEntityEvent event) {
        for (LiveMob m : live.values()) {
            String bone = m.partOf(event.getAttacked());
            if (bone == null) continue;
            event.setCancelled(true);
            if (m.npcInvulnerable()) return;
            m.hitPart(event.getPlayer(), bone);
            return;
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LiveMob m = of(event.getEntity());
        if (m != null) {
            event.getDrops().clear();
            Player killer = event.getEntity().getKiller();
            event.setDroppedExp(killer != null ? m.bh.xp() : 0);
            int looting = killer == null ? 0 : killer.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.LOOTING);
            for (MobBehavior.Drop d : m.bh.drops()) {
                if (ThreadLocalRandom.current().nextDouble() > d.chance()) continue;
                int amount = ThreadLocalRandom.current().nextInt(d.min(), d.max() + 1) + (d.max() > 0 && looting > 0 ? ThreadLocalRandom.current().nextInt(looting + 1) : 0);
                if (amount <= 0) continue;
                ItemStack item = dropItem(d.item(), amount);
                if (item != null) event.getDrops().add(item);
                else getLogger().warning("Mob " + m.id() + ": nieznany przedmiot w dropach: " + d.item());
            }
            m.onDeath();
            return;
        }
        // Ofiara zabita przez naszego moba (cios albo pocisk) - jego umiejętności "kill".
        if (event.getEntity().getLastDamageCause() instanceof EntityDamageByEntityEvent last) {
            Entity d = last.getDamager();
            LiveMob killer = of(d instanceof Projectile pr && pr.getShooter() instanceof Entity sh ? sh : d);
            if (killer != null) killer.onKill(event.getEntity());
        }
        for (LiveMob owner : live.values()) {
            if (owner.ownsMinion(event.getEntity())) {
                event.getDrops().clear();
                event.setDroppedExp(0);
                return;
            }
        }
    }

    /** "minecraft:diamond" albo id z katalogu przedmiotów Core (własne przedmioty, "block_&lt;id&gt;" z Kreatora bloków). */
    private static ItemStack dropItem(String id, int amount) {
        String key = id.toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:") || !key.contains("_") && !key.contains(":")) {
            NamespacedKey k = NamespacedKey.fromString(key.contains(":") ? key : "minecraft:" + key);
            Material m = k == null ? null : Registry.MATERIAL.get(k);
            if (m != null && m.isItem() && !m.isAir()) return new ItemStack(m, Math.min(amount, m.getMaxStackSize() * 9));
            if (key.startsWith("minecraft:")) return null;
        }
        return CoreAPI.getCustomItemService().create(id, amount);
    }

    /** Husk w wodzie zamienia się w zombie - ciało moba nigdy. */
    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (of(event.getEntity()) != null) event.setCancelled(true);
    }

    // ---- komendy ----

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "spawn" -> {
                // Z konsoli / RCON (aplikacja): /@mob spawn <mob> [ilość] <gracz> - przy tym graczu,
                // albo /@mob spawn <mob> <ilość> <świat> <x> <y> <z> - w miejscu (bloki poleceń, serwer bez graczy).
                if (args.length >= 7 && mobs.containsKey(args[1].toLowerCase(Locale.ROOT))) {
                    World w = Bukkit.getWorld(args[3]);
                    try {
                        if (w == null) throw new NumberFormatException(args[3]);
                        Location at = new Location(w, Double.parseDouble(args[4]), Double.parseDouble(args[5]), Double.parseDouble(args[6]));
                        int n = Math.max(1, Math.min(50, Integer.parseInt(args[2])));
                        for (int i = 0; i < n; i++) spawn(args[1], at.clone().add(i == 0 ? 0 : ThreadLocalRandom.current().nextDouble(-2, 2), 0, i == 0 ? 0 : ThreadLocalRandom.current().nextDouble(-2, 2)));
                        lang.send(sender, this, "admin.spawned", Map.of("mob", behaviors.get(args[1].toLowerCase(Locale.ROOT)).name(), "count", String.valueOf(n)));
                    } catch (NumberFormatException e) {
                        sender.sendMessage("/@mob spawn <mob> <amount> <world> <x> <y> <z>");
                    }
                    return true;
                }
                Player p = args.length >= 4 ? Bukkit.getPlayerExact(args[3]) : sender instanceof Player self ? self : null;
                if (p == null) {
                    lang.send(sender, this, "admin.only-player");
                    return true;
                }
                if (args.length < 2 || !mobs.containsKey(args[1].toLowerCase(Locale.ROOT))) {
                    lang.send(sender, this, "admin.unknown-mob", Map.of("mobs", String.join(", ", mobs.keySet())));
                    if (lastProblem != null) lang.send(sender, this, "admin.problem", Map.of("problem", lastProblem));
                    return true;
                }
                int count = 1;
                if (args.length >= 3) {
                    try {
                        count = Math.max(1, Math.min(50, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ignored) {
                        count = 1;
                    }
                }
                // Blok przed graczem, przodem w tę samą stronę, w którą patrzy gracz (Karol 03.10 - ustawianie scen do nagrań).
                float yaw = p.getLocation().getYaw();
                Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize());
                at.setYaw(yaw);
                at.setPitch(0);
                for (int i = 0; i < count; i++) {
                    LiveMob m = spawn(args[1], at.clone().add(i == 0 ? 0 : ThreadLocalRandom.current().nextDouble(-2, 2), 0, i == 0 ? 0 : ThreadLocalRandom.current().nextDouble(-2, 2)));
                    if (m != null) {
                        m.base.setRotation(yaw, 0);
                        m.base.setBodyYaw(yaw);
                    }
                }
                lang.send(sender, this, "admin.spawned", Map.of("mob", behaviors.get(args[1].toLowerCase(Locale.ROOT)).name(), "count", String.valueOf(count)));
            }
            case "list" -> {
                if (mobs.isEmpty()) lang.send(sender, this, "admin.list-empty");
                else lang.send(sender, this, "admin.list", Map.of("mobs", String.join(", ", mobs.keySet()), "alive", String.valueOf(live.size())));
                if (lastProblem != null) lang.send(sender, this, "admin.problem", Map.of("problem", lastProblem));
            }
            case "killall" -> {
                int n = live.size();
                live.values().forEach(LiveMob::remove);
                live.clear();
                lang.send(sender, this, "admin.killed", Map.of("count", String.valueOf(n)));
            }
            case "reload" -> {
                reload();
                lang.send(sender, this, "admin.reloaded", Map.of("count", String.valueOf(mobs.size())));
                if (lastProblem != null) lang.send(sender, this, "admin.problem", Map.of("problem", lastProblem));
            }
            default -> lang.send(sender, this, "admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return List.of("spawn", "list", "killall", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) return new ArrayList<>(mobs.keySet());
        return List.of();
    }
}
