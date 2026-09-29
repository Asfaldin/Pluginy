package elo.mainplugins.skyblock;

import elo.mainplugins.skyblock.config.IslandTuning;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Egzekwuje ustawienia allowBreak/allowPvP z IslandData,
 * które wcześniej istniały tylko jako gettery/settery bez żadnego realnego efektu -
 * każdy mógł wejść na cudzą wyspę i robić co chciał. Właściciel i członkowie wyspy
 * zawsze mają pełny dostęp do własnego terenu niezależnie od tych ustawień; dotyczą
 * one wyłącznie gości.
 */
public class IslandProtectionManager implements Listener {

    private final Plugin plugin;
    private final IslandManager islandManager;

    public IslandProtectionManager(Plugin plugin, IslandManager islandManager) {
        this.plugin = plugin;
        this.islandManager = islandManager;
    }

    private boolean jestWlascicielemLubCzlonkiem(IslandData data, UUID uuid) {
        return data.getOwnerUUID().equals(uuid) || data.getMembers().contains(uuid);
    }

    /** Zwykły członek bez prawa budowania (właściciel i admini wyspy budują zawsze). */
    private boolean czlonekBezBudowania(IslandData data, Player player) {
        return islandManager.zwyklyCzlonek(data, player.getUniqueId()) && !data.isMemberBuild();
    }

