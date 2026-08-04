package elo.mainplugins.skyblock;

import elo.mainplugins.core.util.CustomItemKeys;
import elo.mainplugins.skyblock.event.SnifferPlacedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sniffer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Sniffer Farmera" - specjalny item ze sklepu (kategoria "Itemy Specjalne", custom-id
 * SNIFFER_JAJKO). Użyty (PPM na bloku) na WŁASNEJ wyspie stawia Snifferaa, który co
 * SKAN_ODSTEP_TICKOW automatycznie zbiera i od razu sadzi na nowo dojrzałe uprawy
 * w promieniu PROMIEN_ZBIORU wokół siebie, po czym wrzuca zebrane plony do
 * najbliższej skrzyni w promieniu PROMIEN_SZUKANIA_SKRZYNI - a jeśli żadnej nie ma
 * w zasięgu, po prostu upuszcza je na ziemię pod sobą. Maksymalnie 1 Sniffer na
 * wyspę na raz (patrz onUzyjJajka).
 *
 * CELOWO z wyłączonym AI (setAI(false)) - prawdziwe, autonomiczne pathfindingowe AI
 * na mobach przez Paper API bywa kruche między wersjami serwera. Zamiast tego "rusza
 * się jako tako": co każdy skan losowo TELEPORTUJE się o kilka bloków w obrębie
 * PROMIEN_WEDROWANIA od snifferAnchor (punktu postawienia) - w pełni kontrolowany,
 * przewidywalny ruch (nigdy nie odpłynie z wyspy ani nie utknie), bez zależności od
 * niestabilnego silnika AI.
 */
public class SnifferManager implements Listener {

    private static final String CUSTOM_ID = "SNIFFER_JAJKO";
    private static final int PROMIEN_ZBIORU = 6;
    private static final int WYSOKOSC_ZBIORU = 2; // ile bloków w górę/dół od Snifferaa sprawdzamy - uprawy nie różnią się dużo wysokością
    private static final int PROMIEN_SZUKANIA_SKRZYNI = 8;
    private static final int PROMIEN_WEDROWANIA = 4; // maks. odległość "skoku" od snifferAnchor
    private static final long SKAN_ODSTEP_TICKOW = 100L; // 5s

    private static final Set<Material> UPRAWY = Set.of(
            Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART
    );

    private final IslandManager islandManager;

    public SnifferManager(Plugin plugin, IslandManager islandManager) {
        this.islandManager = islandManager;
        Bukkit.getScheduler().runTaskTimer(plugin, this::skanujWszystkieSniffery, SKAN_ODSTEP_TICKOW, SKAN_ODSTEP_TICKOW);
    }

    private boolean jestJajkiemSnifferaa(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String id = item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
        return CUSTOM_ID.equals(id);
    }

    @EventHandler
    public void onUzyjJajka(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!jestJajkiemSnifferaa(item)) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;

        event.setCancelled(true);

        IslandManager.IslandData wlasna = islandManager.wlasnaWyspaJakoZarzadca(player);
        if (wlasna == null) return; // komunikat o braku uprawnień już wysłany przez wlasnaWyspaJakoZarzadca

        if (islandManager.znajdzWyspePod(clicked.getLocation()) != wlasna) {
            player.sendMessage(Component.text("Możesz postawić Snifferaa tylko na własnej wyspie!", NamedTextColor.RED));
            return;
        }

        if (aktywnySniffer(wlasna) != null) {
            player.sendMessage(Component.text("Twoja wyspa ma już aktywnego Snifferaa Farmera!", NamedTextColor.RED));
            return;
        }

        Location spawnLoc = clicked.getLocation().add(0.5, 1, 0.5);
        Sniffer sniffer = (Sniffer) spawnLoc.getWorld().spawnEntity(spawnLoc, EntityType.SNIFFER);
        sniffer.setAI(false);
        sniffer.setInvulnerable(true);
        sniffer.setPersistent(true);
        sniffer.setRemoveWhenFarAway(false);
        sniffer.customName(Component.text("Sniffer Farmera", NamedTextColor.GREEN));
        sniffer.setCustomNameVisible(true);

        wlasna.setSnifferId(sniffer.getUniqueId());
        wlasna.setSnifferAnchor(spawnLoc.getX(), spawnLoc.getY(), spawnLoc.getZ());
        islandManager.zapiszWszystkieWyspy();
        Bukkit.getPluginManager().callEvent(new SnifferPlacedEvent(player, wlasna));

