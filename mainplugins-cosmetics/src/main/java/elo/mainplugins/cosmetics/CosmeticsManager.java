package elo.mainplugins.cosmetics;

import com.destroystokyo.paper.entity.Pathfinder;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.LangService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Kosmetyki: czapki (item na pustym slocie hełmu - nie do zabrania), smugi cząsteczek za
 * idącym graczem i pupile (mob idący za graczem, nieśmiertelny, znika po wyjściu).
 * Darmowe kosmetyki dla wszystkich, reszta za permisją. Plan: czapki z custom itemów (Plus), pupile (Pro).
 */
public final class CosmeticsManager implements Listener {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final LangService lang;
    private final CustomItemService customItems;
    private final NamespacedKey tag;
    private List<Cosmetic> cosmetics = List.of();
    /** Plan: Plus = czapki z custom itemów, Pro = pupile. */
    private boolean plus;
    private boolean pro;
    /** Założone kosmetyki: gracz -> typ -> id. */
    private final Map<UUID, Map<Cosmetic.Type, String>> equipped = new HashMap<>();
    private final Map<UUID, Entity> pets = new HashMap<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private final File dataFile;

    CosmeticsManager(JavaPlugin plugin, LangService lang, CustomItemService customItems) {
        this.plugin = plugin;
        this.lang = lang;
        this.customItems = customItems;
        this.tag = new NamespacedKey(plugin, "cosmetic");
        this.dataFile = new File(plugin.getDataFolder(), "players.yml");
    }

    void reload() {
        File file = new File(plugin.getDataFolder(), "cosmetics.yml");
        if (!file.exists()) copyDefaults(file);
        cosmetics = Cosmetic.parseAll(YamlConfiguration.loadConfiguration(file), plugin.getLogger()::warning);
        loadData();
        plugin.getLogger().info("Loaded " + cosmetics.size() + " cosmetics.");
    }

    private void copyDefaults(File file) {
        String resource = "defaults/" + lang.language() + "/cosmetics.yml";
        if (plugin.getResource(resource) == null) resource = "defaults/en/cosmetics.yml";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return;
            file.getParentFile().mkdirs();
            Files.copy(in, file.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write cosmetics.yml: " + e.getMessage());
        }
    }

    void setPlan(boolean plus, boolean pro) {
        this.plus = plus;
        this.pro = pro;
    }

    boolean plus() {
        return plus;
    }

    // ---- dane ----

