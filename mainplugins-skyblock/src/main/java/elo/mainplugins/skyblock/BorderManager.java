package elo.mainplugins.skyblock;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Świadomy wysp - dopóki gracz jest w skyblockWorld, border ma trzymać się jego
 * aktualnej pozycji (wyspa, na której fizycznie stoi) przy KAŻDEJ zmianie świata
 * i teleportacji, a nie tylko bezpośrednio po /is. Bez tego border "znikał" po
 * powrocie z innego świata (np. spawna), bo nic go nie odtwarzało przy wejściu.
 */
public class BorderManager implements Listener {

    private final Plugin plugin;
    private final IslandManager islandManager;

    public BorderManager(Plugin plugin, IslandManager islandManager) {
        this.plugin = plugin;
        this.islandManager = islandManager;
    }

    /**
     * Ustawienie customowego WorldBordera W TYM SAMYM ticku co PlayerJoinEvent często
     * jest po cichu ignorowane przez klienta - sekwencja pakietów logowania (w tym
     * domyślny WorldBorder świata) jeszcze się nie ustabilizowała, więc nasz override
     * bywa nadpisywany zaraz po wysłaniu. Stąd border "znikał" dopiero PO relogu, mimo
     * że kod wyglądał na poprawny. Przesunięcie o 1 tick to standardowy obejście tego
     * problemu w Bukkit/Paper.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (islandManager.jestSwiatemWysp(player.getWorld())) {
                islandManager.aplikujBorderDlaLokalizacji(player, player.getLocation());
            } else {
                wyczyscCzerwonyEkranBorderu(player);
            }
        });
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (islandManager.jestSwiatemWysp(player.getWorld())) {
            islandManager.aplikujBorderDlaLokalizacji(player, player.getLocation());
        } else {
            wyczyscCzerwonyEkranBorderu(player);
        }
    }

    /**
     * Teleportacje WEWNĄTRZ świata wysp (np. /tp do innego gracza) nie wywołują
     * PlayerChangedWorldEvent, ale mogą przenieść gracza na teren zupełnie innej
     * wyspy (albo w pustkę) - bez tego jego border zostałby ustawiony na starą
     * lokalizację aż do następnej zmiany świata.
     */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;
        if (islandManager.jestSwiatemWysp(to.getWorld())) {
            islandManager.aplikujBorderDlaLokalizacji(event.getPlayer(), to);
        }
    }

    /**
     * Respawn nie jest teleportacją — bez tego handlera gracz po śmierci
     * zostawał bez bordera do następnej zmiany świata albo reloga.
     *
     * Priorytet MONITOR: inne pluginy (np. SpawnManager z mainplugins-spawn) mogą
     * na tym samym evencie nadpisać event.getRespawnLocation() - MONITOR gwarantuje,
     * że nasz handler odpali się na końcu, po tym jak każdy inny plugin już podjął
     * decyzję. Bez tego (domyślny priorytet NORMAL) kolejność względem SpawnManagera
     * zależałaby od przypadkowej kolejności ładowania pluginów - moglibyśmy tu czasem
     * złapać jeszcze nienadpisaną (starą) lokalizację. Nie modyfikujemy tu eventu,
     * tylko go czytamy, więc MONITOR (zarezerwowany zwykle dla obserwatorów) pasuje.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location respawnLoc = event.getRespawnLocation();

        // Ten sam 1-tickowy odstęp co w onJoin: w chwili zdarzenia gracz nie stoi
        // jeszcze w docelowym miejscu, a klient przetwarza pakiety respawnu.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;

            if (respawnLoc.getWorld() != null && islandManager.jestSwiatemWysp(respawnLoc.getWorld())) {
                islandManager.aplikujBorderDlaLokalizacji(player, respawnLoc);
            } else {
                wyczyscCzerwonyEkranBorderu(player);
            }
        });
    }

    public void wyczyscCzerwonyEkranBorderu(Player player) {
        // Reset kosmetycznego efektu "Pogoda i Czas" (patrz IslandManager.aplikujPogodeICzas) -
        // gracz opuszcza świat wysp, więc żaden island-owy override nie powinien się już trzymać.
        player.resetPlayerTime();
        player.resetPlayerWeather();

        WorldBorder worldBorder = player.getWorld().getWorldBorder();

        // Tworzymy osobną instancję borderu dla gracza, która kopiuje rozmiar świata,
        // ale ma całkowicie wyłączony dystans ostrzegawczy (co usuwa czerwony ekran)
        WorldBorder playerBorder = Bukkit.createWorldBorder();
        playerBorder.setCenter(worldBorder.getCenter());
        playerBorder.setSize(worldBorder.getSize());
        playerBorder.setDamageAmount(worldBorder.getDamageAmount());
        playerBorder.setDamageBuffer(worldBorder.getDamageBuffer());

        // Ustawienie warning na 0 sprawia, że gra nigdy nie włączy czerwonego pulsowania
        playerBorder.setWarningDistance(0);

        player.setWorldBorder(playerBorder);
    }
}