        item.setAmount(item.getAmount() - 1);
        player.sendMessage(Component.text("Postawiono Snifferaa Farmera! Będzie automatycznie zbierał i sadził uprawy w pobliżu.", NamedTextColor.GREEN));
    }

    private Entity aktywnySniffer(IslandManager.IslandData data) {
        UUID id = data.getSnifferId();
        if (id == null) return null;
        Entity entity = Bukkit.getEntity(id);
        return (entity != null && entity.isValid()) ? entity : null;
    }

    /** Wołane cyklicznie (patrz konstruktor) - jeden przebieg po wszystkich wyspach z aktywnym Snifferem. */
    private void skanujWszystkieSniffery() {
        for (IslandManager.IslandData data : islandManager.wszystkieWyspy()) {
            Entity sniffer = aktywnySniffer(data);
            if (sniffer == null) continue; // brak Snifferaa albo jego chunk aktualnie niezaładowany - pomiń ten przebieg
            zbierzIZasadzWokol(sniffer.getLocation());
            sprobujPrzeniesc(sniffer, data);
        }
    }

    /** Kosmetyczny "skok" o kilka bloków w obrębie kotwicy - patrz komentarz klasy. Cicho pomija, jeśli nie znajdzie stabilnego gruntu. */
    private void sprobujPrzeniesc(Entity sniffer, IslandManager.IslandData data) {
        if (!data.hasSnifferAnchor()) return;

        World world = sniffer.getWorld();
        int anchorX = (int) Math.floor(data.getSnifferAnchorX());
        int anchorY = (int) Math.floor(data.getSnifferAnchorY());
        int anchorZ = (int) Math.floor(data.getSnifferAnchorZ());

        ThreadLocalRandom random = ThreadLocalRandom.current();
        int dx = random.nextInt(-PROMIEN_WEDROWANIA, PROMIEN_WEDROWANIA + 1);
        int dz = random.nextInt(-PROMIEN_WEDROWANIA, PROMIEN_WEDROWANIA + 1);
        if (dx == 0 && dz == 0) return; // brak ruchu w tym przebiegu - Sniffer zostaje na miejscu

        Location docelowa = znajdzStabilnyGrunt(world, anchorX + dx, anchorY, anchorZ + dz);
        if (docelowa == null) return; // brak bezpiecznego miejsca w tym kierunku - spróbujemy przy następnym skanie

        docelowa.setYaw(obliczYaw(sniffer.getLocation(), docelowa));
        sniffer.teleport(docelowa);
    }

    /** Szuka najbliższego (w pionie, wokół okoloY) stabilnego miejsca do stania: solidny grunt + 2 wolne kratki nad nim. */
    private Location znajdzStabilnyGrunt(World world, int x, int okoloY, int z) {
        for (int dy = 2; dy >= -2; dy--) {
            int y = okoloY + dy;
            Block grunt = world.getBlockAt(x, y - 1, z);
            Block nogi = world.getBlockAt(x, y, z);
            Block glowa = world.getBlockAt(x, y + 1, z);
            if (grunt.getType().isSolid() && nogi.getType().isAir() && glowa.getType().isAir()) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private float obliczYaw(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private void zbierzIZasadzWokol(Location centrum) {
        List<ItemStack> zebrane = new ArrayList<>();
        int cx = centrum.getBlockX(), cy = centrum.getBlockY(), cz = centrum.getBlockZ();

        for (int dx = -PROMIEN_ZBIORU; dx <= PROMIEN_ZBIORU; dx++) {
            for (int dy = -WYSOKOSC_ZBIORU; dy <= WYSOKOSC_ZBIORU; dy++) {
                for (int dz = -PROMIEN_ZBIORU; dz <= PROMIEN_ZBIORU; dz++) {
                    Block block = centrum.getWorld().getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!UPRAWY.contains(block.getType())) continue;

                    BlockData blockData = block.getBlockData();
                    if (!(blockData instanceof Ageable ageable)) continue;
                    if (ageable.getAge() < ageable.getMaximumAge()) continue; // jeszcze niedojrzałe - pomiń

                    zebrane.addAll(block.getDrops());
                    ageable.setAge(0);
                    block.setBlockData(ageable); // od razu zasadzone na nowo, ta sama farmland/soul sand pod spodem zostaje nietknięta
                }
            }
        }

        if (!zebrane.isEmpty()) {
            dostarczDoSkrzyniLubNaZiemie(centrum, zebrane);
        }
    }

    private void dostarczDoSkrzyniLubNaZiemie(Location centrum, List<ItemStack> przedmioty) {
        Inventory skrzynia = znajdzNajblizszaSkrzynie(centrum);
        if (skrzynia == null) {
            for (ItemStack item : przedmioty) {
                centrum.getWorld().dropItemNaturally(centrum, item);
            }
            return;
        }

        for (ItemStack item : przedmioty) {
            Map<Integer, ItemStack> reszta = skrzynia.addItem(item);
            for (ItemStack niezmieszczone : reszta.values()) {
                centrum.getWorld().dropItemNaturally(centrum, niezmieszczone);
            }
        }
    }

    /** Najbliższa (w linii prostej) skrzynia/beczka w promieniu PROMIEN_SZUKANIA_SKRZYNI - albo null, jeśli żadnej nie ma. */
    private Inventory znajdzNajblizszaSkrzynie(Location centrum) {
        int cx = centrum.getBlockX(), cy = centrum.getBlockY(), cz = centrum.getBlockZ();
        Block najblizszy = null;
        double najlepszyDystansKw = Double.MAX_VALUE;

        for (int dx = -PROMIEN_SZUKANIA_SKRZYNI; dx <= PROMIEN_SZUKANIA_SKRZYNI; dx++) {
            for (int dy = -PROMIEN_SZUKANIA_SKRZYNI; dy <= PROMIEN_SZUKANIA_SKRZYNI; dy++) {
                for (int dz = -PROMIEN_SZUKANIA_SKRZYNI; dz <= PROMIEN_SZUKANIA_SKRZYNI; dz++) {
                    Block block = centrum.getWorld().getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!(block.getState() instanceof Chest)) continue;

                    double dystansKw = dx * dx + dy * dy + dz * dz;
                    if (dystansKw < najlepszyDystansKw) {
                        najlepszyDystansKw = dystansKw;
                        najblizszy = block;
                    }
                }
            }
        }

        if (najblizszy == null) return null;
        BlockState state = najblizszy.getState();
        return state instanceof InventoryHolder holder ? holder.getInventory() : null;
    }
}