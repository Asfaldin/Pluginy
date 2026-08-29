package elo.mainplugins.redstone.chestlink;

import elo.mainplugins.redstone.block.BlockKey;
import elo.mainplugins.redstone.block.DeviceStore;
import elo.mainplugins.redstone.block.PlacedDevice;
import elo.mainplugins.redstone.item.RedstoneItemKind;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stacja Zbiorcza: postawiona jako blok obok skrzynki źródłowej połączonej Łącznikiem
 * Skrzynek (patrz {@link ChestLink}) wypuszcza "golema" (wizualna, nie-persystentna encja
 * ItemDisplay) kursującego między źródłem a celem - po jednym stacku na kurs. Bez
 * kabla/redstone.
 */
public final class GolemManager {

    private static final BlockFace[] SZESC_SCIAN = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final int CZAS_LOTU_TICKOW = 30;

    private final DeviceStore store;
    private final Map<BlockKey, GolemStan> golemy = new HashMap<>();

    public GolemManager(DeviceStore store) {
        this.store = store;
    }

    public void tick() {
        for (PlacedDevice dev : List.copyOf(store.allDevices())) {
            if (dev.kind != RedstoneItemKind.GOLEM_STATION) continue;
            if (!dev.key.chunkZaladowany()) continue;
            processGolemStation(dev);
        }
        golemy.entrySet().removeIf(e -> {
            if (store.getDevice(e.getKey()) != null) return false;
            e.getValue().usun();
            return true;
        });
    }

    private void processGolemStation(PlacedDevice stacja) {
        Block blok = stacja.key.toBlock();
        if (blok == null) return;

        Block zrodlo = znajdzPolaczonaSkrzynke(blok);
        if (zrodlo == null) {
            usunGolema(stacja.key);
            return;
        }
        Block cel = ChestLink.target(zrodlo);
        if (cel == null || !jestSkrzynka(cel)) {
            usunGolema(stacja.key);
            return;
        }
        if (!(zrodlo.getState() instanceof Container zrodloKontener) || !(cel.getState() instanceof Container celKontener)) {
            usunGolema(stacja.key);
            return;
        }

        GolemStan stan = golemy.get(stacja.key);
        if (stan == null || !stan.zyje()) {
            ItemDisplay golem = spawnGolema(zrodlo.getLocation());
            if (golem == null) return;
            stan = new GolemStan(golem);
            golemy.put(stacja.key, stan);
        }

        Location punktZrodlo = zrodlo.getLocation().add(0.5, 1.0, 0.5);
        Location punktCel = cel.getLocation().add(0.5, 1.0, 0.5);

        if (stan.ladunek == null) {
            ItemStack wziete = zabierzZeSkrzynki(zrodloKontener.getInventory());
            if (wziete == null) return;
            stan.ladunek = wziete;
            stan.dron.setItemStack(wziete);
            stan.dron.teleport(punktCel);
        } else {
            wlozDoSkrzynki(celKontener.getInventory(), cel, stan.ladunek);
            stan.ladunek = null;
            stan.dron.setItemStack(new ItemStack(Material.AIR));
            stan.dron.teleport(punktZrodlo);
        }
    }

    private Block znajdzPolaczonaSkrzynke(Block anchor) {
        for (BlockFace face : SZESC_SCIAN) {
            Block sasiad = anchor.getRelative(face);
            if (jestSkrzynka(sasiad) && ChestLink.target(sasiad) != null) return sasiad;
        }
        return null;
    }

    private boolean jestSkrzynka(Block block) {
        return block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST;
    }

    private ItemStack zabierzZeSkrzynki(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() != Material.AIR) {
                inv.setItem(i, null);
                return it;
            }
        }
        return null;
    }

    private void wlozDoSkrzynki(Inventory inv, Block skrzynkaBlok, ItemStack item) {
        var leftover = inv.addItem(item);
        leftover.values().forEach(i -> skrzynkaBlok.getWorld().dropItemNaturally(skrzynkaBlok.getLocation(), i));
    }

    private ItemDisplay spawnGolema(Location przy) {
        World world = przy.getWorld();
        if (world == null) return null;
        Location start = przy.clone().add(0.5, 1.0, 0.5);

        return world.spawn(start, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.IRON_INGOT));
            e.setBillboard(Display.Billboard.CENTER);
            e.setGlowing(true);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setTeleportDuration(CZAS_LOTU_TICKOW);
            e.setTransformation(new Transformation(
                    new Vector3f(-0.25f, -0.25f, -0.25f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(0.5f, 0.5f, 0.5f),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
        });
    }

    /** Woła DeviceListeners przy zniszczeniu stacji zbiorczej. */
    public void stopStation(BlockKey key) {
        usunGolema(key);
    }

    private void usunGolema(BlockKey key) {
        GolemStan stan = golemy.remove(key);
        if (stan != null) stan.usun();
    }

    private static final class GolemStan {
        private ItemDisplay dron;
        private ItemStack ladunek;

        private GolemStan(ItemDisplay dron) {
            this.dron = dron;
        }

        boolean zyje() {
            return dron != null && dron.isValid();
        }

        void usun() {
            if (dron != null) dron.remove();
            dron = null;
        }
    }
}
