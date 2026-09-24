package elo.mainplugins.shop;

import elo.mainplugins.core.api.LangService;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * NPC i tabliczki, które otwierają sklep albo jedną kategorię. Nic nie trzyma w plikach:
 * cel ("" = menu główne, inaczej id kategorii) siedzi w samym mobie / tabliczce (PersistentData),
 * więc przeżywa restart i znika razem z nimi. Stawia je admin komendą /@shop npc | sign.
 */
final class ShopPlaces implements Listener {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();
    private static final int REACH = 5;

    private final Plugin plugin;
    private final LangService lang;
    private final ShopManager shop;
    /** id kategorii -> nazwa z kolorami (null = nie ma takiej kategorii). */
    private final Function<String, String> categoryName;
    private final NamespacedKey key;
    /** Stojak na zbroję wysyła dwa zdarzenia na jedno kliknięcie - drugie pomijamy. */
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    ShopPlaces(Plugin plugin, LangService lang, ShopManager shop, Function<String, String> categoryName) {
        this.plugin = plugin;
        this.lang = lang;
        this.shop = shop;
        this.categoryName = categoryName;
        this.key = new NamespacedKey(plugin, "opens");
    }

    // ---------- wspólne ----------

    /** Cel zapisany w mobie / tabliczce: "" = menu główne, id kategorii, null = to nie nasze. */
    private String target(PersistentDataContainer pdc) {
        return pdc.get(key, PersistentDataType.STRING);
    }

    private void open(Player player, String target) {
        long now = System.currentTimeMillis();
        Long last = lastOpen.put(player.getUniqueId(), now);
        if (last != null && now - last < 250) return;
        shop.openFor(player, target.isEmpty() ? null : target);
    }

    /** Tekst dla admina: "menu główne" albo "kategoria Bloki". */
    String describe(String target) {
        if (target.isEmpty()) return plainText("places.target-main", Map.of());
        String name = categoryName.apply(target);
        return plainText("places.target-category", Map.of("category", stripColors(name == null ? target : name)));
    }