    private Player rozwiazAtakujacego(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private void odmowa(Player player, String key) {
        islandManager.pasek(player, key);
    }

    /**
     * Łapiemy próbę zniszczenia już na SAMYM TAPNIĘCIU (BlockDamageEvent), zanim
     * dojdzie do BlockBreakEvent - bez tego klient Minecrafta zdążał "przewidzieć"
     * zniszczenie bloku (zwłaszcza tych łamanych natychmiast), a gdy serwer cofał to
     * dopiero w BlockBreakEvent, gra sama wypisywała graczowi własny, brzydki
     * komunikat o desynchronizacji z dokładnymi koordynatami bloku nad paskiem
     * doświadczenia (patrz ten sam fix w mainplugins-spawn/ObszarProtectionManager).
     */
    @EventHandler(ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data != null && czlonekBezBudowania(data, event.getPlayer())) {
            event.setCancelled(true);
            odmowa(event.getPlayer(), "protection.member-build");
            return;
        }
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowBreak() && !(data.isAllowGuestFarming() && jestPlonem(event.getBlock().getType()))) {
            event.setCancelled(true);
            odmowa(event.getPlayer(), "protection.break");
        }
    }

    /** Zapasowa siatka bezpieczeństwa na wypadek, gdyby coś ominęło onBlockDamage wyżej. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data != null && czlonekBezBudowania(data, event.getPlayer())) {
            event.setCancelled(true);
            odmowa(event.getPlayer(), "protection.member-build");
            return;
        }
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowBreak() && !(data.isAllowGuestFarming() && jestPlonem(event.getBlock().getType()))) {
            event.setCancelled(true);
            odmowa(event.getPlayer(), "protection.break");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data != null && czlonekBezBudowania(data, event.getPlayer())) {
            event.setCancelled(true);
            islandManager.komunikat(event.getPlayer(), "protection.member-build");
            return;
        }
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowBreak() && !(data.isAllowGuestFarming() && jestPlonem(event.getBlock().getType()))) {
            event.setCancelled(true);
            islandManager.komunikat(event.getPlayer(), "protection.place");
        }
    }

    /**
     * Dowolny wybuch (creeper, naładowany creeper od pioruna, TNT, wither, łódka z TNT) -
     * filtrujemy TYLKO bloki wewnątrz JAKIEJKOLWIEK wyspy z listy zniszczeń, reszta wybuchu
     * (poza granicami wysp) działa normalnie zamiast całkiem anulować event. Twarda ochrona,
     * bez wyjątku dla właściciela - na wyspach po prostu nic nie wybucha, więc spawnery
     * (i wszystko inne) są bezpieczne nawet przed creeperem, który podszedł pod nos właścicielowi.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (islandManager.getTuning().wybuchyNiszczaBloki()) return;
        event.blockList().removeIf(block -> islandManager.znajdzWyspePod(block.getLocation()) != null);
    }

    /** To samo co wyżej, ale dla wybuchów bez encji-sprawcy (łóżko w Netherze, kotwica odrodzenia w Overworldzie). */
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (islandManager.getTuning().wybuchyNiszczaBloki()) return;
        event.blockList().removeIf(block -> islandManager.znajdzWyspePod(block.getLocation()) != null);
    }

    /**
     * Dopełnienie ochrony przed wybuchami wyżej - sam wybuch na wyspie ma być czysto
     * kosmetyczny (błysk/dźwięk), więc oprócz bloków chronimy też WSZYSTKIE encje w jego
     * zasięgu: graczy, potwory, zwierzęta, itemframe'y itd. Bez tego TNT/creeper wciąż
     * dawałoby obrażenia/odrzut mimo że bloki są już nietykalne.
     */
    @EventHandler(ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        if (islandManager.getTuning().wybuchyNiszczaBloki()) return;

        if (islandManager.znajdzWyspePod(event.getEntity().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Piorun na wyspie ma tylko "uderzyć" (błysk/dźwięk) - nie podpala bloków ani encji
     * i nie zadaje obrażeń. Bez wyjątku dla właściciela, tak samo jak ochrona przed
     * wybuchami wyżej - to jeden i ten sam pomysł (efekt bez realnych konsekwencji).
     */
    @EventHandler(ignoreCancelled = true)
    public void onLightningIgnite(BlockIgniteEvent event) {
        if (event.getCause() != BlockIgniteEvent.IgniteCause.LIGHTNING || islandManager.getTuning().pioruny()) return;
        if (islandManager.znajdzWyspePod(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /** Piorun potrafi podpalić trafioną encję bezpośrednio (nie przez blok) - to ten przypadek. */
    @EventHandler(ignoreCancelled = true)
    public void onLightningCombust(EntityCombustByEntityEvent event) {
        if (!(event.getCombuster() instanceof LightningStrike) || islandManager.getTuning().pioruny()) return;
        if (islandManager.znajdzWyspePod(event.getEntity().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLightningDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.LIGHTNING || islandManager.getTuning().pioruny()) return;
        if (islandManager.znajdzWyspePod(event.getEntity().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Blokada na "Ender Pearl chunk loading" - wanilijski trik, gdzie perła w locie zmusza
     * silnik do dalszego tickowania chunku pod sobą niezależnie od tego, czy jakikolwiek
     * gracz jest w pobliżu (albo nawet online) - efektywnie darmowy chunk loader, dopóki
     * perła nie wyląduje. Twardy limit czasu lotu likwiduje to niezależnie od wariantu
     * (rzuć+wyloguj się, rzuć+odejdź na drugi koniec wyspy, perła utknięta w bloku) - po
     * prostu znika sama, jeśli nie wyląduje w rozsądnym czasie.
     *
     * Bez wyjątku dla właściciela (tak samo jak wybuchy/pioruny wyżej) - to nie jest kara za
     * coś złego, tylko twardy limit fizyki, więc nie ma powodu robić wyjątków.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl perla)) return;
        if (!islandManager.jestSwiatemWysp(perla.getWorld())) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (perla.isValid() && !perla.isDead()) {
                perla.remove();
            }
        }, islandManager.getTuning().maxLotPerlyTicks());
    }

    /**
     * Przyrostowe śledzenie "wartości wyspy" (patrz IslandManager.WARTOSCI_BLOKOW) -
     * MONITOR + ignoreCancelled, żeby liczyć TYLKO bloki, które faktycznie zostały
     * złamane/postawione (po wszystkich innych pluginach i po ewentualnej blokadzie
     * powyżej), a nie próby zablokowane ochroną wyspy. Celowo BEZ zapiszWyspy() -
     * pełny zapis całego pliku wysp przy KAŻDYM złamanym bloku zabiłby TPS na
     * ruchliwym serwerze; worth i tak zapisuje się przy najbliższej innej zmianie
     * (toggle/ulepszenie) albo na wyłączeniu pluginu (zapiszWszystkieWyspy).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorthTrackBreak(BlockBreakEvent event) {
        IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null) return;
        data.dodajDoWartosci(-islandManager.wartoscBloku(event.getBlock().getType()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorthTrackPlace(BlockPlaceEvent event) {
        IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null) return;
        data.dodajDoWartosci(islandManager.wartoscBloku(event.getBlock().getType()));
        if (islandManager.getTuning().liczonyBlok(event.getBlock().getType())) data.addBlockCount(event.getBlock().getType(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLimitTrackBreak(BlockBreakEvent event) {
        IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null || !islandManager.getTuning().liczonyBlok(event.getBlock().getType())) return;
        data.addBlockCount(event.getBlock().getType(), -1);
    }

    /** Limit bloków na wyspę (np. lejów) - dotyczy wszystkich, też właściciela. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLimitPlace(BlockPlaceEvent event) {
        Material typ = event.getBlock().getType();
        int limit = islandManager.getTuning().limitBloku(typ);
        if (limit <= 0) return;
        IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null || data.getBlockCount(typ) < limit) return;
        event.setCancelled(true);
        islandManager.msg(event.getPlayer(), "limit.block", "max", String.valueOf(limit));
    }

    /** Limit zwierząt na wyspę: rozmnażanie. */
    @EventHandler(ignoreCancelled = true)
    public void onBreedLimit(org.bukkit.event.entity.EntityBreedEvent event) {
        if (!zaDuzoZwierzat(event.getEntity().getLocation())) return;
        event.setCancelled(true);
        if (event.getBreeder() instanceof Player p) {
            islandManager.msg(p, "limit.animals", "max", String.valueOf(islandManager.getTuning().limitZwierzat()));
        }
    }

    /** Limit zwierząt na wyspę: jajka i jajka spawnu. */
    @EventHandler(ignoreCancelled = true)
    public void onEggLimit(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Animals)) return;
        CreatureSpawnEvent.SpawnReason r = event.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.EGG && r != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG
                && r != CreatureSpawnEvent.SpawnReason.DISPENSE_EGG) return;
        if (zaDuzoZwierzat(event.getLocation())) event.setCancelled(true);
    }

    private boolean zaDuzoZwierzat(Location loc) {
        int limit = islandManager.getTuning().limitZwierzat();
        if (limit <= 0) return false;
        IslandData data = islandManager.znajdzWyspePod(loc);
        if (data == null) return false;
        int r = data.getBorderSize();
        org.bukkit.util.BoundingBox teren = new org.bukkit.util.BoundingBox(
                data.getCenterX() - r, loc.getWorld().getMinHeight(), data.getCenterZ() - r,
                data.getCenterX() + r + 1, loc.getWorld().getMaxHeight(), data.getCenterZ() + r + 1);
        return loc.getWorld().getNearbyEntities(teren, e -> e instanceof Animals).size() >= limit;
    }

    /**
     * Tłok potrafi pchnąć blok poza granicę wyspy bez wywołania BlockPlaceEvent
     * (silnik gry po prostu przesuwa istniejący blok) - onBlockPlace/onBlockBreak
     * wyżej w ogóle tego nie widzą. Tutaj sprawdzamy NOWĄ pozycję każdego
     * przesuwanego bloku: jeśli którykolwiek wylądowałby poza granicami
     * JAKIEJKOLWIEK wyspy (w "pustce" między wyspami), odwołujemy cały ruch
     * tłoka - dotyczy to również właściciela wyspy, nie tylko gości.
     *
     * Osobny przypadek: tłok potrafi też wypchnąć STOJĄCEGO GRACZA (nie blok) -
     * np. gracz stoi tuż przed pchanym rzędem bloków. To zupełnie inny mechanizm
     * gry niż przesuwanie bloków, więc sprawdzamy go osobno w sprawdzGraczaPodPchnieciem().
     */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!islandManager.jestSwiatemWysp(event.getBlock().getWorld())) return;

        Vector kierunek = event.getDirection().getDirection();

        for (Block block : event.getBlocks()) {
            Location docelowaLokalizacja = block.getLocation().add(kierunek);
            if (islandManager.znajdzWyspePod(docelowaLokalizacja) == null) {
                event.setCancelled(true);
                return;
            }
        }

        if (sprawdzGraczaPodPchnieciem(event, kierunek)) {
            event.setCancelled(true);
        }
    }

    /**
     * Sprawdza miejsce, w które tłok właśnie wepchnie się (koniec łańcucha pchanych
     * bloków + jedno pole "na przedzie", gdzie może stać gracz czekający na pchnięcie).
     * Jeśli w którymś z tych miejsc stoi gracz, a pchnięcie wyrzuciłoby go poza
     * granicę jakiejkolwiek wyspy - zwraca true, żeby cały ruch tłoka odwołać.
     */
    private boolean sprawdzGraczaPodPchnieciem(BlockPistonExtendEvent event, Vector kierunek) {
        List<Block> sprawdzaneMiejsca = new ArrayList<>(event.getBlocks());
        sprawdzaneMiejsca.add(event.getBlock().getRelative(event.getDirection(), sprawdzaneMiejsca.size() + 1));

        for (Block miejsce : sprawdzaneMiejsca) {
            Location srodekBloku = miejsce.getLocation().add(0.5, 0.5, 0.5);
            for (Entity entity : miejsce.getWorld().getNearbyEntities(srodekBloku, 0.6, 1.2, 0.6)) {
                if (!(entity instanceof Player player)) continue;

                Location docelowaGracza = player.getLocation().add(kierunek);
                if (islandManager.znajdzWyspePod(docelowaGracza) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data != null && czlonekBezBudowania(data, event.getPlayer())) {
            event.setCancelled(true);
            islandManager.komunikat(event.getPlayer(), "protection.member-build");
            return;
        }
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowGuestBuckets()) {
            event.setCancelled(true);
            islandManager.komunikat(event.getPlayer(), "protection.fluid-place");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data != null && czlonekBezBudowania(data, event.getPlayer())) {
            event.setCancelled(true);
            islandManager.komunikat(event.getPlayer(), "protection.member-build");
            return;
        }
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowGuestBuckets()) {
            event.setCancelled(true);
            islandManager.komunikat(event.getPlayer(), "protection.fluid-take");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player ofiara)) return;

        Player atakujacy = rozwiazAtakujacego(event.getDamager());
        if (atakujacy == null || atakujacy.equals(ofiara)) return;

        IslandData data = islandManager.znajdzWyspePod(ofiara.getLocation());
        if (data == null || data.isAllowPvP()) return;

        event.setCancelled(true);
        islandManager.komunikat(atakujacy, "protection.pvp");
    }

    /**
     * Moby pojawiające się same na wyspach - ustawienie serwera (wyspy-config.yml: moby.potwory / moby.zwierzeta),
     * domyślnie wyłączone, bo naturalny spawn na wielu wyspach mocno obciąża serwer. Spawnery, rozmnażanie,
     * jajka i /summon działają zawsze. Potwory = wszystko wrogie (też slime'y, fantomy), zwierzęta = reszta żywych
     * (krowy, ryby, kałamarnice, nietoperze...).
     */
    @EventHandler(ignoreCancelled = true)
    public void onNaturalSpawn(CreatureSpawnEvent event) {
        if (!islandManager.jestSwiatemWysp(event.getLocation().getWorld())) return;
        CreatureSpawnEvent.SpawnReason r = event.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL && r != CreatureSpawnEvent.SpawnReason.CHUNK_GEN
                && r != CreatureSpawnEvent.SpawnReason.PATROL && r != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) return;
        IslandTuning t = islandManager.getTuning();
        boolean wrog = event.getEntity() instanceof org.bukkit.entity.Enemy;
        if (wrog ? !t.mobyPotwory() : !t.mobyZwierzeta()) event.setCancelled(true);
    }

    /** Kto może polować na moby na cudzej wyspie. */
    @EventHandler(ignoreCancelled = true)
    public void onGuestMobKill(EntityDamageByEntityEvent event) {
        // Wszystkie moby: potwory i zwierzęta (bez graczy - to PvP - i stojaków na zbroję).
        if (!(event.getEntity() instanceof LivingEntity) || event.getEntity() instanceof Player || event.getEntity() instanceof ArmorStand) return;

        Player atakujacy = rozwiazAtakujacego(event.getDamager());
        if (atakujacy == null) return;

        IslandData data = islandManager.znajdzWyspePod(event.getEntity().getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, atakujacy.getUniqueId())) return;

        if (!data.isAllowGuestMobKill()) {
            event.setCancelled(true);
            islandManager.komunikat(atakujacy, "protection.mob-kill");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        IslandData data = islandManager.znajdzWyspePod(event.getItem().getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, player.getUniqueId())) return;

        if (!data.isAllowItemPickup()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onContainerOpen(InventoryOpenEvent event) {
        if (!(event.getInventory().getHolder() instanceof Container)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        Location lokalizacja = event.getInventory().getLocation();
        if (lokalizacja == null) return;

        IslandData data = islandManager.znajdzWyspePod(lokalizacja);
        if (data != null && islandManager.zwyklyCzlonek(data, player.getUniqueId()) && !data.isMemberContainers()) {
            event.setCancelled(true);
            islandManager.komunikat(player, "protection.member-containers");
            return;
        }
        if (data == null || jestWlascicielemLubCzlonkiem(data, player.getUniqueId())) return;

        if (!data.isAllowContainerAccess()) {
            event.setCancelled(true);
            islandManager.komunikat(player, "protection.containers");
        }
    }

    // Celowo BEZ Tag.PRESSURE_PLATES - płytki naciskowe triggerują się wejściem na nie,
    // nie prawym kliknięciem, więc PlayerInteractEvent i tak by ich nie złapał.
    private boolean czyMechanizm(Material material) {
        return Tag.DOORS.isTagged(material)
                || Tag.TRAPDOORS.isTagged(material)
                || Tag.FENCE_GATES.isTagged(material)
                || Tag.BUTTONS.isTagged(material)
                || material == Material.LEVER;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMechanismInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null || !czyMechanizm(block.getType())) return;

        IslandData data = islandManager.znajdzWyspePod(block.getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowInteract()) {
            event.setCancelled(true);
            islandManager.komunikat(event.getPlayer(), "protection.interact");
        }
    }

    /**
     * Upadek w pustkę: zamiast śmierci (i utraty rzeczy) gracz wraca na punkt /is wyspy, z której spadł.
     * Obrażenia od pustki pojawiają się dopiero sporo pod światem, więc spadający ich nie odczuje.
     */
    @EventHandler(ignoreCancelled = true)
    public void onVoidFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID || !(event.getEntity() instanceof Player player)) return;
        if (!islandManager.jestSwiatemWysp(player.getWorld())) return;
        IslandData data = islandManager.znajdzWyspePod(player.getLocation());
        if (data == null || !islandManager.getTuning().powrotZPustki()) return;

        event.setCancelled(true);
        player.setFallDistance(0);
        islandManager.teleportNaSpawnWyspy(player, data);
        islandManager.komunikat(player, "void.returned");
    }

    /** Teleport (np. /tpa) na wyspę, na której gracz ma bana - blokowany; udany teleport pokazuje napis wyspy. */
    @EventHandler(ignoreCancelled = true)
    public void onTeleportBan(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (!islandManager.jestSwiatemWysp(event.getTo().getWorld())) return;
        if (islandManager.zbanowanyTutaj(event.getPlayer(), event.getTo())) {
            event.setCancelled(true);
            islandManager.komunikat(event.getPlayer(), "guest.banned-here");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportTitle(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (!islandManager.jestSwiatemWysp(event.getTo().getWorld())) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) islandManager.sprawdzWejscie(player, player.getLocation());
        });
    }

    /**
     * Odrodzenie po śmierci na własnej wyspie (zasada serwera odrodzenie-na-wyspie) zamiast na spawnie.
     * HIGH - po pluginie spawnu (NORMAL), który ustawia spawn serwera. Łóżko i kotwica mają pierwszeństwo.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawnOnIsland(org.bukkit.event.player.PlayerRespawnEvent event) {
        if (!islandManager.getTuning().odrodzenieNaWyspie()) return;
        if (event.getRespawnReason() != org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH) return;
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;
        IslandData data = islandManager.wyspaGraczaPoUuid(event.getPlayer().getUniqueId());
        if (data != null) event.setRespawnLocation(islandManager.lokalizacjaSpawnuWyspy(data));
    }

    /** Zachowanie ekwipunku po śmierci w świecie wysp (zasada serwera zachowanie-ekwipunku). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeathKeepInventory(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (!islandManager.getTuning().zachowanieEkwipunku()) return;
        if (!islandManager.jestSwiatemWysp(event.getEntity().getWorld())) return;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
    }

    /** Plony, które gość z włączonym „rolnictwem” może zebrać i posadzić. */
    private boolean jestPlonem(Material material) {
        if (Tag.CROPS.isTagged(material)) return true;
        return switch (material) {
            case NETHER_WART, COCOA, SWEET_BERRY_BUSH, MELON, PUMPKIN, SUGAR_CANE, CACTUS, BAMBOO -> true;
            default -> material == Material.MELON_STEM || material == Material.PUMPKIN_STEM;
        };
    }

    /** Karmienie, rozmnażanie, strzyżenie, dojenie i smycz na zwierzętach - dla gości tylko z „rolnictwem”. */
    @EventHandler(ignoreCancelled = true)
    public void onAnimalInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Animals zwierze)) return;
        IslandData data = islandManager.znajdzWyspePod(zwierze.getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowGuestFarming()) {
            event.setCancelled(true);
            islandManager.komunikat(event.getPlayer(), "protection.farming");
        }
    }

    /** Ogień przeskakujący na sąsiednie bloki. */
    @EventHandler(ignoreCancelled = true)
    public void onFireSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() != Material.FIRE && event.getSource().getType() != Material.SOUL_FIRE) return;
        if (ogienZablokowany(event.getBlock().getLocation())) event.setCancelled(true);
    }

    /** Blok spalony przez ogień. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (ogienZablokowany(event.getBlock().getLocation())) event.setCancelled(true);
    }

    /** Podpalenie od lawy albo od innego ognia (zapalniczka gracza działa zawsze). */
    @EventHandler(ignoreCancelled = true)
    public void onFireIgnite(BlockIgniteEvent event) {
        BlockIgniteEvent.IgniteCause cause = event.getCause();
        if (cause != BlockIgniteEvent.IgniteCause.SPREAD && cause != BlockIgniteEvent.IgniteCause.LAVA) return;
        if (ogienZablokowany(event.getBlock().getLocation())) event.setCancelled(true);
    }

    /** Ogień na wyspach - ustawienie całego serwera (wyspy-config.yml: ogien-sie-rozprzestrzenia). */
    private boolean ogienZablokowany(Location lokalizacja) {
        return !islandManager.getTuning().ogienSieRozprzestrzenia() && islandManager.znajdzWyspePod(lokalizacja) != null;
    }

    /**
     * Twarde zamknięcie granic świata wysp - niezależne od kosmetycznego przełącznika
     * "Wizualny Border" w panelu wyspy. WorldBorder w Minecraftcie to tylko ostrzeżenie
     * i obrażenia w czasie, a NIE fizyczna ściana - wystarczająco odporny gracz zawsze
     * mógł go po prostu przejść, a wyłączenie wizualnego borderu robiło to z zerowym
     * oporem (ustawiało promień na 60 milionów bloków). Tutaj fizycznie cofamy gracza,
     * jeśli próbuje wejść w pustkę między wyspami - niezależnie od stanu tamtego
     * kosmetycznego przełącznika.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || !islandManager.jestSwiatemWysp(to.getWorld())) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) return; // sam obrót głowy/ruch w pionie nas nie interesuje

        Player player = event.getPlayer();
        // Permission zamiast isOp() - działa też, gdy admin dostał uprawnienia
        // przez plugin permisji, a nie przez literalne /op (domyślnie: op ma je i tak).
        if (player.hasPermission("mainplugins.skyblock.bypass")) return;

        if (islandManager.znajdzWyspePod(to) != null) {
            // w granicach jakiejkolwiek wyspy - OK, chyba że gracz ma na niej bana
            if (islandManager.zbanowanyTutaj(player, to) && !islandManager.zbanowanyTutaj(player, from)) {
                event.setTo(from);
                odmowa(player, "guest.banned-here");
                return;
            }
            islandManager.sprawdzWejscie(player, to);
            return;
        }

        event.setTo(from);
    }
}