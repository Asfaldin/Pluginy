package elo.mainplugins.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Egzekwuje ustawienia allowBreak/allowPvP/allowMobs z IslandManager.IslandData,
 * które wcześniej istniały tylko jako gettery/settery bez żadnego realnego efektu -
 * każdy mógł wejść na cudzą wyspę i robić co chciał. Właściciel i członkowie wyspy
 * zawsze mają pełny dostęp do własnego terenu niezależnie od tych ustawień; dotyczą
 * one wyłącznie gości.
 */
public class IslandProtectionManager implements Listener {

    private final IslandManager islandManager;

    public IslandProtectionManager(IslandManager islandManager) {
        this.islandManager = islandManager;
    }

    private boolean jestWlascicielemLubCzlonkiem(IslandManager.IslandData data, UUID uuid) {
        return data.getOwnerUUID().equals(uuid) || data.getMembers().contains(uuid);
    }

    private Player rozwiazAtakujacego(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private void odmowa(Player player, String komunikat) {
        player.sendActionBar(Component.text(komunikat, NamedTextColor.RED));
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
        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowBreak()) {
            event.setCancelled(true);
            odmowa(event.getPlayer(), "Nie możesz tego zniszczyć!");
        }
    }

    /** Zapasowa siatka bezpieczeństwa na wypadek, gdyby coś ominęło onBlockDamage wyżej. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowBreak()) {
            event.setCancelled(true);
            odmowa(event.getPlayer(), "Nie możesz tego zniszczyć!");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowBreak()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Nie możesz stawiać bloków na cudzej wyspie!", NamedTextColor.RED));
        }
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
        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null) return;
        data.dodajDoWartosci(-IslandManager.wartoscBloku(event.getBlock().getType()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorthTrackPlace(BlockPlaceEvent event) {
        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null) return;
        data.dodajDoWartosci(IslandManager.wartoscBloku(event.getBlock().getType()));
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
        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowBreak()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Nie możesz wylewać cieczy na cudzej wyspie!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowBreak()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Nie możesz zbierać cieczy z cudzej wyspy!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPvP(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player ofiara)) return;

        Player atakujacy = rozwiazAtakujacego(event.getDamager());
        if (atakujacy == null || atakujacy.equals(ofiara)) return;

        IslandManager.IslandData data = islandManager.znajdzWyspePod(ofiara.getLocation());
        if (data == null || data.isAllowPvP()) return;

        event.setCancelled(true);
        atakujacy.sendMessage(Component.text("PvP jest wyłączone na tej wyspie!", NamedTextColor.RED));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster)) return;

        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getLocation());
        if (data != null && !data.isAllowMobs()) {
            event.setCancelled(true);
        }
    }

    /** Osobne od allowMobs (który dotyczy TYLKO spawnu) - kontroluje, kto może polować na już zaspawnowane moby. */
    @EventHandler(ignoreCancelled = true)
    public void onGuestMobKill(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Monster)) return;

        Player atakujacy = rozwiazAtakujacego(event.getDamager());
        if (atakujacy == null) return;

        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getEntity().getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, atakujacy.getUniqueId())) return;

        if (!data.isAllowGuestMobKill()) {
            event.setCancelled(true);
            atakujacy.sendMessage(Component.text("Nie możesz zabijać mobów na cudzej wyspie!", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getItem().getLocation());
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

        IslandManager.IslandData data = islandManager.znajdzWyspePod(lokalizacja);
        if (data == null || jestWlascicielemLubCzlonkiem(data, player.getUniqueId())) return;

        if (!data.isAllowContainerAccess()) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Nie możesz otwierać skrzyń/kontenerów na cudzej wyspie!", NamedTextColor.RED));
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

        IslandManager.IslandData data = islandManager.znajdzWyspePod(block.getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowInteract()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Nie możesz używać drzwi/mechanizmów na cudzej wyspie!", NamedTextColor.RED));
        }
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

        if (islandManager.znajdzWyspePod(to) != null) return; // w granicach jakiejkolwiek wyspy - OK

        event.setTo(from);
    }
}