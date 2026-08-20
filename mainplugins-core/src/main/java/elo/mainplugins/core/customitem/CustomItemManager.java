package elo.mainplugins.core.customitem;

import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.util.CustomItemKeys;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rejestr custom itemów wczytywany z custom-items.yml (patrz javadoc {@link CustomItemService}) -
 * w odróżnieniu od reszty ekosystemu (gdzie wygląd custom itemu to hardkodowana logika Javy w
 * module, który go tworzy, np. PickaxeSkillManager#stworzKilof) każdy wpis tutaj to CZYSTA dana
 * konfiguracyjna: material, nazwa, lore i opcjonalny własny model z resourcepacka. Wygląd budujemy
 * WYŁĄCZNIE aktualnym, nie-deprecated API komponentów (ItemStack#setData(DataComponentTypes...)) -
 * bez ItemMeta#setCustomModelData(int), które Paper oznaczył jako deprecated.
 *
 * Model z resourcepacka to komponent "minecraft:item_model" (patrz DataComponentTypes#ITEM_MODEL) -
 * WARTOŚĆ TA CAŁKOWICIE ZASTĘPUJE domyślny wygląd Materiału. Od 1.21.4 (24w45a) item_model
 * wskazuje na plik DEFINICJI itemu pod assets/<namespace>/items/<ścieżka>.json (DOKŁADNIE ten
 * sam format co assets/minecraft/items/diamond_pickaxe.json w tym resourcepacku, patrz komentarz
 * w kilof_niflheim.json) - NIE bezpośrednio na sam model/mesh. Ten plik definicji z kolei
 * odwołuje się do prawdziwego modelu (parent + textures) pod assets/<namespace>/models/item/.
 * "model" w custom-items.yml to Key "<namespace>:<ścieżka>" (bez ".json") pliku DEFINICJI, np.
 * dla assets/mainplugins/items/przyklad.json wpisz "mainplugins:przyklad" - patrz komentarz
 * w custom-items.yml po pełny przykład obu plików.
 */
public class CustomItemManager implements CustomItemService {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final Map<String, CustomItemDefinition> definicje = new HashMap<>();

    public CustomItemManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** {@inheritDoc} Wczytuje custom-items.yml na nowo z dysku - kopiuje domyślny zasób TYLKO przy pierwszym uruchomieniu (plik jeszcze nie istnieje), tak jak sklep.yml w ShopManager. */
    @Override
    public void reload() {
        File plik = new File(plugin.getDataFolder(), "custom-items.yml");
        if (!plik.exists()) {
            plugin.saveResource("custom-items.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);

        Map<String, CustomItemDefinition> nowe = new HashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection("items");
        if (sekcja != null) {
            for (String id : sekcja.getKeys(false)) {
                CustomItemDefinition def = wczytajWpis(cfg, id);
                if (def != null) nowe.put(id, def);
            }
        }

        definicje.clear();
        definicje.putAll(nowe);
        plugin.getLogger().info("Wczytano " + definicje.size() + " custom itemów z custom-items.yml.");
    }

    private CustomItemDefinition wczytajWpis(FileConfiguration cfg, String id) {
        String path = "items." + id + ".";
        String matName = cfg.getString(path + "material");
        Material material = matName != null ? Material.matchMaterial(matName) : null;
        if (material == null) {
            plugin.getLogger().warning("custom-items.yml: pomijam '" + id + "' - brak/zły material ('" + matName + "').");
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
                plugin.getLogger().warning("custom-items.yml: '" + id + "' ma niepoprawny model ('" + modelRaw + "') - pomijam to pole.");
            }
        }

        boolean glint = cfg.getBoolean(path + "glint", false);

        return new CustomItemDefinition(id, material, name, lore, model, glint);
    }

    @Override
    public boolean exists(String id) {
        return id != null && definicje.containsKey(id);
    }

    @Override
    public Set<String> ids() {
        return Set.copyOf(definicje.keySet());
    }

    @Override
    public ItemStack create(String id, int amount) {
        CustomItemDefinition def = definicje.get(id);
        if (def == null) return null;

        ItemStack item = new ItemStack(def.material(), amount);
        if (def.name() != null) item.setData(DataComponentTypes.CUSTOM_NAME, def.name());
        if (!def.lore().isEmpty()) item.setData(DataComponentTypes.LORE, ItemLore.lore(def.lore()));
        if (def.model() != null) item.setData(DataComponentTypes.ITEM_MODEL, def.model());
        if (def.glint()) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);

        // Tag custom-id w PDC idzie PRZEZ ItemMeta (setData powyżej działa na komponentach
        // bezpośrednio) - musi być ostatni, żeby późniejszy setItemMeta nie nadpisał komponentów
        // ustawionych wyżej starszym stanem meta.
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);

        return item;
    }
}
