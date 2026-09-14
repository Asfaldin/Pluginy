package elo.mainplugins.spawn;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.UnlockService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generyczne, nazwane punkty teleportu (/warp, /@setwarp, /@delwarp) - ten sam wzorzec
 * persystencji co SpawnManager (jeden punkt), tylko trzymany jako mapa nazwa->Location w
 * jednym pliku warps.yml zamiast pojedynczych pól. Nazwy warpów są case-insensitive
 * (przechowywane z małej litery) - "Kowal"/"kowal"/"KOWAL" to ten sam warp.
 *
 * Warp może wymagać odblokowania z core (requires-unlock w warps.yml, ustawiane /@warplock) -
 * Spawn nie wie, kto je daje (zadanie, osiągnięcie, admin).
 */
public class WarpManager {

    private final Plugin plugin;
    private final LangService lang;
    private final File plikWarpow;
    private final FileConfiguration configWarpow;
    private final Map<String, Location> warpy = new LinkedHashMap<>();
    private final Map<String, String> blokady = new HashMap<>();

    public WarpManager(Plugin plugin, LangService lang) {
        this.plugin = plugin;
        this.lang = lang;
        this.plikWarpow = new File(plugin.getDataFolder(), "warps.yml");
        if (!plikWarpow.exists()) {
            plikWarpow.getParentFile().mkdirs();
            try { plikWarpow.createNewFile(); } catch (IOException ignored) {}
        }
        this.configWarpow = YamlConfiguration.loadConfiguration(plikWarpow);
        wczytaj();
    }

    private void wczytaj() {
        ConfigurationSection sekcja = configWarpow.getConfigurationSection("warpy");
        if (sekcja == null) return;

        for (String nazwa : sekcja.getKeys(false)) {
            ConfigurationSection w = sekcja.getConfigurationSection(nazwa);
            if (w == null || !w.contains("world")) continue;
            World world = Bukkit.getWorld(w.getString("world"));
            if (world == null) continue;

            warpy.put(nazwa, new Location(world,
                    w.getDouble("x"), w.getDouble("y"), w.getDouble("z"),
                    (float) w.getDouble("yaw", 0), (float) w.getDouble("pitch", 0)));
            String wymagane = w.getString("requires-unlock");
            if (wymagane != null && !wymagane.isBlank()) blokady.put(nazwa, wymagane.trim().toLowerCase(Locale.ROOT));
        }
    }

    private void zapisz() {
        configWarpow.set("warpy", null); // czyste nadpisanie - usunięte warpy nie zostają jako sierota w pliku
        for (Map.Entry<String, Location> e : warpy.entrySet()) {
            String sciezka = "warpy." + e.getKey();
            Location loc = e.getValue();
            configWarpow.set(sciezka + ".world", loc.getWorld().getName());
            configWarpow.set(sciezka + ".x", loc.getX());
            configWarpow.set(sciezka + ".y", loc.getY());
            configWarpow.set(sciezka + ".z", loc.getZ());
            configWarpow.set(sciezka + ".yaw", (double) loc.getYaw());
            configWarpow.set(sciezka + ".pitch", (double) loc.getPitch());
            String wymagane = blokady.get(e.getKey());
            if (wymagane != null) configWarpow.set(sciezka + ".requires-unlock", wymagane);
        }
        try {
            configWarpow.save(plikWarpow);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać warps.yml: " + e.getMessage());
        }
    }

    public void ustawWarp(Player player, String nazwa) {
        String klucz = nazwa.toLowerCase();
        warpy.put(klucz, player.getLocation().clone());
        zapisz();
        player.sendMessage(Component.text("Ustawiono warp \"" + klucz + "\" w Twojej aktualnej lokalizacji.", NamedTextColor.GREEN));
    }

    public void usunWarp(Player player, String nazwa) {
        String klucz = nazwa.toLowerCase();
        if (warpy.remove(klucz) == null) {
            player.sendMessage(Component.text("Nie ma warpu o nazwie \"" + klucz + "\".", NamedTextColor.RED));
            return;
        }
        blokady.remove(klucz);
        zapisz();
        player.sendMessage(Component.text("Usunięto warp \"" + klucz + "\".", NamedTextColor.GREEN));
    }

    public void teleportujDoWarpu(Player player, String nazwa) {
        String klucz = nazwa.toLowerCase();
        Location cel = warpy.get(klucz);
        if (cel == null) {
            player.sendMessage(Component.text("Nie ma warpu o nazwie \"" + klucz + "\". Sprawdź /warp bez argumentu.", NamedTextColor.RED));
            return;
        }
        if (jestZablokowany(player, klucz)) {
            lang.send(player, plugin, "warp.locked");
            return;
        }
        player.teleport(cel);
        player.sendMessage(Component.text("Przeteleportowano do \"" + klucz + "\".", NamedTextColor.AQUA));
    }

    /** Blokada z warps.yml (requires-unlock); bez niej warp otwarty dla wszystkich. */
    private boolean jestZablokowany(Player player, String klucz) {
        String wymagane = blokady.get(klucz);
        if (wymagane == null) return false;
        UnlockService unlocks = CoreAPI.getUnlockService();
        return unlocks == null || !unlocks.has(player.getUniqueId(), wymagane);
    }

    /** unlock null/pusty = zdjęcie blokady; false = nie ma takiego warpu. */
    public boolean ustawBlokade(String warp, String unlock) {
        String klucz = warp.toLowerCase(Locale.ROOT);
        if (!warpy.containsKey(klucz)) return false;
        if (unlock == null || unlock.isBlank()) blokady.remove(klucz);
        else blokady.put(klucz, unlock.trim().toLowerCase(Locale.ROOT));
        zapisz();
        return true;
    }

    public Set<String> nazwyWarpow() {
        return new TreeSet<>(warpy.keySet());
    }
}
