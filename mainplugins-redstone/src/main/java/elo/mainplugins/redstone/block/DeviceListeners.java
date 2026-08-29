package elo.mainplugins.redstone.block;

import elo.mainplugins.redstone.chestlink.GolemManager;
import elo.mainplugins.redstone.item.RedstoneItemKind;
import elo.mainplugins.redstone.item.RedstoneItemManager;
import elo.mainplugins.redstone.network.CableNetwork;
import elo.mainplugins.redstone.network.RedstonePower;
import elo.mainplugins.redstone.network.StationManager;
import elo.mainplugins.redstone.planter.PlanterManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Cały cykl życia postawionych urządzeń/kabli jako PRAWDZIWYCH bloków:
 * <ul>
 *   <li>{@link BlockPlaceEvent} - postawiono redstone-item: rejestracja w {@link DeviceStore}
 *       (Sadzarka wymaga sąsiedniego Farmland, inaczej anulujemy postawienie),</li>
 *   <li>{@link BlockBreakEvent} - zwrot naszego itemu zamiast wanilijskiego dropu, stop
 *       dronów/golema, wyrejestrowanie,</li>
 *   <li>wybuch / tłok / pożar / zniknięcie / spływająca woda - instalacje są chronione
 *       (event anulowany albo blok wyjęty z listy zniszczeń),</li>
 *   <li>{@link PlayerInteractEvent} PPM - otwiera nasze GUI / pokazuje status, blokując
 *       wanilijskie zachowanie bloku bazowego.</li>
 * </ul>
 */
public final class DeviceListeners implements Listener {

    private static final BlockFace[] SZESC = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final RedstoneItemManager items;
    private final DeviceStore store;
    private final PlanterManager planterManager;
    private final StationManager stationManager;
    private final GolemManager golemManager;

    public DeviceListeners(RedstoneItemManager items, DeviceStore store, PlanterManager planterManager,
                           StationManager stationManager, GolemManager golemManager) {
        this.items = items;
        this.store = store;
        this.planterManager = planterManager;
        this.stationManager = stationManager;
        this.golemManager = golemManager;
    }

    // ---------- stawianie ----------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        RedstoneItemKind kind = items.kindOf(hand);
        if (kind == null) return;

        if (kind == RedstoneItemKind.CHEST_LINKER) {
            event.setCancelled(true); // łącznik nigdy nie jest blokiem - patrz ChestLinkerListener
            return;
        }

        Block b = event.getBlockPlaced();
        BlockKey key = BlockKey.of(b);
        String itemId = items.idOf(hand);
        if (itemId == null) return;

        if (kind == RedstoneItemKind.CABLE) {
            store.placeCable(key, itemId);
            return;
        }