    private void loadData() {
        equipped.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        for (String k : yml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                Map<Cosmetic.Type, String> m = new EnumMap<>(Cosmetic.Type.class);
                for (Cosmetic.Type t : Cosmetic.Type.values()) {
                    String v = yml.getString(k + "." + t.name().toLowerCase(Locale.ROOT));
                    if (v != null && !v.isBlank()) m.put(t, v);
                }
                if (!m.isEmpty()) equipped.put(id, m);
            } catch (IllegalArgumentException ignored) {
                // zły klucz - pomijamy
            }
        }
    }

    void save() {
        YamlConfiguration yml = new YamlConfiguration();
        equipped.forEach((id, m) -> m.forEach((t, c) -> yml.set(id + "." + t.name().toLowerCase(Locale.ROOT), c)));
        try {
            dataFile.getParentFile().mkdirs();
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save players.yml: " + e.getMessage());
        }
    }

    private Cosmetic byId(String id) {
        for (Cosmetic c : cosmetics) if (c.id().equals(id)) return c;
        return null;
    }

    /** null = może używać, inaczej klucz komunikatu, czemu nie. */
    String lockReason(Player p, Cosmetic c) {
        if (c.type() == Cosmetic.Type.PET && !pro) return "lock.plan-pro";
        if (c.type() == Cosmetic.Type.HAT && c.custom() && !plus) return "lock.plan-plus";
        if (!c.free() && !p.hasPermission(c.permission())) return "lock.permission";
        return null;
    }

    // ---- zakładanie ----

    void toggle(Player p, Cosmetic c) {
        String reason = lockReason(p, c);
        if (reason != null) {
            lang.send(p, plugin, reason);
            return;
        }
        Map<Cosmetic.Type, String> mine = equipped.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Cosmetic.Type.class));
        boolean was = c.id().equals(mine.get(c.type()));
        unequip(p, c.type());
        if (!was) {
            mine.put(c.type(), c.id());
            apply(p, c);
            lang.send(p, plugin, "cosmetic.equipped", Map.of("name", plain(c.name())));
        } else {
            lang.send(p, plugin, "cosmetic.removed", Map.of("name", plain(c.name())));
        }
    }

    private void unequip(Player p, Cosmetic.Type type) {
        Map<Cosmetic.Type, String> mine = equipped.get(p.getUniqueId());
        if (mine == null) return;
        mine.remove(type);
        switch (type) {
            case HAT -> {
                ItemStack helmet = p.getInventory().getHelmet();
                if (isCosmetic(helmet)) p.getInventory().setHelmet(null);
            }
            case PET -> removePet(p.getUniqueId());
            case TRAIL -> { }
        }
    }

    private void apply(Player p, Cosmetic c) {
        switch (c.type()) {
            case HAT -> {
                ItemStack current = p.getInventory().getHelmet();
                if (current != null && !current.getType().isAir() && !isCosmetic(current)) {
                    lang.send(p, plugin, "cosmetic.helmet-busy");
                    equipped.get(p.getUniqueId()).remove(Cosmetic.Type.HAT);
                    return;
                }
                ItemStack hat = c.custom() ? customItems.create(c.value(), 1, p) : itemOf(c.value());
                if (hat == null) {
                    lang.send(p, plugin, "cosmetic.broken", Map.of("name", plain(c.name())));
                    return;
                }
                hat.editPersistentDataContainer(pdc -> pdc.set(tag, PersistentDataType.STRING, c.id()));
                hat.setData(DataComponentTypes.CUSTOM_NAME, legacy(c.name()));
                p.getInventory().setHelmet(hat);
            }
            case PET -> spawnPet(p, c);
            case TRAIL -> { }
        }
    }

    private static ItemStack itemOf(String material) {
        Material m = Material.matchMaterial(material);
        return m != null && m.isItem() ? new ItemStack(m) : null;
    }

    boolean isCosmetic(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getPersistentDataContainer().has(tag, PersistentDataType.STRING);
    }

    /** Po wejściu na serwer / zmianie świata - przywraca czapkę i pupila. */
    void restore(Player p) {
        Map<Cosmetic.Type, String> mine = equipped.get(p.getUniqueId());
        if (mine == null) return;
        for (Cosmetic.Type t : List.copyOf(mine.keySet())) {
            Cosmetic c = byId(mine.get(t));
            if (c == null || lockReason(p, c) != null) {
                mine.remove(t);
                continue;
            }
            if (t != Cosmetic.Type.TRAIL) apply(p, c);
        }
    }

    // ---- pupile ----

    private void spawnPet(Player p, Cosmetic c) {
        removePet(p.getUniqueId());
        EntityType type;
        try {
            type = EntityType.valueOf(c.value());
        } catch (IllegalArgumentException e) {
            lang.send(p, plugin, "cosmetic.broken", Map.of("name", plain(c.name())));
            return;
        }
        if (!type.isAlive() || !type.isSpawnable()) {
            lang.send(p, plugin, "cosmetic.broken", Map.of("name", plain(c.name())));
            return;
        }
        Entity e = p.getWorld().spawnEntity(p.getLocation(), type);
        e.setPersistent(false);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.customName(lang.msg(plugin, "cosmetic.pet-name", Map.of("player", p.getName(), "name", plain(c.name()))));
        e.setCustomNameVisible(true);
        e.getPersistentDataContainer().set(tag, PersistentDataType.STRING, p.getUniqueId().toString());
        if (e instanceof Tameable t) {
            t.setTamed(true);
            t.setOwner(p);
        }
        if (e instanceof LivingEntity le) {
            le.setRemoveWhenFarAway(false);
            le.setCollidable(false);
        }
        pets.put(p.getUniqueId(), e);
    }

    private void removePet(UUID owner) {
        Entity e = pets.remove(owner);
        if (e != null && e.isValid()) e.remove();
    }

    void removeAllPets() {
        pets.values().forEach(e -> {
            if (e.isValid()) e.remove();
        });
        pets.clear();
    }

    /** Co sekundę: pupil idzie za właścicielem, a gdy za daleko (albo inny świat) - teleport. */
    void tickPets() {
        for (Map.Entry<UUID, Entity> en : List.copyOf(pets.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            Entity e = en.getValue();
            if (p == null || !e.isValid()) {
                removePet(en.getKey());
                continue;
            }
            if (e.getWorld() != p.getWorld() || e.getLocation().distanceSquared(p.getLocation()) > 144) {
                e.teleport(p.getLocation());
            } else if (e instanceof Mob mob && e.getLocation().distanceSquared(p.getLocation()) > 9) {
                Pathfinder pf = mob.getPathfinder();
                pf.moveTo(p.getLocation(), 1.2);
            }
        }
    }

    // ---- smugi ----

    /** Co kilka ticków: cząsteczki pod stopami idących graczy z założoną smugą. */
    void tickTrails() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Map<Cosmetic.Type, String> mine = equipped.get(p.getUniqueId());
            String id = mine == null ? null : mine.get(Cosmetic.Type.TRAIL);
            if (id == null) continue;
            Location now = p.getLocation();
            Location before = lastPos.put(p.getUniqueId(), now.clone());
            if (before == null || before.getWorld() != now.getWorld() || before.distanceSquared(now) < 0.01) continue;
            Cosmetic c = byId(id);
            if (c == null) continue;
            Particle particle;
            try {
                particle = Particle.valueOf(c.value());
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (particle.getDataType() != Void.class) continue; // cząsteczki wymagające danych pomijamy
            p.getWorld().spawnParticle(particle, now.clone().add(0, 0.15, 0), 3, 0.2, 0.05, 0.2, 0.01);
        }
    }

    // ---- okno /cosmetics ----

    static final class Gui implements InventoryHolder {
        Inventory inventory;
        Cosmetic.Type tab = Cosmetic.Type.HAT;
        final Map<Integer, Cosmetic> slots = new HashMap<>();

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    void open(Player p, Cosmetic.Type tab) {
        Gui gui = new Gui();
        gui.tab = tab;
        gui.inventory = Bukkit.createInventory(gui, 54, lang.msg(plugin, "menu.title"));
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of(), false);
        for (int i = 0; i < 54; i++) gui.inventory.setItem(i, filler);
        Material[] tabIcons = {Material.LEATHER_HELMET, Material.BLAZE_POWDER, Material.LEAD};
        String[] tabKeys = {"menu.tab-hats", "menu.tab-trails", "menu.tab-pets"};
        for (int i = 0; i < 3; i++) {
            boolean on = Cosmetic.Type.values()[i] == tab;
            gui.inventory.setItem(2 + i * 2, item(tabIcons[i], lang.msg(plugin, tabKeys[i]), List.of(lang.msg(plugin, on ? "menu.tab-open" : "menu.tab-click")), on));
        }
        Map<Cosmetic.Type, String> mine = equipped.getOrDefault(p.getUniqueId(), Map.of());
        int slot = 18;
        for (Cosmetic c : cosmetics) {
            if (c.type() != tab || slot > 44) continue;
            String reason = lockReason(p, c);
            boolean on = c.id().equals(mine.get(tab));
            List<Component> lore = new ArrayList<>();
            lore.add(lang.msg(plugin, reason == null ? (on ? "menu.on" : "menu.click") : reason));
            Material icon = Material.matchMaterial(c.icon());
            gui.inventory.setItem(slot, item(reason != null ? Material.GRAY_DYE : icon != null && icon.isItem() ? icon : Material.PAPER, legacy(c.name()), lore, on));
            gui.slots.put(slot, c);
            slot++;
        }
        if (gui.slots.isEmpty()) gui.inventory.setItem(31, item(Material.BARRIER, lang.msg(plugin, "menu.empty"), List.of(), false));
        p.openInventory(gui.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        // Czapka-kosmetyk nie do zdjęcia ani przełożenia.
        if (isCosmetic(e.getCurrentItem()) || isCosmetic(e.getCursor())) {
            if (!(e.getInventory().getHolder(false) instanceof Gui)) {
                e.setCancelled(true);
                return;
            }
        }
        if (!(e.getInventory().getHolder(false) instanceof Gui gui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int s = e.getRawSlot();
        if (s == 2 || s == 4 || s == 6) {
            open(p, Cosmetic.Type.values()[(s - 2) / 2]);
            return;
        }
        Cosmetic c = gui.slots.get(s);
        if (c != null) {
            toggle(p, c);
            open(p, gui.tab);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder(false) instanceof Gui || isCosmetic(e.getOldCursor())) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isCosmetic(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    /** Czapka nie wypada przy śmierci; pupil zostaje (jest nieśmiertelny). */
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isCosmetic);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline()) restore(e.getPlayer());
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (isCosmetic(p.getInventory().getHelmet())) p.getInventory().setHelmet(null);
        removePet(p.getUniqueId());
        lastPos.remove(p.getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Map<Cosmetic.Type, String> mine = equipped.get(e.getPlayer().getUniqueId());
        if (mine != null && mine.containsKey(Cosmetic.Type.PET)) {
            Cosmetic c = byId(mine.get(Cosmetic.Type.PET));
            if (c != null) spawnPet(e.getPlayer(), c);
        }
    }

    // Pupil: nie do zranienia, nie atakuje, nie do "używania" przez innych.
    private boolean isPet(Entity e) {
        return e.getPersistentDataContainer().has(tag, PersistentDataType.STRING) && pets.containsValue(e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPetDamage(EntityDamageEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPetTarget(EntityTargetEvent e) {
        if (isPet(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPetInteract(PlayerInteractEntityEvent e) {
        if (isPet(e.getRightClicked())) e.setCancelled(true);
    }

    // ---- pomocnicze ----

    private static Component legacy(String text) {
        return SER.deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    private static String plain(String legacyText) {
        return legacyText.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    private static ItemStack item(Material mat, Component name, List<Component> lore, boolean glow) {
        ItemStack it = new ItemStack(mat);
        it.setData(DataComponentTypes.CUSTOM_NAME, name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (Component c : lore) lines.add(c.decoration(TextDecoration.ITALIC, false));
            it.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        }
        if (glow) it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return it;
    }
}
