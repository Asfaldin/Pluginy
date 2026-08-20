package elo.mainplugins.spawn;

import elo.mainplugins.core.api.SpawnService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;

/**
 * Główny punkt teleportu serwera - /spawn, pierwsze wejście na serwer i respawn po
 * śmierci, gdy gracz nie ma ustawionego własnego punktu odradzania. Jeśli gracz MA
 * łóżko/kotwicę (np. na własnej wyspie), respawn ląduje tam - główny spawn jest
 * tylko fallbackiem, patrz onRespawn(). Sama ochrona terenu wokół spawnu (i przyszłych
 * warpów) to osobny, ogólny mechanizm niezwiązany z konkretnym punktem - patrz
 * {@link ObszarManager}.
 */
public class SpawnManager implements Listener, SpawnService {

    private final Plugin plugin;
    private final File plikSpawnu;
    private final FileConfiguration configSpawnu;

    private Location spawnPoint; // null dopóki nikt nie ustawi - wtedy domyślny spawn pierwszego świata

    public SpawnManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikSpawnu = new File(plugin.getDataFolder(), "spawn.yml");
        if (!plikSpawnu.exists()) {
            plikSpawnu.getParentFile().mkdirs();
            try { plikSpawnu.createNewFile(); } catch (IOException ignored) {}
        }
        this.configSpawnu = YamlConfiguration.loadConfiguration(plikSpawnu);
        wczytaj();
    }

    private void wczytaj() {
        if (!configSpawnu.contains("world")) return;
        World world = Bukkit.getWorld(configSpawnu.getString("world"));
        if (world == null) return;

        spawnPoint = new Location(world,
                configSpawnu.getDouble("x"),
                configSpawnu.getDouble("y"),
                configSpawnu.getDouble("z"),
                (float) configSpawnu.getDouble("yaw", 0),
                (float) configSpawnu.getDouble("pitch", 0));
    }

    private void zapisz() {
        configSpawnu.set("world", spawnPoint.getWorld().getName());
        configSpawnu.set("x", spawnPoint.getX());
        configSpawnu.set("y", spawnPoint.getY());
        configSpawnu.set("z", spawnPoint.getZ());
        configSpawnu.set("yaw", (double) spawnPoint.getYaw());
        configSpawnu.set("pitch", (double) spawnPoint.getPitch());
        try {
            configSpawnu.save(plikSpawnu);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać spawn.yml: " + e.getMessage());
        }
    }

    /** Ustawiony spawn, albo domyślny spawn pierwszego świata, jeśli nikt jeszcze nic nie ustawił. */
    @Override
    public Location getSpawn() {
        return spawnPoint != null ? spawnPoint : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public void ustawPunkt(Player player) {
        spawnPoint = player.getLocation().clone();
        zapisz();
        player.sendMessage(Component.text("Ustawiono główny spawn serwera w Twojej aktualnej lokalizacji.", NamedTextColor.GREEN));
    }

    public void teleportujNaSpawn(Player player) {
        player.teleport(getSpawn());
        player.sendMessage(Component.text("Przeteleportowano na spawn.", NamedTextColor.AQUA));
    }

    public String opisPunktu() {
        return spawnPoint != null
                ? spawnPoint.getWorld().getName() + " " + spawnPoint.getBlockX() + ", " + spawnPoint.getBlockY() + ", " + spawnPoint.getBlockZ()
                : "nieustawiony (domyślny spawn świata)";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!event.getPlayer().hasPlayedBefore()) {
            event.getPlayer().teleport(getSpawn());
        }
    }

    /**
     * Gracz z ustawionym łóżkiem/kotwicą (np. na własnej wyspie) odradza się tam -
     * Bukkit sam już wyliczył tę lokalizację i wpisał do eventu, więc wystarczy jej
     * nie ruszać. Dopiero gdy gracz NIE ma żadnego z nich (isBedSpawn/isAnchorSpawn
     * oba false - nowy gracz albo łóżko/kotwica zniszczone/zablokowane), event i tak
     * domyślnie wskazywałby na spawn świata - nadpisujemy na NASZ ustawiony spawn
     * (patrz getSpawn()), żeby admin mógł go ustawić w innym miejscu niż wanilijski.
     */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;
        event.setRespawnLocation(getSpawn());
    }
}
