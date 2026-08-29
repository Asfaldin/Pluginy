package elo.mainplugins.redstone.item;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Wczytuje redstone-items.yml - schemat pól identyczny jak custom-items.yml (mainplugins-core), plus wymagane pole "kind". */
public final class RedstoneItemLoader {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private RedstoneItemLoader() {}

    public static Map<String, RedstoneItemDefinition> load(Plugin plugin) {
        File plik = new File(plugin.getDataFolder(), "redstone-items.yml");
        if (!plik.exists()) {
            plugin.saveResource("redstone-items.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);

        Map<String, RedstoneItemDefinition> wynik = new HashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection("items");
        if (sekcja != null) {
            for (String id : sekcja.getKeys(false)) {
                RedstoneItemDefinition def = wczytajWpis(plugin, cfg, id);
                if (def != null) wynik.put(id, def);
            }
        }
        return wynik;
    }

    private static RedstoneItemDefinition wczytajWpis(Plugin plugin, FileConfiguration cfg, String id) {
        String path = "items." + id + ".";

        String kindRaw = cfg.getString(path + "kind");
        RedstoneItemKind kind;
        try {
            kind = RedstoneItemKind.valueOf(kindRaw == null ? "" : kindRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("redstone-items.yml: pomijam '" + id + "' - brak/złe pole 'kind' (WIRE|PLANTER), było: '" + kindRaw + "'.");
            return null;
        }

        String matName = cfg.getString(path + "material");
        Material material = matName != null ? Material.matchMaterial(matName) : null;
        if (material == null) {
            plugin.getLogger().warning("redstone-items.yml: pomijam '" + id + "' - brak/zły material ('" + matName + "').");
            return null;
        }

        String nameRaw = cfg.getString(path + "name");
        Component name = nameRaw != null ? SERIALIZER.deserialize(nameRaw).decoration(TextDecoration.ITALIC, false) : null;

        List<Component> lore = cfg.getStringList(path + "lore").stream()
                .map(linia -> (Component) SERIALIZER.deserialize(linia).decoration(TextDecoration.ITALIC, false))
                .toList();

        String modelRaw = cfg.getString(path + "model");
        Key model = null;
        if (modelRaw != null) {
            try {
                model = Key.key(modelRaw);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("redstone-items.yml: '" + id + "' ma niepoprawny model ('" + modelRaw + "') - pomijam to pole.");
            }
        }

        boolean glint = cfg.getBoolean(path + "glint", false);

        return new RedstoneItemDefinition(id, kind, material, name, lore, model, glint);
    }
}
