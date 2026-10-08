package elo.mainplugins.guilds;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gildie: zakładanie, zaproszenia, role (lider/oficer/członek), czat gildii, dom, brak obrażeń
 * między członkami. Z planem (licencja "guilds"): bank gildii, sojusze i wyższy limit członków.
 * Tag gildii idzie do czatu/TAB przez placeholder %mainplugins_guild_tag% (bez osobnego
 * renderera czatu - nie gryzie się z tagiem rangi).
 */
public final class GuildManager implements Listener {

    private final JavaPlugin plugin;
    private final LangService lang;
    private final EconomyService economy;
    private final GuildStore store;
    private GuildsConfig config;
    private boolean planUnlocked;
    /** Zaproszenia: gracz -> (id gildii -> kiedy wygasa). */
    private final Map<UUID, Map<String, Long>> invites = new HashMap<>();
    /** Gracze z włączonym czatem gildii (czytane z wątku czatu). */
    private final Set<UUID> guildChat = ConcurrentHashMap.newKeySet();
    /** Prośby o sojusz: id gildii adresata -> id gildii proszącej. */
    private final Map<String, Set<String>> allyRequests = new HashMap<>();

    GuildManager(JavaPlugin plugin, LangService lang, EconomyService economy) {
        this.plugin = plugin;
        this.lang = lang;
        this.economy = economy;
        this.store = new GuildStore(new File(plugin.getDataFolder(), "data.yml"), plugin.getLogger()::warning);
    }

    void reload() {
        File file = new File(plugin.getDataFolder(), "guilds.yml");
        if (!file.exists()) copyDefaults(file);
        config = GuildsConfig.parse(YamlConfiguration.loadConfiguration(file), plugin.getLogger()::warning);
        store.load();
        plugin.getLogger().info("Loaded " + store.all().size() + " guilds.");
    }

