package elo.mainplugins.redstone.network;

import elo.mainplugins.redstone.block.BlockKey;
import elo.mainplugins.redstone.block.DeviceStore;
import elo.mainplugins.redstone.block.PlacedDevice;
import elo.mainplugins.redstone.item.RedstoneItemKind;
import elo.mainplugins.redstone.planter.CropMapping;
import elo.mainplugins.redstone.planter.PlanterManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Silnik dwóch typów stacji z latającym "dronem". Obie MUSZĄ być zasilone prawdziwym
 * redstone na WŁASNYM bloku (patrz {@link RedstonePower}), inaczej dron się nie pojawia:
 *
 * - STATION (sadzenie): musi mieć nieprzerwany łańcuch Kabla Przesyłowego
 *   ({@link CableNetwork}) do jakiejś Sadzarki. Dron (ikona ItemDisplay) lata po siatce
 *   5x5 wokół pola TEJ sadzarki - gdy trafi na puste Farmland, sadzarka dostaje polecenie
 *   zasiania dokładnie tam.
 * - HARVESTER (zbieranie): musi mieć łańcuch Kabla do PRAWDZIWEJ Skrzynki. Dron to Allay
 *   z wyłączonym AI, sterowany deterministycznie po siatce 5x5 wokół WŁASNEGO bloku -
 *   dojrzałe uprawy zbiera do podłączonej skrzynki.
 *
 * Dron jest nie-persystentny i żyje wyłącznie w pamięci tej klasy (mapa po pozycji stacji).
 */
public final class StationManager {

    private static final int SIATKA_BOK = 5;
    private static final int SIATKA_POL = SIATKA_BOK * SIATKA_BOK;
    private static final double WYSOKOSC_LOTU = 1.5;
    private static final int CZAS_LOTU_TICKOW = 30;

    private final DeviceStore store;
    private final PlanterManager planterManager;
    private final Map<BlockKey, DronStan> drony = new HashMap<>();

    public StationManager(DeviceStore store, PlanterManager planterManager) {
        this.store = store;
        this.planterManager = planterManager;
    }

    /** Wołane cyklicznie (patrz MainpluginsRedstone#onEnable). */
    public void tick() {
        for (PlacedDevice dev : List.copyOf(store.allDevices())) {
            if (!dev.key.chunkZaladowany()) continue;
            if (dev.kind == RedstoneItemKind.STATION) processPlantingStation(dev);
            else if (dev.kind == RedstoneItemKind.HARVESTER) processHarvesterStation(dev);
        }
        drony.entrySet().removeIf(e -> {
            if (store.getDevice(e.getKey()) != null) return false;
            e.getValue().usun();
            return true;
        });
    }

    private void processPlantingStation(PlacedDevice station) {
        if (!aktywna(station)) return;

        PlacedDevice planter = CableNetwork.findReachablePlanter(station, store);
        if (planter == null || planter.farmland == null) {
            usunDrona(station.key);
            return;
        }
        Block centrum = planter.farmland.toBlock();
        if (centrum == null) {
            usunDrona(station.key);
            return;
        }

        Block cel = nastepnyKafelek(station, centrum);
        if (cel == null) return;

        if (cel.getType() == Material.FARMLAND && cel.getRelative(BlockFace.UP).getType() == Material.AIR) {
            planterManager.trigger(planter, cel);
        }
    }

    private void processHarvesterStation(PlacedDevice harvester) {
        if (!aktywna(harvester)) return;

        Block skrzynkaBlok = CableNetwork.findReachableChest(harvester, store);
        if (skrzynkaBlok == null) {
            usunDrona(harvester.key);
            return;
        }

        Block centrum = harvester.key.toBlock();
        if (centrum == null) return;
        Block cel = nastepnyKafelek(harvester, centrum);
        if (cel == null) return;

        if (CropMapping.isMatureHarvestable(cel) && skrzynkaBlok.getState() instanceof Container kontener) {
            Collection<ItemStack> drop = cel.getDrops();
            cel.setType(Material.AIR);
            for (ItemStack item : drop) {
                var leftover = kontener.getInventory().addItem(item);
                leftover.values().forEach(i -> skrzynkaBlok.getWorld().dropItemNaturally(skrzynkaBlok.getLocation(), i));
            }
        }
    }

    private boolean aktywna(PlacedDevice station) {
        Block blok = station.key.toBlock();
        if (blok == null || !RedstonePower.isPowered(blok)) {
            usunDrona(station.key);
            return false;
        }
        return true;
    }

    /** Zapewnia drona, przesuwa go na kolejny kafelek siatki 5x5 wokół `centrum`, zwraca ten kafelek. */
    private Block nastepnyKafelek(PlacedDevice station, Block centrum) {
        DronStan stan = drony.get(station.key);
        if (stan == null || !stan.zyje()) {
            Entity dron = station.kind == RedstoneItemKind.HARVESTER ? spawnDronaZbierania(station) : spawnDronaSadzenia(station);
            if (dron == null) return null;
            stan = new DronStan(dron);
            drony.put(station.key, stan);
        }

        int cursor = stan.nastepnyKursor();
        int polowa = SIATKA_BOK / 2;
        int dx = (cursor % SIATKA_BOK) - polowa;
        int dz = (cursor / SIATKA_BOK) - polowa;
        Block cel = centrum.getRelative(dx, 0, dz);

        stan.lecDo(cel.getLocation().add(0.5, WYSOKOSC_LOTU, 0.5));
        return cel;
    }

    private ItemDisplay spawnDronaSadzenia(PlacedDevice station) {
        Block blok = station.key.toBlock();
        if (blok == null) return null;
        Location start = blok.getLocation().add(0.5, 1.0, 0.5);
        World world = start.getWorld();

        return world.spawn(start, ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(Material.ENDER_EYE));
            e.setBillboard(Display.Billboard.CENTER);
            e.setGlowing(true);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setTeleportDuration(CZAS_LOTU_TICKOW);
            e.setTransformation(new Transformation(
                    new Vector3f(-0.2f, -0.2f, -0.2f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(0.4f, 0.4f, 0.4f),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
        });
    }

    private Allay spawnDronaZbierania(PlacedDevice station) {
        Block blok = station.key.toBlock();
        if (blok == null) return null;
        Location start = blok.getLocation().add(0.5, 1.0, 0.5);
        World world = start.getWorld();

        return world.spawn(start, Allay.class, e -> {
            e.setAI(false);
            e.setGravity(false);
            e.setSilent(true);
            e.setInvulnerable(true);
            e.setPersistent(false);
            e.setCanPickupItems(false);
            e.setCollidable(false);
        });
    }

    /** Woła DeviceListeners przy zniszczeniu stacji - sprząta drona. */
    public void stopStation(BlockKey key) {
        usunDrona(key);
    }

    private void usunDrona(BlockKey key) {
        DronStan stan = drony.remove(key);
        if (stan != null) stan.usun();
    }

    private static final class DronStan {
        private Entity dron;
        private int kursor = 0;

        private DronStan(Entity dron) {
            this.dron = dron;
        }

        boolean zyje() {
            return dron != null && dron.isValid();
        }

        void lecDo(Location cel) {
            if (zyje()) dron.teleport(cel);
        }

        int nastepnyKursor() {
            int obecny = kursor;
            kursor = (kursor + 1) % SIATKA_POL;
            return obecny;
        }

        void usun() {
            if (dron != null) dron.remove();
            dron = null;
        }
    }
}