    private String plainText(String langKey, Map<String, String> ph) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(lang.msg(plugin, langKey, ph));
    }

    private static String stripColors(String s) {
        return s.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
    }

    // ---------- NPC ----------

    /** Mob, który może być NPC: żywy, da się go postawić, nie gracz. Null = nie. */
    static EntityType npcType(String raw) {
        EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT)));
        if (type == null || type == EntityType.PLAYER || !type.isSpawnable() || !type.isAlive()) return null;
        return type;
    }

    static Villager.Profession profession(String raw) {
        return Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT)));
    }

    /** Domyślna nazwa NPC z tekstów (lang): "Sklep" albo nazwa kategorii. */
    String defaultName(String target) {
        if (target.isEmpty()) return plainLegacy("places.npc-name-main", Map.of());
        String name = categoryName.apply(target);
        return plainLegacy("places.npc-name-category", Map.of("category", name == null ? target : name));
    }

    private String plainLegacy(String langKey, Map<String, String> ph) {
        return SER.serialize(lang.msg(plugin, langKey, ph));
    }

    LivingEntity createNpc(Location at, EntityType type, String target, String name) {
        Entity e = at.getWorld().spawn(at, type.getEntityClass(), spawned -> setUp(spawned, target, name));
        return (LivingEntity) e;
    }

    private void setUp(Entity e, String target, String name) {
        e.getPersistentDataContainer().set(key, PersistentDataType.STRING, target);
        e.setInvulnerable(true);
        e.setSilent(true);
        e.setPersistent(true);
        rename(e, name);
        if (e instanceof LivingEntity le) {
            le.setAI(false);
            le.setRemoveWhenFarAway(false);
            le.setCanPickupItems(false);
            le.setCollidable(false);
        }
    }

    void rename(Entity e, String name) {
        e.customName(SER.deserialize(name).decoration(TextDecoration.ITALIC, false));
        e.setCustomNameVisible(true);
    }

    /** Nowy wygląd = nowy mob w tym samym miejscu, z tą samą nazwą i celem; stary znika. */
    LivingEntity changeType(LivingEntity old, EntityType type) {
        String target = target(old.getPersistentDataContainer());
        String name = old.customName() == null ? defaultName(target) : SER.serialize(old.customName());
        LivingEntity fresh = createNpc(old.getLocation(), type, target, name);
        old.remove();
        return fresh;
    }

    void retarget(Entity e, String target) {
        e.getPersistentDataContainer().set(key, PersistentDataType.STRING, target);
    }

    /** NPC sklepu, na którego patrzy gracz, albo null. */
    LivingEntity lookedAtNpc(Player p) {
        Entity e = p.getTargetEntity(REACH);
        return e instanceof LivingEntity le && target(le.getPersistentDataContainer()) != null ? le : null;
    }

    String npcTarget(Entity e) {
        return target(e.getPersistentDataContainer());
    }

    private boolean isNpc(Entity e) {
        return target(e.getPersistentDataContainer()) != null;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onNpcClick(PlayerInteractEntityEvent event) {
        clickNpc(event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onNpcClickAt(PlayerInteractAtEntityEvent event) {
        clickNpc(event);
    }

    private void clickNpc(PlayerInteractEntityEvent event) {
        String target = target(event.getRightClicked().getPersistentDataContainer());
        if (target == null) return;
        event.setCancelled(true); // bez handlu z wieśniakiem, smyczy, nametagów
        if (event.getHand() == EquipmentSlot.HAND) open(event.getPlayer(), target);
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcDamage(EntityDamageEvent event) {
        if (isNpc(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onNpcBurn(EntityCombustEvent event) {
        if (isNpc(event.getEntity())) event.setCancelled(true);
    }

    /** Piorun nie zamieni wieśniaka w wiedźmę, a świni w zombifikanta. */
    @EventHandler(ignoreCancelled = true)
    public void onNpcTransform(EntityTransformEvent event) {
        if (isNpc(event.getEntity())) event.setCancelled(true);
    }

    // ---------- tabliczki ----------

    /** Tabliczka, na którą patrzy gracz, albo null. */
    Sign lookedAtSign(Player p) {
        Block b = p.getTargetBlockExact(REACH);
        return b != null && Tag.ALL_SIGNS.isTagged(b.getType()) && b.getState() instanceof Sign s ? s : null;
    }

    /** Tabliczka otwiera cel; napisy z tekstów (lang), woskowana, żeby nikt jej nie przepisał. */
    void markSign(Sign sign, String target) {
        sign.getPersistentDataContainer().set(key, PersistentDataType.STRING, target);
        String category = target.isEmpty() ? plainLegacy("places.sign-main", Map.of())
                : stripColors(categoryName.apply(target) == null ? target : categoryName.apply(target));
        var side = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) {
            side.line(i, lang.msg(plugin, "places.sign-line-" + (i + 1), Map.of("category", category)));
        }
        sign.setWaxed(true);
        sign.update();
    }

    /** Zwykła tabliczka z powrotem: bez celu i bez wosku (napisy zostają). */
    boolean unmarkSign(Sign sign) {
        if (target(sign.getPersistentDataContainer()) == null) return false;
        sign.getPersistentDataContainer().remove(key);
        sign.setWaxed(false);
        sign.update();
        return true;
    }

    private String signTarget(Block b) {
        if (!Tag.ALL_SIGNS.isTagged(b.getType()) || !(b.getState(false) instanceof Sign s)) return null;
        return target(s.getPersistentDataContainer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSignClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        String target = signTarget(event.getClickedBlock());
        if (target == null) return;
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) open(event.getPlayer(), target);
    }

    /** Tabliczkę sklepu niszczy tylko admin, i to ze Shiftem - żeby nie zepsuć jej przypadkiem. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignBreak(BlockBreakEvent event) {
        if (signTarget(event.getBlock()) == null) return;
        Player p = event.getPlayer();
        if (p.hasPermission("mainplugins.shop.admin") && p.isSneaking()) {
            lang.send(p, plugin, "places.sign-broken");
            return;
        }
        event.setCancelled(true);
        lang.send(p, plugin, p.hasPermission("mainplugins.shop.admin") ? "places.sign-protected-admin" : "places.sign-protected");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastOpen.remove(event.getPlayer().getUniqueId());
    }
}
