package elo.mainplugins.claims;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ochrona terenu na chunkach: /claim zabezpiecza chunk pod graczem. Na cudzym terenie nie da się
 * niszczyć, budować, otwierać skrzyń, używać wiader ani bić zwierząt - chyba że właściciel zaufał.
 * Wybuchy i moby nie niszczą terenu; PvP wyłączone. Flagi (pvp, wybuchy, moby) zmienia się z planem.
 */
public final class ClaimManager implements Listener {

    public static final String BYPASS_PERMISSION = "mainplugins.claims.bypass";

    private final JavaPlugin plugin;
    private final LangService lang;
    private final EconomyService economy;
    private final ClaimStore store;
    private ClaimsConfig config;
    private boolean planUnlocked;
    /** Ostatni chunk gracza - komunikat tylko przy zmianie właściciela terenu. */
    private final Map<UUID, UUID> lastOwner = new HashMap<>();
    private final Set<UUID> bypass = new HashSet<>();

    ClaimManager(JavaPlugin plugin, LangService lang, EconomyService economy) {
        this.plugin = plugin;
        this.lang = lang;
        this.economy = economy;
        this.store = new ClaimStore(new File(plugin.getDataFolder(), "data.yml"), plugin.getLogger()::warning);
    }

    void reload() {
        File file = new File(plugin.getDataFolder(), "claims.yml");
        if (!file.exists()) copyDefaults(file);
        config = ClaimsConfig.parse(YamlConfiguration.loadConfiguration(file), plugin.getLogger()::warning);
        store.load();
    }

    private void copyDefaults(File file) {
        String resource = "defaults/" + lang.language() + "/claims.yml";
        if (plugin.getResource(resource) == null) resource = "defaults/en/claims.yml";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return;
            file.getParentFile().mkdirs();
            Files.copy(in, file.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write claims.yml: " + e.getMessage());
        }
    }

    void setPlanUnlocked(boolean v) {
        planUnlocked = v;
    }

    boolean planUnlocked() {
        return planUnlocked;
    }

    void save() {
        store.save();
    }

    int maxChunks() {
        return planUnlocked ? config.maxChunksPlan() : config.maxChunksFree();
    }

    private void send(Player p, String key, Map<String, String> ph) {
        lang.send(p, plugin, key, ph);
    }

    private void send(Player p, String key) {
        lang.send(p, plugin, key);
    }

