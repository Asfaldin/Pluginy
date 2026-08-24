package elo.mainplugins.spawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.entity.Monster;
import org.bukkit.event.entity.EntityBreakDoorEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.Set;

/**
 * Cała rzeczywista ochrona wnętrza obszarów zdefiniowanych w {@link ObszarManager} -
 * właściciel/członkowie nie istnieją tutaj jak na wyspach, jest tylko "ma uprawnienie
 * mainplugins.spawn.admin (bypass) albo nie ma" (patrz mozeIngerowac). Sekcje poniżej,
 * z grubsza od najbardziej oczywistych do najbardziej "na wszelki wypadek":
 *  - budowanie/niszczenie bloków i cieczy (klasyka - jak w IslandProtectionManager)
 *  - mechanizmy i stacje rzemieślnicze (kowadło/piec/warsztat alchemika/lektern/dzwon...)
 *  - żywe istoty jako źródło zmiany terenu (enderman, owca jedząca trawę, ravager,
 *    silverfish, zombie wyważający drzwi) - jeden ogólny handler zamiast osobnego kodu
 *    dla każdego moba z osobna
 *  - żywioły (ogień, wybuchy, mróz/lód, wzrost roślin) - rzeczy bez sprawcy-gracza,
 *    więc bez wyjątku na bypass tam, gdzie i tak nie ma kogo bypassować
 *  - dekoracje (ramki na przedmioty, obrazy) - osobno "zerwanie" i "zabranie zawartości"
 */
public class ObszarProtectionManager implements Listener {

    private final ObszarManager obszarManager;

    private static final Set<Material> ZABLOKOWANE_MECHANIZMY = Set.of(
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.BREWING_STAND,
            Material.LECTERN,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.ENCHANTING_TABLE,
            Material.LEVER,
            Material.BELL,
            Material.COMPOSTER
    );

    public ObszarProtectionManager(ObszarManager obszarManager) {
        this.obszarManager = obszarManager;
    }

    private boolean mozeIngerowac(Player player) {
        return player.hasPermission("mainplugins.spawn.admin");
    }

    private void odmowa(Player player, String komunikat) {
        player.sendActionBar(Component.text(komunikat, NamedTextColor.RED));
    }

    // ==================================================== Blok/ciecz - klasyka ====