        if (kind == RedstoneItemKind.PLANTER) {
            BlockKey farmland = szukajFarmland(b);
            if (farmland == null) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(Component.text("Sadzarkę trzeba postawić obok zaoranej ziemi (Farmland).", NamedTextColor.RED));
                return;
            }
            PlacedDevice d = new PlacedDevice(key, kind, itemId);
            d.farmland = farmland;
            store.place(d);
            return;
        }

        store.place(new PlacedDevice(key, kind, itemId));
    }

    private BlockKey szukajFarmland(Block anchor) {
        for (BlockFace face : SZESC) {
            Block s = anchor.getRelative(face);
            if (s.getType() == Material.FARMLAND) return BlockKey.of(s);
        }
        return null;
    }

    // ---------- rozbieranie ----------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockKey key = BlockKey.of(block);
        Player player = event.getPlayer();

        if (store.isCable(key)) {
            event.setDropItems(false);
            oddaj(player, items.create(store.cableItemId(key), 1));
            store.removeCable(key);
            return;
        }

        PlacedDevice d = store.getDevice(key);
        if (d != null) {
            event.setDropItems(false);
            oddaj(player, items.create(d.itemId, 1));
            rozbierz(d);
            return;
        }

        // Rozbito blok-podporę pod naszym kablem (pył redstone) - kabel by "wypadł" jako
        // wanilijski redstone. Przechwytujemy: usuwamy z bazy, oddajemy nasz Kabel, zerujemy blok.
        BlockKey nad = key.przesun(0, 1, 0);
        if (store.isCable(nad)) {
            oddaj(player, items.create(store.cableItemId(nad), 1));
            store.removeCable(nad);
            Block b = nad.toBlock();
            if (b != null) b.setType(Material.AIR, false);
        }
    }

    private void rozbierz(PlacedDevice d) {
        switch (d.kind) {
            case PLANTER -> planterManager.dropStoredSeeds(d);
            case STATION, HARVESTER -> stationManager.stopStation(d.key);
            case GOLEM_STATION -> golemManager.stopStation(d.key);
            default -> { }
        }
        store.removeDevice(d.key);
    }

    private void oddaj(Player player, ItemStack item) {
        if (item == null) return;
        player.getInventory().addItem(item).values()
                .forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
    }

    // ---------- ochrona przed zniszczeniem nie-przez-gracza ----------

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::nasze);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::nasze);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::nasze)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::nasze)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (nasze(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (nasze(event.getBlock())) event.setCancelled(true);
    }

    /** Spływająca woda/lawa nie zmywa cienkiego kabla ani urządzeń. */
    @EventHandler(ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (nasze(event.getToBlock())) event.setCancelled(true);
    }

    /** Np. enderman podnoszący blok. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (nasze(event.getBlock())) event.setCancelled(true);
    }

    /**
     * Kabel to wizualnie pył redstone (reskin z resourcepacka), ale NIE ma przewodzić prądu -
     * przy każdej aktualizacji redstone zerujemy jego sygnał, więc nic nie zasila i nie
     * "świeci" jasno. Zwykły redstone gracza obok działa normalnie (nie jest naszym kablem).
     */
    @EventHandler
    public void onRedstone(BlockRedstoneEvent event) {
        if (store.isCable(BlockKey.of(event.getBlock()))) {
            event.setNewCurrent(0);
        }
    }

    private boolean nasze(Block b) {
        BlockKey k = BlockKey.of(b);
        return store.isCable(k) || store.getDevice(k) != null;
    }

    // ---------- interakcja (GUI / status) ----------

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = event.getClickedBlock();
        if (b == null) return;

        PlacedDevice d = store.getDevice(BlockKey.of(b));
        if (d == null) return;

        Player player = event.getPlayer();
        // sneak + coś w ręce = pozwól budować "o" blok, nie otwieraj GUI
        if (player.isSneaking() && !player.getInventory().getItemInMainHand().getType().isAir()) return;

        event.setCancelled(true);
        switch (d.kind) {
            case PLANTER -> planterManager.openGui(player, d);
            case STATION -> statusStacji(player, d, "Sadzarki");
            case HARVESTER -> statusStacji(player, d, "skrzynki");
            case GOLEM_STATION -> player.sendActionBar(Component.text("Stacja Zbiorcza - postaw obok skrzynki źródłowej połączonej Łącznikiem.", NamedTextColor.GOLD));
            default -> { }
        }
    }

    private void statusStacji(Player player, PlacedDevice d, String celNazwa) {
        Block blok = d.key.toBlock();
        boolean zasilona = blok != null && RedstonePower.isPowered(blok);
        boolean polaczona = d.kind == RedstoneItemKind.STATION
                ? CableNetwork.findReachablePlanter(d, store) != null
                : CableNetwork.findReachableChest(d, store) != null;

        player.sendMessage(Component.text("» Redstone: ", NamedTextColor.GRAY)
                .append(Component.text(zasilona ? "ZASILONA" : "BRAK ZASILANIA", zasilona ? NamedTextColor.GREEN : NamedTextColor.RED)));
        player.sendMessage(Component.text("» Kabel do " + celNazwa + ": ", NamedTextColor.GRAY)
                .append(Component.text(polaczona ? "POŁĄCZONA" : "BRAK POŁĄCZENIA", polaczona ? NamedTextColor.GREEN : NamedTextColor.RED)));
        if (zasilona && polaczona) {
            player.sendMessage(Component.text("» Dron pracuje.", NamedTextColor.GREEN));
        }
    }
}
