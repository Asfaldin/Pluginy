package elo.mainplugins.redstone.item;

import elo.mainplugins.core.util.CustomItemKeys;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;

/**
 * Rejestr redstone-itemów wczytywany z redstone-items.yml - ta sama filozofia co
 * CustomItemManager (mainplugins-core): czysta dana konfiguracyjna, budowa itemu
 * przez aktualne, nie-deprecated komponenty (ItemStack#setData). Item dostaje DWA
 * tagi PDC: wspólny CustomItemKeys.CUSTOM_ITEM_ID (żeby /@dajcustom-owy ekosystem
 * "widział" te itemy tak samo jak każdy inny custom-id) i własny ITEM_KIND_KEY
 * (żeby RedstoneItemPlacementListener nie musiał robić lookupu do definicji tylko
 * po to, by rozróżnić przewód od sadzarki przy każdym kliknięciu).
 */
public final class RedstoneItemManager {

    public static final NamespacedKey ITEM_KIND_KEY = new NamespacedKey("mainplugins-redstone", "redstone-item-kind");

    private final Plugin plugin;
    private Map<String, RedstoneItemDefinition> definicje;

    public RedstoneItemManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        definicje = RedstoneItemLoader.load(plugin);
        plugin.getLogger().info("Wczytano " + definicje.size() + " redstone-itemów z redstone-items.yml.");
    }

    public boolean exists(String id) {
        return id != null && definicje.containsKey(id);
    }

    public Set<String> ids() {
        return Set.copyOf(definicje.keySet());
    }

    public ItemStack create(String id, int amount) {
        RedstoneItemDefinition def = definicje.get(id);
        if (def == null) return null;

        ItemStack item = new ItemStack(def.material(), amount);
        if (def.name() != null) item.setData(DataComponentTypes.CUSTOM_NAME, def.name());
        if (!def.lore().isEmpty()) item.setData(DataComponentTypes.LORE, ItemLore.lore(def.lore()));
        if (def.model() != null) item.setData(DataComponentTypes.ITEM_MODEL, def.model());
        if (def.glint()) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);

        // Tagi PDC idą przez ItemMeta ostatnie - patrz komentarz w CustomItemManager#create.
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, def.id());
        meta.getPersistentDataContainer().set(ITEM_KIND_KEY, PersistentDataType.STRING, def.kind().name());
        item.setItemMeta(meta);

        return item;
    }

    public RedstoneItemKind kindOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(ITEM_KIND_KEY, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return RedstoneItemKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String idOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
    }
}
