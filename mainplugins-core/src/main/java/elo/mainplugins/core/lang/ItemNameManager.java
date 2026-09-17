package elo.mainplugins.core.lang;

import elo.mainplugins.core.api.ItemNameService;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Słownik nazw przedmiotów (names/pl.yml, names/en.yml). Plik z folderu pluginu ma pierwszeństwo przed tym
 * z jara, więc właściciel serwera może poprawić dowolną nazwę i jego zmiany przeżyją aktualizację pluginu.
 * Nazwy pochodzą z plików językowych Minecrafta - generuje je scripts/generuj-nazwy-itemow.mjs w aplikacji.
 */
public final class ItemNameManager implements ItemNameService {

    private final Plugin plugin;
    private final Supplier<String> language;
    private final Map<Material, String> nazwy = new HashMap<>();

    public ItemNameManager(Plugin plugin, Supplier<String> language) {
        this.plugin = plugin;
        this.language = language;
        reload();
    }

    @Override
    public void reload() {
        nazwy.clear();
        String code = language.get();
        // Angielski zawsze pod spodem - gdy w tłumaczeniu brakuje nowego przedmiotu, zostaje nazwa angielska.
        if (!code.equals("en")) wczytaj("en");
        wczytaj(code);
        plugin.getLogger().info("Item names (" + code + "): " + nazwy.size() + " loaded.");
    }

    private void wczytaj(String code) {
        String path = "names/" + code + ".yml";
        if (plugin.getResource(path) != null && !new File(plugin.getDataFolder(), path).exists()) {
            plugin.saveResource(path, false);
        }
        File file = new File(plugin.getDataFolder(), path);
        if (file.exists()) {
            zbierz(YamlConfiguration.loadConfiguration(file));
            return;
        }
        InputStream in = plugin.getResource(path);
        if (in == null) return;
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            zbierz(YamlConfiguration.loadConfiguration(reader));
        } catch (Exception e) {
            plugin.getLogger().warning(path + ": " + e.getMessage());
        }
    }

    private void zbierz(YamlConfiguration yml) {
        ConfigurationSection section = yml.getConfigurationSection("names");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            String name = section.getString(key);
            if (m != null && name != null && !name.isBlank()) nazwy.put(m, name);
        }
    }

    /** "OAK_LOG" -> "Oak Log" - gdy słownik nie zna przedmiotu (np. nowy w Minecrafcie). */
    static String zNazwyMaterialu(Material material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.name().toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return out.toString();
    }

    @Override
    public String name(Material material) {
        String n = nazwy.get(material);
        return n != null ? n : zNazwyMaterialu(material);
    }

    @Override
    public String name(ItemStack item) {
        String own = wlasnaNazwa(item);
        return own != null ? own : name(item.getType());
    }

    private static String wlasnaNazwa(ItemStack item) {
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName() || meta.displayName() == null) return null;
        String plain = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).trim();
        return plain.isEmpty() ? null : plain;
    }

    @Override
    public List<String> searchTerms(ItemStack item) {
        List<String> out = searchTerms(item == null ? Material.AIR : item.getType());
        String own = wlasnaNazwa(item);
        if (own != null) out.add(own.toLowerCase(Locale.ROOT));
        return out;
    }

    @Override
    public List<String> searchTerms(Material material) {
        List<String> out = new ArrayList<>(4);
        out.add(name(material).toLowerCase(Locale.ROOT));
        String raw = material.name().toLowerCase(Locale.ROOT);
        out.add(raw);
        out.add(raw.replace('_', ' '));
        return out;
    }
}
