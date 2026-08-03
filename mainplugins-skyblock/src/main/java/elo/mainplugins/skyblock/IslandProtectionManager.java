package elo.mainplugins.skyblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
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

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        IslandManager.IslandData data = islandManager.znajdzWyspePod(event.getBlock().getLocation());
        if (data == null || jestWlascicielemLubCzlonkiem(data, event.getPlayer().getUniqueId())) return;

        if (!data.isAllowBreak()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Nie możesz niszczyć bloków na cudzej wyspie!", NamedTextColor.RED));
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