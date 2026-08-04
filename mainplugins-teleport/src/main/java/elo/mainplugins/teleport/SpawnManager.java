package elo.mainplugins.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;

/**
 * /spawn (każdy) i /spawnset (opowie - permission mainplugins.teleport.spawnset).
 * Dopóki nikt nie ustawi customowego punktu przez /spawnset, /spawn teleportuje do
 * domyślnego spawnu pierwszego świata (Bukkit.getWorlds().get(0)) - patrz getSpawn().
 * Persystencja x/y/z/yaw/pitch w osobnym pliku spawn.yml - ten sam wzorzec co
 * /is sethome w mainplugins-skyblock (IslandManager.setHome/hasCustomHome).
 */
public class SpawnManager {

    private final Plugin plugin;
    private final File plikSpawnu;
    private final FileConfiguration configSpawnu;

    private Location spawn; // null dopóki nikt nie ustawi - wtedy używamy domyślnego spawnu świata

    public SpawnManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikSpawnu = new File(plugin.getDataFolder(), "spawn.yml");
        if (!plikSpawnu.exists()) {
            plikSpawnu.getParentFile().mkdirs();
            try { plikSpawnu.createNewFile(); } catch (IOException ignored) {}
        }
        this.configSpawnu = YamlConfiguration.loadConfiguration(plikSpawnu);
        wczytajSpawn();
    }

    private void wczytajSpawn() {
        if (!configSpawnu.contains("world")) return;

        World world = Bukkit.getWorld(configSpawnu.getString("world"));
        if (world == null) return; // świat jeszcze niezaładowany (kolejność startu pluginów) - odczyta się przy pierwszym użyciu po jego wczytaniu

        spawn = new Location(
                world,
                configSpawnu.getDouble("x"),
                configSpawnu.getDouble("y"),
                configSpawnu.getDouble("z"),
                (float) configSpawnu.getDouble("yaw", 0),
                (float) configSpawnu.getDouble("pitch", 0)
        );
    }

    private void zapiszSpawn() {
        configSpawnu.set("world", spawn.getWorld().getName());
        configSpawnu.set("x", spawn.getX());
        configSpawnu.set("y", spawn.getY());
        configSpawnu.set("z", spawn.getZ());
        configSpawnu.set("yaw", (double) spawn.getYaw());
        configSpawnu.set("pitch", (double) spawn.getPitch());
        try {
            configSpawnu.save(plikSpawnu);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać spawn.yml: " + e.getMessage());
        }
    }

    /** Ustawiony spawn, albo domyślny spawn pierwszego świata, jeśli nikt jeszcze nic nie ustawił. */
    public Location getSpawn() {
        if (spawn != null) return spawn;
        wczytajSpawn(); // na wypadek, gdy świat z zapisanego spawnu doładował się już PO starcie tego pluginu
        return spawn != null ? spawn : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public void teleportujNaSpawn(Player player) {
        player.teleport(getSpawn());
        player.sendMessage("Przeteleportowano na spawn.");
    }

    public void ustawSpawn(Player player) {
        spawn = player.getLocation();
        zapiszSpawn();
        player.sendMessage("Ustawiono spawn w Twojej aktualnej lokalizacji.");
    }
}