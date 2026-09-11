package elo.mainplugins.core.customitem;

import elo.mainplugins.core.api.CustomItemProvider;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.util.CustomItemKeys;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemEnchantments;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Katalog itemów z folderu items/ (każdy plik *.yml, alfabetycznie) + dostawcy pluginów.
 * Wygląd budujemy API komponentów (setData), tag custom-id przez ItemMeta na końcu -
 * żeby setItemMeta nie nadpisał komponentów starszym stanem.
 */
public class CustomItemManager implements CustomItemService, Listener {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final String FOLDER = "items";
    private static final List<String> BUNDLED = List.of("examples.yml", "quests.yml", "fishing.yml");

    private final Plugin plugin;
    private final Map<String, CustomItemDefinition> definitions = new HashMap<>();
    private final Map<Plugin, CustomItemProvider> providers = new LinkedHashMap<>();
    private ItemCatalog catalog = ItemCatalog.build(List.of(), w -> {});

    public CustomItemManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    @Override
    public void reload() {
        File folder = new File(plugin.getDataFolder(), FOLDER);
        if (!folder.exists()) {
            for (String name : BUNDLED) plugin.saveResource(FOLDER + "/" + name, false);
        }
        if (new File(plugin.getDataFolder(), "custom-items.yml").exists()) {
            plugin.getLogger().warning("custom-items.yml is no longer read - move its entries into the items/ folder and delete it.");
        }

        Consumer<String> warn = plugin.getLogger()::warning;
        List<ItemSpec> specs = new ArrayList<>();
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                specs.addAll(ItemSpecParser.parseFile(YamlConfiguration.loadConfiguration(file), file.getName(), warn));
            }
        }

        ItemCatalog newCatalog = ItemCatalog.build(specs, warn);
        Map<String, CustomItemDefinition> newDefinitions = new HashMap<>();
        for (String id : newCatalog.ids()) {
            CustomItemDefinition def = toDefinition(newCatalog.get(id));
            if (def != null) newDefinitions.put(id.toLowerCase(Locale.ROOT), def);
        }
        catalog = newCatalog;
        definitions.clear();
        definitions.putAll(newDefinitions);
        plugin.getLogger().info("Loaded " + definitions.size() + " custom items from " + FOLDER + "/.");
    }

    private CustomItemDefinition toDefinition(ItemSpec spec) {
        String where = spec.sourceFile() + ": '" + spec.id() + "'";
        Material material = Material.matchMaterial(spec.material());
        if (material == null) {
            plugin.getLogger().warning(where + " has unknown material '" + spec.material() + "' - skipping.");
            return null;
        }
        Component name = spec.name() != null
                ? SERIALIZER.deserialize(spec.name()).decoration(TextDecoration.ITALIC, false) : null;
        List<Component> lore = spec.lore().stream()
                .map(line -> (Component) SERIALIZER.deserialize(line).decoration(TextDecoration.ITALIC, false))
                .toList();

        Key model = null;
        if (spec.model() != null) {
            try {
                model = Key.key(spec.model());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(where + " has invalid model '" + spec.model() + "' - ignoring model.");
            }
        }

        Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
        Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        for (Map.Entry<String, Integer> e : spec.enchants().entrySet()) {
            Enchantment enchantment = null;
            try {
                enchantment = registry.get(Key.key(e.getKey()));
            } catch (IllegalArgumentException ignored) {
                // zła składnia klucza - potraktuj jak nieznany enchant
            }
            if (enchantment == null) {
                plugin.getLogger().warning(where + " has unknown enchant '" + e.getKey() + "' - skipping enchant.");
                continue;
            }
            enchants.put(enchantment, e.getValue());
        }

        return new CustomItemDefinition(spec.id(), material, name, lore, model, spec.glint(), enchants, spec.unbreakable());
    }

    @Override
    public boolean exists(String id) {
        if (id == null) return false;
        if (definitions.containsKey(id.toLowerCase(Locale.ROOT))) return true;
        for (CustomItemProvider provider : providers.values()) {
            if (findIgnoreCase(provider.ids(), id) != null) return true;
        }
        return false;
    }

    @Override
    public Set<String> ids() {
        Set<String> out = new LinkedHashSet<>();
        for (CustomItemDefinition def : definitions.values()) out.add(def.id());
        for (CustomItemProvider provider : providers.values()) out.addAll(provider.ids());
        return Set.copyOf(out);
    }

    @Override
    public ItemStack create(String id, int amount) {
        return create(id, amount, null);
    }

    @Override
    public ItemStack create(String id, int amount, Player player) {
        if (id == null) return null;
        CustomItemDefinition def = definitions.get(id.toLowerCase(Locale.ROOT));
        if (def != null) return build(def, amount);
        for (CustomItemProvider provider : providers.values()) {
            String own = findIgnoreCase(provider.ids(), id);
            if (own != null) return provider.create(own, amount, player);
        }
        return null;
    }

    private ItemStack build(CustomItemDefinition def, int amount) {
        ItemStack item = new ItemStack(def.material(), amount);
        if (def.name() != null) item.setData(DataComponentTypes.CUSTOM_NAME, def.name());
        if (!def.lore().isEmpty()) item.setData(DataComponentTypes.LORE, ItemLore.lore(def.lore()));
        if (def.model() != null) item.setData(DataComponentTypes.ITEM_MODEL, def.model());
        if (def.glint()) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        if (!def.enchants().isEmpty()) item.setData(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.itemEnchantments(def.enchants()));
        if (def.unbreakable()) item.setData(DataComponentTypes.UNBREAKABLE);

        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public String idOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
    }

    @Override
    public void registerProvider(Plugin owner, CustomItemProvider provider) {
        providers.put(owner, provider);
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        providers.remove(event.getPlugin());
    }

    @Override
    public void registerDefaults(Plugin owner, String resourcePath) {
        InputStream in = owner.getResource(resourcePath);
        if (in == null) {
            plugin.getLogger().warning(owner.getName() + ": default items resource '" + resourcePath + "' not found in its jar.");
            return;
        }
        YamlConfiguration defaults;
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning(owner.getName() + ": could not read '" + resourcePath + "': " + e.getMessage());
            return;
        }

        List<String> missing = DefaultItemMerger.missingIds(defaults, catalog);
        if (missing.isEmpty()) return;

        File target = new File(new File(plugin.getDataFolder(), FOLDER), owner.getName().toLowerCase(Locale.ROOT) + ".yml");
        YamlConfiguration out = target.exists() ? YamlConfiguration.loadConfiguration(target) : new YamlConfiguration();
        DefaultItemMerger.copyEntries(defaults, out, missing);
        try {
            out.save(target);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + target.getName() + ": " + e.getMessage());
            return;
        }
        plugin.getLogger().info("Added " + missing.size() + " default item(s) for " + owner.getName()
                + " to " + FOLDER + "/" + target.getName() + ".");
        reload();
    }

    private static String findIgnoreCase(Set<String> ids, String id) {
        for (String candidate : ids) {
            if (candidate.equalsIgnoreCase(id)) return candidate;
        }
        return null;
    }
}
