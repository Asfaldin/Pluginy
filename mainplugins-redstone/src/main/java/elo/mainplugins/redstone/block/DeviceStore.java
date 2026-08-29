package elo.mainplugins.redstone.block;

import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.redstone.item.RedstoneItemKind;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Baza danych postawionych urządzeń i kabli - prawdziwe bloki nie mają PDC, więc ich
 * tożsamość trzymamy tutaj i zrzucamy do redstone-urzadzenia.yml (bufor zapisu jak w
 * mainplugins-quests - patrz {@link AsyncConfigSaver}). Format: listy map, żeby klucz
 * bloku ("świat;x;y;z") nie kolidował ze ścieżkami YAML.
 */
public final class DeviceStore {

    private final Plugin plugin;
    private final YamlConfiguration config;
    private final AsyncConfigSaver saver;

    private final Map<BlockKey, PlacedDevice> devices = new ConcurrentHashMap<>();
    private final Map<BlockKey, String> cables = new ConcurrentHashMap<>();

    public DeviceStore(Plugin plugin) {
        this.plugin = plugin;
        File plik = new File(plugin.getDataFolder(), "redstone-urzadzenia.yml");
        this.config = YamlConfiguration.loadConfiguration(plik);
        this.saver = new AsyncConfigSaver(plugin, config, plik, 20);
        wczytaj();
    }

    // ---------- urządzenia ----------

    public void place(PlacedDevice device) {
        devices.put(device.key, device);
        zrzuc();
    }

    public void removeDevice(BlockKey key) {
        if (devices.remove(key) != null) zrzuc();
    }

    public PlacedDevice getDevice(BlockKey key) {
        return devices.get(key);
    }

    public Collection<PlacedDevice> allDevices() {
        return devices.values();
    }

    // ---------- kable ----------

    public void placeCable(BlockKey key, String itemId) {
        cables.put(key, itemId);
        zrzuc();
    }

    public void removeCable(BlockKey key) {
        if (cables.remove(key) != null) zrzuc();
    }

    public boolean isCable(BlockKey key) {
        return cables.containsKey(key);
    }

    public String cableItemId(BlockKey key) {
        return cables.get(key);
    }

    public Collection<BlockKey> allCables() {
        return cables.keySet();
    }

    /** Woła manager sadzarki po zmianie nasion - stan siedzi już w PlacedDevice, tu tylko planujemy zapis. */
    public void oznaczZmiane() {
        zrzuc();
    }

    public void zamknij() {
        saver.zamknij();
    }

    // ---------- IO ----------

    private void wczytaj() {
        for (Map<?, ?> m : config.getMapList("urzadzenia")) {
            try {
                BlockKey key = new BlockKey(str(m, "world"), intt(m, "x"), intt(m, "y"), intt(m, "z"));
                RedstoneItemKind kind = RedstoneItemKind.valueOf(str(m, "kind").toUpperCase(Locale.ROOT));
                PlacedDevice d = new PlacedDevice(key, kind, str(m, "item"));
                if (m.get("farmland-x") != null) {
                    d.farmland = new BlockKey(key.world(), intt(m, "farmland-x"), intt(m, "farmland-y"), intt(m, "farmland-z"));
                }
                if (m.get("seed") != null) {
                    d.seed = Material.matchMaterial(str(m, "seed"));
                    d.seedCount = intt(m, "seed-count");
                }
                devices.put(key, d);
            } catch (Exception e) {
                plugin.getLogger().warning("redstone-urzadzenia.yml: pomijam błędny wpis urządzenia: " + e.getMessage());
            }
        }
        for (Map<?, ?> m : config.getMapList("kable")) {
            try {
                cables.put(new BlockKey(str(m, "world"), intt(m, "x"), intt(m, "y"), intt(m, "z")), str(m, "item"));
            } catch (Exception e) {
                plugin.getLogger().warning("redstone-urzadzenia.yml: pomijam błędny wpis kabla: " + e.getMessage());
            }
        }
        plugin.getLogger().info("redstone-urzadzenia.yml: wczytano " + devices.size() + " urządzeń i " + cables.size() + " kabli.");
    }

    private void zrzuc() {
        List<Map<String, Object>> u = new ArrayList<>();
        for (PlacedDevice d : devices.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", d.key.world());
            m.put("x", d.key.x());
            m.put("y", d.key.y());
            m.put("z", d.key.z());
            m.put("kind", d.kind.name());
            m.put("item", d.itemId);
            if (d.farmland != null) {
                m.put("farmland-x", d.farmland.x());
                m.put("farmland-y", d.farmland.y());
                m.put("farmland-z", d.farmland.z());
            }
            if (d.seed != null && d.seedCount > 0) {
                m.put("seed", d.seed.name());
                m.put("seed-count", d.seedCount);
            }
            u.add(m);
        }
        List<Map<String, Object>> k = new ArrayList<>();
        for (Map.Entry<BlockKey, String> e : cables.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", e.getKey().world());
            m.put("x", e.getKey().x());
            m.put("y", e.getKey().y());
            m.put("z", e.getKey().z());
            m.put("item", e.getValue());
            k.add(m);
        }
        config.set("urzadzenia", u);
        config.set("kable", k);
        saver.oznaczZmiane();
    }

    private static String str(Map<?, ?> m, String key) {
        Object o = m.get(key);
        return o != null ? o.toString() : null;
    }

    private static int intt(Map<?, ?> m, String key) {
        Object o = m.get(key);
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString().trim());
    }
}
