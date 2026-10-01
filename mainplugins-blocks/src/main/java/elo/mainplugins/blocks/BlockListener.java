package elo.mainplugins.blocks;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.SoundGroup;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Własne bloki w świecie: stawianie z przedmiotu, kopanie w tempie z ustawień bloku, dropy,
 * wybuchy. Postawiony blok to note block w stanie z paczki - nic poza nim nie trzeba zapisywać.
 * Note blocki nie mogą zmieniać stanu (strojenie, redstone, blok pod spodem), inaczej własny
 * blok zamieniłby się w inny - stąd blokada aktualizacji i strojenia dla wszystkich note blocków.
 */
public final class BlockListener implements Listener {

    private final BlockRegistry registry;
    private final NamespacedKey speedKey;
    private final BlockData noteBlock = Material.NOTE_BLOCK.createBlockData();

    public BlockListener(Plugin plugin, BlockRegistry registry) {
        this.registry = registry;
        this.speedKey = new NamespacedKey(plugin, "break_speed");
    }

    // --- Stawianie ---

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() == null) return;
        Block clicked = event.getClickedBlock();
        Player player = event.getPlayer();
        BlockDef def = registry.of(event.getItem());
        if (def == null) {
            // Strojenie note blocka zmieniłoby jego stan (i wygląd własnego bloku) - bez strojenia.
            if (clicked.getType() == Material.NOTE_BLOCK && !(player.isSneaking() && event.getItem() != null)) {
                event.setUseInteractedBlock(Event.Result.DENY);
            }
            return;
        }
        // Skrzynia, drzwi itd. otwierają się normalnie (z Shiftem stawiamy blok obok).
        if (clicked.getType().isInteractable() && clicked.getType() != Material.NOTE_BLOCK && !player.isSneaking()) return;
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        place(player, event.getHand(), event.getItem(), clicked, event.getBlockFace(), def);
    }

    private void place(Player player, EquipmentSlot hand, ItemStack stack, Block clicked, BlockFace face, BlockDef def) {
        Block target = clicked.isReplaceable() ? clicked : clicked.getRelative(face);
        if (!target.isReplaceable()) return;
        if (target.getY() < target.getWorld().getMinHeight() || target.getY() >= target.getWorld().getMaxHeight()) return;
        BoundingBox box = BoundingBox.of(target);
        for (Entity e : target.getWorld().getNearbyEntities(box)) {
            if (e instanceof LivingEntity && !(e instanceof Player p && p.getGameMode() == GameMode.SPECTATOR)) return;
        }
        BlockState replaced = target.getState();
        target.setBlockData(registry.blockData(def), false);
        // Zwykłe zdarzenie postawienia - ochrona terenu (WorldGuard, wyspy) może je anulować.
        BlockPlaceEvent place = new BlockPlaceEvent(target, replaced, clicked, stack, player, true, hand);
        place.callEvent();
        if (place.isCancelled() || !place.canBuild()) {
            replaced.update(true, false);
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) stack.subtract(1);
        player.swingHand(hand);
        SoundGroup sounds = sounds(def);
        target.getWorld().playSound(center(target), sounds.getPlaceSound(), (sounds.getVolume() + 1f) / 2f, sounds.getPitch() * 0.8f);
    }

    /** Zwykły note block z ekwipunku dostaje zawsze stan 0 - nigdy nie wygląda jak własny blok. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaPlace(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        if (b.getType() == Material.NOTE_BLOCK && event.getItemInHand().getType() == Material.NOTE_BLOCK) {
            b.setBlockData(registry.vanillaNoteBlock(), false);
        }
    }

    // --- Stan note blocka ---

    @EventHandler(ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        Block b = event.getBlock();
        if (b.getType() == Material.NOTE_BLOCK) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onNote(NotePlayEvent event) {
        if (registry.at(event.getBlock()) != null) event.setCancelled(true);
    }

    // --- Kopanie ---

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        BlockDef def = registry.at(event.getBlock());
        clearSpeed(player);
        if (def == null || player.getGameMode() == GameMode.CREATIVE) return;
        if (def.hardness() == 0) {
            event.setInstaBreak(true);
            return;
        }
        ItemStack tool = event.getItemInHand();
        double note = noteBlock.getDestroySpeed(tool, true);
        double m = BreakSpeed.multiplier(note, toolSpeed(def, tool), def.hardness(), canHarvest(def, tool));
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr == null || Math.abs(m - 1) < 0.001) return;
        attr.addTransientModifier(new AttributeModifier(speedKey, m - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent event) {
        clearSpeed(event.getPlayer());
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        clearSpeed(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        clearSpeed(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSpeed(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        clearSpeed(player);
        BlockDef def = registry.at(event.getBlock());
        if (def == null) return;
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (def.unbreakable() && !creative) {
            event.setCancelled(true);
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        Block b = event.getBlock();
        if (!creative && canHarvest(def, player.getInventory().getItemInMainHand())) {
            for (ItemStack drop : drops(def)) b.getWorld().dropItemNaturally(center(b), drop);
        }
        // Dźwięk drewna gra już sam klient (kopie "note block") - inne zestawy dokładamy.
        if (!def.sound().equals("wood")) {
            SoundGroup sounds = sounds(def);
            b.getWorld().playSound(center(b), sounds.getBreakSound(), (sounds.getVolume() + 1f) / 2f, sounds.getPitch() * 0.8f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        explode(event.blockList(), event.getYield());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        explode(event.blockList(), event.getYield());
    }

    private void explode(List<Block> blocks, float yield) {
        for (Block b : new ArrayList<>(blocks)) {
            BlockDef def = registry.at(b);
            if (def == null) continue;
            blocks.remove(b);
            if (def.unbreakable()) continue;
            b.setType(Material.AIR, false);
            if (ThreadLocalRandom.current().nextFloat() <= yield) {
                for (ItemStack drop : drops(def)) b.getWorld().dropItemNaturally(center(b), drop);
            }
        }
    }

    // --- Pomocnicze ---

    private void clearSpeed(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (attr != null) attr.removeModifier(speedKey);
    }

    /** Blok z gry o tym samym narzędziu - z niego bierzemy szybkość trzymanego przedmiotu (tier, wydajność). */
    private static Material reference(String tool) {
        return switch (tool) {
            case "pickaxe" -> Material.STONE;
            case "axe" -> Material.OAK_PLANKS;
            case "shovel" -> Material.DIRT;
            case "hoe" -> Material.HAY_BLOCK;
            default -> null;
        };
    }

    private static boolean isTool(String tool, ItemStack item) {
        if (item == null) return false;
        Material m = item.getType();
        return switch (tool) {
            case "pickaxe" -> Tag.ITEMS_PICKAXES.isTagged(m);
            case "axe" -> Tag.ITEMS_AXES.isTagged(m);
            case "shovel" -> Tag.ITEMS_SHOVELS.isTagged(m);
            case "hoe" -> Tag.ITEMS_HOES.isTagged(m);
            default -> false;
        };
    }

    private static double toolSpeed(BlockDef def, ItemStack item) {
        Material ref = reference(def.tool());
        if (ref == null || !isTool(def.tool(), item)) return 1;
        return ref.createBlockData().getDestroySpeed(item, true);
    }

    private static boolean canHarvest(BlockDef def, ItemStack item) {
        return !def.requiresTool() || isTool(def.tool(), item);
    }

    private List<ItemStack> drops(BlockDef def) {
        return switch (def.dropType()) {
            case "none" -> List.of();
            case "item" -> {
                Material m = itemType(def.dropItem());
                if (m == null) yield List.of();
                int amount = ThreadLocalRandom.current().nextInt(def.dropMin(), def.dropMax() + 1);
                yield List.of(new ItemStack(m, amount));
            }
            default -> List.of(registry.item(def, 1));
        };
    }

    private static Material itemType(String id) {
        if (id == null) return null;
        NamespacedKey key = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
        if (key == null) return null;
        Material m = Registry.MATERIAL.get(key);
        return m != null && m.isItem() && !m.isAir() ? m : null;
    }

    private static SoundGroup sounds(BlockDef def) {
        Material m = switch (def.sound()) {
            case "wood" -> Material.OAK_PLANKS;
            case "metal" -> Material.IRON_BLOCK;
            case "glass" -> Material.GLASS;
            case "grass" -> Material.GRASS_BLOCK;
            case "gravel" -> Material.GRAVEL;
            case "sand" -> Material.SAND;
            case "wool" -> Material.WHITE_WOOL;
            case "deepslate" -> Material.DEEPSLATE;
            case "amethyst" -> Material.AMETHYST_BLOCK;
            default -> Material.STONE;
        };
        return m.createBlockData().getSoundGroup();
    }

    private static Location center(Block b) {
        return b.getLocation().add(0.5, 0.5, 0.5);
    }
}