    private void copyDefaults(File file) {
        String resource = "defaults/" + lang.language() + "/guilds.yml";
        if (plugin.getResource(resource) == null) resource = "defaults/en/guilds.yml";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return;
            file.getParentFile().mkdirs();
            Files.copy(in, file.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write guilds.yml: " + e.getMessage());
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

    GuildStore store() {
        return store;
    }

    int maxMembers() {
        return planUnlocked ? config.maxMembersPlan() : config.maxMembersFree();
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

    private void broadcast(Guild g, String key, Map<String, String> ph) {
        for (UUID m : g.members) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) send(p, key, ph);
        }
    }

    // ---- komendy gracza ----

    void create(Player p, String tag, String name) {
        if (store.of(p.getUniqueId()) != null) {
            send(p, "error.already-in-guild");
            return;
        }
        String err = GuildRules.checkTag(tag, config);
        if (err == null) err = GuildRules.checkName(name, config);
        if (err != null) {
            send(p, err, Map.of("min", String.valueOf(config.tagMin()), "max", String.valueOf(config.tagMax()), "name-max", String.valueOf(config.nameMax())));
            return;
        }
        if (store.byTag(tag) != null) {
            send(p, "error.tag-taken", Map.of("tag", tag));
            return;
        }
        if (config.createCost() > 0) {
            if (!economy.maWystarczajaco(p.getUniqueId(), config.createCost())) {
                send(p, "error.no-money", Map.of("cost", fmt(config.createCost())));
                return;
            }
            economy.odejmijKase(p.getUniqueId(), config.createCost());
        }
        Guild g = new Guild(tag, name, p.getUniqueId());
        store.put(g);
        Bukkit.getOnlinePlayers().forEach(o -> send(o, "guild.created-broadcast", Map.of("player", p.getName(), "tag", tag, "name", name)));
    }

    void invite(Player p, String targetName) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        if (!g.canManage(p.getUniqueId())) { send(p, "error.no-rights"); return; }
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null) { send(p, "error.player-offline", Map.of("player", targetName)); return; }
        if (store.of(t.getUniqueId()) != null) { send(p, "error.target-in-guild", Map.of("player", t.getName())); return; }
        if (g.members.size() >= maxMembers()) { send(p, "error.full", Map.of("max", String.valueOf(maxMembers()))); return; }
        invites.computeIfAbsent(t.getUniqueId(), k -> new HashMap<>()).put(g.id, System.currentTimeMillis() + config.inviteSeconds() * 1000L);
        send(p, "guild.invite-sent", Map.of("player", t.getName()));
        send(t, "guild.invite-received", Map.of("tag", g.tag, "name", g.name, "player", p.getName()));
    }

    void accept(Player p, String tag) {
        if (store.of(p.getUniqueId()) != null) { send(p, "error.already-in-guild"); return; }
        Map<String, Long> mine = invites.getOrDefault(p.getUniqueId(), Map.of());
        mine.values().removeIf(exp -> exp < System.currentTimeMillis());
        String id = tag != null ? tag.toLowerCase() : mine.size() == 1 ? mine.keySet().iterator().next() : null;
        Guild g = id != null && mine.containsKey(id) ? store.byTag(id) : null;
        if (g == null) { send(p, "error.no-invite"); return; }
        if (g.members.size() >= maxMembers()) { send(p, "error.full", Map.of("max", String.valueOf(maxMembers()))); return; }
        invites.remove(p.getUniqueId());
        store.join(g, p.getUniqueId());
        broadcast(g, "guild.joined", Map.of("player", p.getName(), "tag", g.tag));
    }

    void kick(Player p, String targetName) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        UUID target = memberByName(g, targetName);
        if (target == null) { send(p, "error.not-member", Map.of("player", targetName)); return; }
        if (!g.canKick(p.getUniqueId(), target)) { send(p, "error.no-rights"); return; }
        store.leave(g, target);
        broadcast(g, "guild.kicked", Map.of("player", nameOf(target)));
        Player t = Bukkit.getPlayer(target);
        if (t != null) send(t, "guild.you-were-kicked", Map.of("tag", g.tag));
    }

    void leave(Player p) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        if (g.leader.equals(p.getUniqueId())) { send(p, "error.leader-cant-leave"); return; }
        store.leave(g, p.getUniqueId());
        guildChat.remove(p.getUniqueId());
        send(p, "guild.left", Map.of("tag", g.tag));
        broadcast(g, "guild.member-left", Map.of("player", p.getName()));
    }

    void disband(Player p) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        if (!g.leader.equals(p.getUniqueId())) { send(p, "error.no-rights"); return; }
        if (g.bank > 0) economy.dodajKase(p.getUniqueId(), g.bank);
        broadcast(g, "guild.disbanded", Map.of("tag", g.tag));
        g.members.forEach(guildChat::remove);
        store.remove(g);
    }

    void promote(Player p, String targetName, boolean up) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        if (!g.leader.equals(p.getUniqueId())) { send(p, "error.no-rights"); return; }
        UUID target = memberByName(g, targetName);
        if (target == null || target.equals(g.leader)) { send(p, "error.not-member", Map.of("player", targetName)); return; }
        if (up) g.officers.add(target);
        else g.officers.remove(target);
        store.markDirty();
        broadcast(g, up ? "guild.promoted" : "guild.demoted", Map.of("player", nameOf(target)));
    }

    void transfer(Player p, String targetName) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        if (!g.leader.equals(p.getUniqueId())) { send(p, "error.no-rights"); return; }
        UUID target = memberByName(g, targetName);
        if (target == null || target.equals(g.leader)) { send(p, "error.not-member", Map.of("player", targetName)); return; }
        g.officers.remove(target);
        g.officers.add(g.leader);
        g.leader = target;
        store.markDirty();
        broadcast(g, "guild.new-leader", Map.of("player", nameOf(target)));
    }

    void setHome(Player p) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        if (!g.canManage(p.getUniqueId())) { send(p, "error.no-rights"); return; }
        Location l = p.getLocation();
        g.home = l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
        store.markDirty();
        send(p, "guild.home-set");
    }

    void home(Player p) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        Location target = parseHome(g.home);
        if (target == null) { send(p, "error.no-home"); return; }
        int delay = config.homeDelaySeconds();
        if (delay <= 0) {
            p.teleportAsync(target);
            return;
        }
        Location start = p.getLocation().clone();
        send(p, "guild.home-wait", Map.of("seconds", String.valueOf(delay)));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (p.getLocation().getWorld() != start.getWorld() || p.getLocation().distanceSquared(start) > 1.0) {
                send(p, "guild.home-cancelled");
                return;
            }
            p.teleportAsync(target);
        }, delay * 20L);
    }

    private static Location parseHome(String raw) {
        if (raw == null) return null;
        String[] a = raw.split(";");
        if (a.length < 6) return null;
        World w = Bukkit.getWorld(a[0]);
        if (w == null) return null;
        try {
            return new Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]), Float.parseFloat(a[4]), Float.parseFloat(a[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    void bank(Player p, String action, String amountRaw) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        if (!planUnlocked) { send(p, "error.plan-bank"); return; }
        if (action == null) { send(p, "guild.bank-balance", Map.of("amount", fmt(g.bank))); return; }
        double amount;
        try {
            amount = Double.parseDouble(amountRaw.replace(',', '.'));
        } catch (RuntimeException e) {
            send(p, "error.bad-amount");
            return;
        }
        if (amount <= 0 || Double.isNaN(amount) || Double.isInfinite(amount)) { send(p, "error.bad-amount"); return; }
        if (action.equals("deposit")) {
            if (!economy.maWystarczajaco(p.getUniqueId(), amount)) { send(p, "error.no-money", Map.of("cost", fmt(amount))); return; }
            economy.odejmijKase(p.getUniqueId(), amount);
            g.bank += amount;
            store.markDirty();
            broadcast(g, "guild.bank-deposit", Map.of("player", p.getName(), "amount", fmt(amount), "total", fmt(g.bank)));
        } else {
            if (!g.leader.equals(p.getUniqueId())) { send(p, "error.no-rights"); return; }
            if (g.bank < amount) { send(p, "error.bank-low", Map.of("amount", fmt(g.bank))); return; }
            g.bank -= amount;
            economy.dodajKase(p.getUniqueId(), amount);
            store.markDirty();
            broadcast(g, "guild.bank-withdraw", Map.of("player", p.getName(), "amount", fmt(amount), "total", fmt(g.bank)));
        }
    }

    void ally(Player p, String tag, boolean add) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        if (!planUnlocked) { send(p, "error.plan-allies"); return; }
        if (!g.leader.equals(p.getUniqueId())) { send(p, "error.no-rights"); return; }
        Guild other = store.byTag(tag);
        if (other == null || other == g) { send(p, "error.no-guild", Map.of("tag", tag == null ? "" : tag)); return; }
        if (!add) {
            g.allies.remove(other.id);
            other.allies.remove(g.id);
            store.markDirty();
            broadcast(g, "guild.ally-removed", Map.of("tag", other.tag));
            broadcast(other, "guild.ally-removed", Map.of("tag", g.tag));
            return;
        }
        if (g.allies.contains(other.id)) return;
        if (g.allies.size() >= config.maxAllies() || other.allies.size() >= config.maxAllies()) { send(p, "error.allies-full", Map.of("max", String.valueOf(config.maxAllies()))); return; }
        // Druga strona już prosiła = sojusz zawarty; inaczej wysyłamy prośbę.
        if (allyRequests.getOrDefault(g.id, Set.of()).contains(other.id)) {
            allyRequests.get(g.id).remove(other.id);
            g.allies.add(other.id);
            other.allies.add(g.id);
            store.markDirty();
            broadcast(g, "guild.ally-made", Map.of("tag", other.tag));
            broadcast(other, "guild.ally-made", Map.of("tag", g.tag));
        } else {
            allyRequests.computeIfAbsent(other.id, k -> new HashSet<>()).add(g.id);
            send(p, "guild.ally-requested", Map.of("tag", other.tag));
            Player leader = Bukkit.getPlayer(other.leader);
            if (leader != null) send(leader, "guild.ally-request-received", Map.of("tag", g.tag));
        }
    }

    void toggleChat(Player p) {
        if (store.of(p.getUniqueId()) == null) { send(p, "error.not-in-guild"); return; }
        if (guildChat.remove(p.getUniqueId())) send(p, "guild.chat-off");
        else {
            guildChat.add(p.getUniqueId());
            send(p, "guild.chat-on");
        }
    }

    void guildMessage(Player p, String message) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) { send(p, "error.not-in-guild"); return; }
        broadcast(g, "guild.chat-format", Map.of("tag", g.tag, "player", p.getName(), "message", message));
    }

    void info(Player p, String tag) {
        Guild g = tag != null ? store.byTag(tag) : store.of(p.getUniqueId());
        if (g == null) { send(p, tag != null ? "error.no-guild" : "error.not-in-guild", Map.of("tag", tag == null ? "" : tag)); return; }
        List<String> names = g.members.stream().map(GuildManager::nameOf).toList();
        send(p, "guild.info", Map.of("tag", g.tag, "name", g.name, "leader", nameOf(g.leader), "members", String.valueOf(g.members.size()),
                "max", String.valueOf(maxMembers()), "list", String.join(", ", names), "bank", fmt(g.bank),
                "allies", g.allies.isEmpty() ? "-" : String.join(", ", g.allies.stream().map(a -> store.byTag(a) != null ? store.byTag(a).tag : a).toList())));
    }

    void top(Player p) {
        List<Guild> top = GuildRules.top(store.all(), 10);
        send(p, "guild.top-header");
        int i = 1;
        for (Guild g : top) {
            send(p, "guild.top-line", Map.of("pos", String.valueOf(i++), "tag", g.tag, "name", g.name, "members", String.valueOf(g.members.size()), "bank", fmt(g.bank)));
        }
        if (top.isEmpty()) send(p, "guild.top-empty");
    }

    private UUID memberByName(Guild g, String name) {
        for (UUID m : g.members) if (nameOf(m).equalsIgnoreCase(name)) return m;
        return null;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ---- listenery ----

    /** Członkowie gildii (i sojusznicy) nie ranią się nawzajem, chyba że friendly-fire: true. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (config.friendlyFire() || !(e.getEntity() instanceof Player victim)) return;
        Player attacker = e.getDamager() instanceof Player d ? d
                : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null || attacker == victim) return;
        Guild a = store.of(attacker.getUniqueId());
        Guild v = store.of(victim.getUniqueId());
        if (a != null && v != null && (a == v || a.allies.contains(v.id))) e.setCancelled(true);
    }

    /** Włączony czat gildii: wiadomość idzie tylko do członków (z wątku czatu przez scheduler). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!guildChat.contains(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> guildMessage(p, msg));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        invites.remove(e.getPlayer().getUniqueId());
    }

    // ---- okno /g ----

    static final class GuildGui implements InventoryHolder {
        Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    void openMenu(Player p) {
        Guild g = store.of(p.getUniqueId());
        if (g == null) {
            send(p, "guild.no-guild-help");
            return;
        }
        GuildGui gui = new GuildGui();
        gui.inventory = Bukkit.createInventory(gui, 54, lang.msg(plugin, "menu.title", Map.of("tag", g.tag, "name", g.name)));
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) gui.inventory.setItem(i, filler);
        gui.inventory.setItem(4, item(Material.WHITE_BANNER, lang.msg(plugin, "menu.info-name", Map.of("tag", g.tag, "name", g.name)), List.of(
                lang.msg(plugin, "menu.info-leader", Map.of("leader", nameOf(g.leader))),
                lang.msg(plugin, "menu.info-members", Map.of("members", String.valueOf(g.members.size()), "max", String.valueOf(maxMembers()))),
                lang.msg(plugin, planUnlocked ? "menu.info-bank" : "menu.info-bank-locked", Map.of("bank", fmt(g.bank))))));
        int slot = 9;
        for (UUID m : g.members) {
            if (slot > 44) break;
            Guild.Role r = g.role(m);
            boolean online = Bukkit.getPlayer(m) != null;
            Material mat = r == Guild.Role.LEADER ? Material.GOLDEN_HELMET : r == Guild.Role.OFFICER ? Material.IRON_HELMET : Material.LEATHER_HELMET;
            gui.inventory.setItem(slot++, item(mat, lang.msg(plugin, "menu.member", Map.of("player", nameOf(m))), List.of(
                    lang.msg(plugin, "menu.role-" + r.name().toLowerCase()),
                    lang.msg(plugin, online ? "menu.online" : "menu.offline"))));
        }
        gui.inventory.setItem(47, item(Material.RED_BED, lang.msg(plugin, "menu.home"), List.of(lang.msg(plugin, "menu.home-lore"))));
        gui.inventory.setItem(49, item(Material.WRITABLE_BOOK, lang.msg(plugin, guildChat.contains(p.getUniqueId()) ? "menu.chat-on" : "menu.chat-off"), List.of(lang.msg(plugin, "menu.chat-lore"))));
        gui.inventory.setItem(51, item(Material.GOLD_INGOT, lang.msg(plugin, "menu.bank"), List.of(lang.msg(plugin, planUnlocked ? "menu.bank-lore" : "menu.info-bank-locked", Map.of("bank", fmt(g.bank))))));
        p.openInventory(gui.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder(false) instanceof GuildGui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        switch (e.getRawSlot()) {
            case 47 -> {
                p.closeInventory();
                home(p);
            }
            case 49 -> {
                toggleChat(p);
                openMenu(p);
            }
            case 51 -> bank(p, null, null);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof GuildGui) e.setCancelled(true);
    }

    private static ItemStack item(Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        it.setData(DataComponentTypes.CUSTOM_NAME, name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (Component c : lore) lines.add(c.decoration(TextDecoration.ITALIC, false));
            it.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        }
        return it;
    }
}