    /**
     * Łapiemy próbę zniszczenia już na SAMYM TAPNIĘCIU (BlockDamageEvent), zanim
     * dojdzie do BlockBreakEvent - bez tego klient Minecrafta zdążał "przewidzieć"
     * zniszczenie bloku (zwłaszcza tych łamanych natychmiast, jak trawa/kwiaty),
     * a gdy serwer cofał to dopiero w BlockBreakEvent, gra sama wypisywała graczowi
     * własny, brzydki komunikat o desynchronizacji z dokładnymi koordynatami bloku
     * nad paskiem doświadczenia. Anulowanie tutaj w ogóle nie dopuszcza do tej
     * sytuacji - blok nigdy nie zaczyna się "kruszyć" po stronie klienta.
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) == null || mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
        odmowa(event.getPlayer(), "Nie możesz tego zniszczyć!");
    }

    /** Zapasowa siatka bezpieczeństwa na wypadek, gdyby coś ominęło onBlockDamage wyżej (np. zniszczenie bloku spoza normalnego klikania). */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) == null || mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
        odmowa(event.getPlayer(), "Nie możesz tego zniszczyć!");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) == null || mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("Nie możesz stawiać bloków w tym miejscu!", NamedTextColor.RED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) == null || mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("Nie możesz zbierać cieczy w tym miejscu!", NamedTextColor.RED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) == null || mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text("Nie możesz wylewać cieczy w tym miejscu!", NamedTextColor.RED));
    }

    /** Wypuszczenie ryby/kijanki/axolotla z wiaderka - osobny event od zwykłego bucket empty. */
    @EventHandler(ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (obszarManager.znajdzObszarPod(event.getEntity().getLocation()) == null || mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
        odmowa(event.getPlayer(), "Nie możesz tego wypuścić w tym miejscu!");
    }

    // ============================================== Mechanizmy i stacje ====

    /**
     * Wszystko, co się otwiera/obsługuje prawym (a dla dzwonu też lewym) kliknięciem:
     * kowadło, piec/hutniczy/wędzarnia, stół alchemika (brewing stand), stół
     * zaklęć, lektern, ognisko (użycie I gaszenie to to samo kliknięcie), dźwignia,
     * guziki, trapdoory (ale NIE drzwi/bramki - te celowo pominięte, patrz opis
     * użytkownika), świeczki (zapalanie/gaszenie), dzwon, kompostownik. Osobno w tym
     * samym handlerze: zaoranie ziemi motyką i obcięcie dyni nożycami - to nie ma
     * własnego zdarzenia w Bukkicie, tylko zwykłe kliknięcie w blok konkretnym itemem.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        Player player = event.getPlayer();
        if (obszarManager.znajdzObszarPod(block.getLocation()) == null || mozeIngerowac(player)) return;

        Material typ = block.getType();
        boolean mechanizm = ZABLOKOWANE_MECHANIZMY.contains(typ)
                || Tag.BUTTONS.isTagged(typ)
                || Tag.TRAPDOORS.isTagged(typ)
                || Tag.CANDLES.isTagged(typ)
                || czyDoniczka(typ);

        boolean prawy = event.getAction() == Action.RIGHT_CLICK_BLOCK;
        Material trzymany = player.getInventory().getItemInMainHand().getType();
        boolean zaoranie = prawy && trzymany.name().endsWith("_HOE") && czyMoznaZaorac(typ);
        boolean strzyzeniePumpkina = prawy && trzymany == Material.SHEARS && typ == Material.PUMPKIN;

        if (!mechanizm && !zaoranie && !strzyzeniePumpkina) return;

        event.setCancelled(true);
        odmowa(player, "Nie możesz tego użyć w tym miejscu!");
    }

    private boolean czyMoznaZaorac(Material typ) {
        return typ == Material.GRASS_BLOCK || typ == Material.DIRT || typ == Material.DIRT_PATH || typ == Material.ROOTED_DIRT;
    }

    /**
     * Pusta doniczka (FLOWER_POT) i każda "POTTED_*" (roślina już w doniczce) - osobny
     * Material na każdy rodzaj rośliny, więc zamiast wypisywać ich wszystkie z osobna
     * (i gubić nowe przy każdej aktualizacji Minecrafta) sprawdzamy po prefiksie nazwy.
     * Łapie zarówno wyjęcie kwiatka z doniczki, jak i włożenie nowego do pustej.
     */
    private boolean czyDoniczka(Material typ) {
        return typ == Material.FLOWER_POT || typ.name().startsWith("POTTED_");
    }

    /** Skrzynie/beczki/piece/dozowniki itd. (wszystko co implementuje Container) - enderchest CELOWO wyjęty, patrz opis użytkownika. */
    @EventHandler(ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST) return;
        if (!(event.getInventory().getHolder() instanceof Container)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        Location loc = event.getInventory().getLocation();
        if (loc == null) return;
        if (obszarManager.znajdzObszarPod(loc) == null || mozeIngerowac(player)) return;

        event.setCancelled(true);
        odmowa(player, "Nie możesz tego otworzyć w tym miejscu!");
    }

    /** Owce/mooshroomy itd. - obcięcie dyni nożycami jest osobno w onInteract (to nie jest "encja"). */
    @EventHandler(ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (obszarManager.znajdzObszarPod(event.getEntity().getLocation()) == null || mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
        odmowa(event.getPlayer(), "Nie możesz tego strzyc w tym miejscu!");
    }

    // ============================================ Żywe istoty zmieniające teren ====

    /**
     * Jeden ogólny handler zamiast osobnego kodu per moba: enderman zabierający/stawiający
     * blok, owca zjadająca trawę, stratowanie uprawy (farmland -> ziemia), ravager gryzący
     * liście, silverfish infekujący kamień - to wszystko jeden i ten sam typ zdarzenia.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) == null) return;
        if (event.getEntity() instanceof Player player && mozeIngerowac(player)) return;
        event.setCancelled(true);
    }

    /** Zombie/vindicator wyważający drzwi na wyższym poziomie trudności. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityBreakDoor(EntityBreakDoorEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Obszar obszar = obszarManager.znajdzObszarPod(event.getLocation());
        if (obszar == null) return;

        boolean agresywny = event.getEntity() instanceof Monster;
        boolean dozwolone = agresywny ? obszar.mobyAgresywneDozwolone : obszar.mobyPasywneDozwolone;
        if (!dozwolone) {
            event.setCancelled(true);
        }
    }

    // ========================================================== Żywioły ====

    /** Zapalenie (rozniecenie, rozprzestrzenienie, lawa, piorun, kula ognia) - bez sprawcy-gracza cały czas zablokowane. */
    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) == null) return;
        if (event.getPlayer() != null && mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /** Blok spalony przez sąsiedni ogień (np. drewniana konstrukcja przy pożarze). */
    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Dowolny wybuch (creeper, ładowany creeper od pioruna, TNT, wither, łódka z TNT) -
     * filtrujemy TYLKO bloki wewnątrz chronionego obszaru z listy zniszczeń, reszta
     * wybuchu (poza obszarem) działa normalnie zamiast całkiem anulować event.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> obszarManager.znajdzObszarPod(block.getLocation()) != null);
    }

    // ================================================ Portal do Netheru ====

    /**
     * W odróżnieniu od reszty tej klasy (ochrona WNĘTRZA obszaru przed graczami),
     * tutaj kierunek jest odwrotny: portal do Netheru ma prawo istnieć i działać
     * WYŁĄCZNIE wewnątrz zdefiniowanego obszaru (np. na spawnie) - wszędzie indziej,
     * łącznie z wyspami graczy i dziczą, jest zablokowany. Oficjalny portal zapala
     * admin z bypassem (patrz mozeIngerowac w onIgnite wyżej) raz, gracze z niego
     * tylko korzystają. Dwie warstwy:
     *  1) portal w ogóle się nie utworzy poza obszarem (ognisko/krzesiwo albo
     *     dowiązanie się drugiej strony portalu),
     *  2) na wszelki wypadek - anulowanie samej teleportacji, gdyby portal poza
     *     obszarem jednak istniał (np. sprzed wprowadzenia tej zasady).
     */
    @EventHandler(ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getReason() != PortalCreateEvent.CreateReason.FIRE
                && event.getReason() != PortalCreateEvent.CreateReason.NETHER_PAIR) return;

        for (BlockState block : event.getBlocks()) {
            if (obszarManager.znajdzObszarPod(block.getLocation()) != null) return;
        }
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) return;
        if (obszarManager.znajdzObszarPod(event.getFrom()) != null) return;

        event.setCancelled(true);
        odmowa(event.getPlayer(), "Portal do Netheru działa tylko w wyznaczonym miejscu!");
    }

    /** To samo co wyżej, ale dla wybuchów bez encji-sprawcy (łóżko w Netherze, kotwica odrodzenia w Overworldzie). */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> obszarManager.znajdzObszarPod(block.getLocation()) != null);
    }

    /** Nic nie rośnie - naturalny wzrost upraw/sadzonek (losowe ticki gry, bez sprawcy). */
    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /** Nawożenie kością (bonemeal) - ma sprawcę-gracza, w przeciwieństwie do onGrow. */
    @EventHandler(ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) == null) return;
        if (event.getPlayer() != null && mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /** Wyrośnięcie całej struktury (drzewo, bambus, grzyb) - osobne od pojedynczego BlockGrowEvent. */
    @EventHandler(ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (obszarManager.znajdzObszarPod(event.getLocation()) == null) return;
        if (event.getPlayer() != null && mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /** Frost Walker zamieniający wodę w lód pod nogami gracza. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityBlockForm(EntityBlockFormEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /** Naturalne formowanie się lodu/śniegu (zależne od biomu, bez sprawcy). */
    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (obszarManager.znajdzObszarPod(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    // ====================================================== Dekoracje ====

    /** Zerwanie obrazu/ramki na przedmioty - fizyką/wybuchem/inną encją (nie graczem, patrz onHangingBreakByEntity). */
    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (obszarManager.znajdzObszarPod(event.getEntity().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /** To samo, ale zerwane bezpośrednio przez gracza/mob - osobny typ zdarzenia w Bukkicie (patrz onHangingBreak). */
    @EventHandler(ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (obszarManager.znajdzObszarPod(event.getEntity().getLocation()) == null) return;
        if (event.getRemover() instanceof Player player && mozeIngerowac(player)) return;
        event.setCancelled(true);
    }

    /** Kliknięcie ramki na przedmioty - wsadzenie/obrócenie/wyciągnięcie zawartości bez łamania samej ramki. */
    @EventHandler(ignoreCancelled = true)
    public void onItemFrameInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame)) return;
        if (obszarManager.znajdzObszarPod(event.getRightClicked().getLocation()) == null || mozeIngerowac(event.getPlayer())) return;
        event.setCancelled(true);
    }

    /** Uderzenie ramki na przedmioty - w wanilii wypycha zawartość bez łamania samej ramki (ta ma osobny event wyżej). */
    @EventHandler(ignoreCancelled = true)
    public void onItemFrameDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) return;
        if (obszarManager.znajdzObszarPod(event.getEntity().getLocation()) == null) return;
        if (event.getDamager() instanceof Player player && mozeIngerowac(player)) return;
        event.setCancelled(true);
    }
}