    private static String nameOf(UUID id) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(id);
        return o.getName() != null ? o.getName() : id.toString().substring(0, 8);
    }

    private boolean isBypass(Player p) {
        return bypass.contains(p.getUniqueId()) && p.hasPermission(BYPASS_PERMISSION);
    }

    private boolean allowed(Player p, Location l) {
        return store.canUse(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4, p.getUniqueId(), isBypass(p));
    }

    private UUID ownerAt(Location l) {
        return store.ownerAt(l.getWorld().getName(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
    }

    private ClaimStore.Owner flagsAt(Location l) {
        UUID owner = ownerAt(l);
        return owner == null ? null : store.ownerIfExists(owner);
    }

    // ---- komendy ----

    void claim(Player p) {
        Chunk c = p.getLocation().getChunk();
        String world = c.getWorld().getName();
        if (config.disabledWorlds().contains(world.toLowerCase())) { send(p, "error.world-disabled"); return; }
        UUID owner = store.ownerAt(world, c.getX(), c.getZ());
        if (owner != null) {
            send(p, owner.equals(p.getUniqueId()) ? "error.already-yours" : "error.taken", Map.of("owner", nameOf(owner)));
            return;
        }
        int have = store.count(p.getUniqueId());
        if (have >= maxChunks()) {
            send(p, planUnlocked ? "error.limit" : "error.limit-plan", Map.of("max", String.valueOf(maxChunks())));
            return;
        }
        if (config.costPerChunk() > 0) {
            if (!economy.maWystarczajaco(p.getUniqueId(), config.costPerChunk())) {
                send(p, "error.no-money", Map.of("cost", fmt(config.costPerChunk())));
                return;
            }
            economy.odejmijKase(p.getUniqueId(), config.costPerChunk());
        }
        store.owner(p.getUniqueId(), config);
        store.claim(world, c.getX(), c.getZ(), p.getUniqueId());
        send(p, "claim.claimed", Map.of("count", String.valueOf(have + 1), "max", String.valueOf(maxChunks())));
        showBorder(p, c);
    }

    void unclaim(Player p) {
        Chunk c = p.getLocation().getChunk();
        UUID owner = store.ownerAt(c.getWorld().getName(), c.getX(), c.getZ());
        if (owner == null || (!owner.equals(p.getUniqueId()) && !isBypass(p))) { send(p, "error.not-yours"); return; }
        store.unclaim(c.getWorld().getName(), c.getX(), c.getZ());
        send(p, "claim.unclaimed");
    }

    void list(Player p) {
        List<String> mine = store.chunksOf(p.getUniqueId());
        send(p, "claim.list-header", Map.of("count", String.valueOf(mine.size()), "max", String.valueOf(maxChunks())));
        for (String k : mine) {
            String[] a = k.split(";");
            if (a.length == 3) {
                send(p, "claim.list-line", Map.of("world", a[0], "x", String.valueOf(Integer.parseInt(a[1]) * 16 + 8), "z", String.valueOf(Integer.parseInt(a[2]) * 16 + 8)));
            }
        }
    }

    void trust(Player p, String name, boolean add) {
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(name);
        if (t == null) { send(p, "error.player-unknown", Map.of("player", name)); return; }
        if (t.getUniqueId().equals(p.getUniqueId())) return;
        ClaimStore.Owner o = store.owner(p.getUniqueId(), config);
        if (add) o.trusted.add(t.getUniqueId());
        else o.trusted.remove(t.getUniqueId());
        store.markDirty();
        send(p, add ? "claim.trusted" : "claim.untrusted", Map.of("player", name));
    }

    void info(Player p) {
        UUID owner = ownerAt(p.getLocation());
        if (owner == null) { send(p, "claim.info-free"); return; }
        ClaimStore.Owner o = store.owner(owner, config);
        send(p, "claim.info", Map.of("owner", nameOf(owner),
                "trusted", o.trusted.isEmpty() ? "-" : String.join(", ", o.trusted.stream().map(ClaimManager::nameOf).toList()),
                "pvp", onOff(o.pvp), "explosions", onOff(o.explosions), "mobs", onOff(o.mobGriefing)));
        showBorder(p, p.getLocation().getChunk());
    }

    void flag(Player p, String flag, String value) {
        if (!planUnlocked) { send(p, "error.plan-flags"); return; }
        ClaimStore.Owner o = store.owner(p.getUniqueId(), config);
        boolean on = value != null && (value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("wl"));
        switch (flag == null ? "" : flag.toLowerCase()) {
            case "pvp" -> o.pvp = on;
            case "explosions" -> o.explosions = on;
            case "mobs" -> o.mobGriefing = on;
            default -> { send(p, "help.flag"); return; }
        }
        store.markDirty();
        send(p, "claim.flag-set", Map.of("flag", flag.toLowerCase(), "value", onOff(on)));
    }

    boolean toggleBypass(Player p) {
        if (!bypass.remove(p.getUniqueId())) {
            bypass.add(p.getUniqueId());
            return true;
        }
        return false;
    }

    private String onOff(boolean v) {
        return lang.language().equals("pl") ? (v ? "wł." : "wył.") : (v ? "on" : "off");
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** Granica chunka cząsteczkami przez kilka sekund (co pół sekundy). */
    void showBorder(Player p, Chunk c) {
        World w = c.getWorld();
        int bx = c.getX() << 4, bz = c.getZ() << 4;
        final int[] runs = {config.borderSeconds() * 2};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline() || runs[0]-- <= 0 || p.getWorld() != w) {
                task.cancel();
                return;
            }
            double y = p.getLocation().getY() + 1;
            for (int i = 0; i <= 16; i += 2) {
                for (double[] xz : new double[][] {{bx + i, bz}, {bx + i, bz + 16}, {bx, bz + i}, {bx + 16, bz + i}}) {
                    p.spawnParticle(Particle.HAPPY_VILLAGER, xz[0], y, xz[1], 1, 0, 0.4, 0, 0);
                }
            }
        }, 0L, 10L);
    }

    // ---- ochrona ----

    private void deny(Player p) {
        UUID owner = ownerAt(p.getLocation());
        p.sendActionBar(lang.msg(plugin, "protect.denied", Map.of("owner", owner == null ? "?" : nameOf(owner))));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    /** Skrzynie, drzwi, przyciski, dźwignie, kowadła... i deptanie upraw. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        boolean relevant = e.getAction() == Action.PHYSICAL || (e.getAction() == Action.RIGHT_CLICK_BLOCK && b.getType().isInteractable());
        if (relevant && !allowed(e.getPlayer(), b.getLocation())) {
            e.setCancelled(true);
            if (e.getAction() != Action.PHYSICAL) deny(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!allowed(e.getPlayer(), e.getRightClicked().getLocation())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (!allowed(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!allowed(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            deny(e.getPlayer());
        }
    }

    /** PvP według flagi właściciela; zwierzęta i ramki chronione przed obcymi (potwory nie). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player attacker = e.getDamager() instanceof Player d ? d
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null) return;
        Location at = e.getEntity().getLocation();
        if (ownerAt(at) == null) return;
        if (e.getEntity() instanceof Player) {
            ClaimStore.Owner o = flagsAt(at);
            if (o != null && !o.pvp && !isBypass(attacker)) e.setCancelled(true);
            return;
        }
        if (!(e.getEntity() instanceof Monster) && !allowed(attacker, at)) {
            e.setCancelled(true);
            deny(attacker);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> {
            ClaimStore.Owner o = flagsAt(b.getLocation());
            return ownerAt(b.getLocation()) != null && (o == null || !o.explosions);
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> {
            ClaimStore.Owner o = flagsAt(b.getLocation());
            return ownerAt(b.getLocation()) != null && (o == null || !o.explosions);
        });
    }

    /** Endermany, zombie łamiące drzwi itd. - według flagi mob-griefing. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player) return;
        ClaimStore.Owner o = flagsAt(e.getBlock().getLocation());
        if (ownerAt(e.getBlock().getLocation()) != null && (o == null || !o.mobGriefing)) e.setCancelled(true);
    }

    /** Komunikat na pasku akcji przy wejściu na teren innego właściciela (albo na wolny teren). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!config.enterMessage()) return;
        Location from = e.getFrom(), to = e.getTo();
        if ((from.getBlockX() >> 4) == (to.getBlockX() >> 4) && (from.getBlockZ() >> 4) == (to.getBlockZ() >> 4) && from.getWorld() == to.getWorld()) return;
        UUID owner = ownerAt(to);
        UUID prev = lastOwner.get(e.getPlayer().getUniqueId());
        if (java.util.Objects.equals(owner, prev)) return;
        if (owner == null) lastOwner.remove(e.getPlayer().getUniqueId());
        else lastOwner.put(e.getPlayer().getUniqueId(), owner);
        e.getPlayer().sendActionBar(owner == null ? lang.msg(plugin, "protect.wilderness")
                : lang.msg(plugin, "protect.entered", Map.of("owner", nameOf(owner))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastOwner.remove(e.getPlayer().getUniqueId());
        bypass.remove(e.getPlayer().getUniqueId());
    }
}
