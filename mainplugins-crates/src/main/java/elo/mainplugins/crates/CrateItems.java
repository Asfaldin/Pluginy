package elo.mainplugins.crates;

import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.crates.model.CrateConfig;
import elo.mainplugins.crates.model.CrateDef;
import elo.mainplugins.crates.model.ItemRef;
import elo.mainplugins.crates.model.KeyDef;
import elo.mainplugins.crates.model.Prize;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/** Buduje przedmioty skrzynek/kluczy/ikon i rozpoznaje je po tagach (w tym stare przedmioty sprzed pilota). */
final class CrateItems {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final CustomItemService items;
    private final NamespacedKey crateIdTag;
    private final NamespacedKey keyIdTag;
    // Stare tagi (przed pilotem): skrzynka z numerem tieru, jeden uniwersalny klucz.
    private final NamespacedKey legacyBox;
    private final NamespacedKey legacyTier;
    private final NamespacedKey legacyKey;

    CrateItems(Plugin plugin, CustomItemService items) {
        this.plugin = plugin;
        this.items = items;
        this.crateIdTag = new NamespacedKey(plugin, "crate_id");
        this.keyIdTag = new NamespacedKey(plugin, "key_id");
        this.legacyBox = new NamespacedKey(plugin, "crate_box");
        this.legacyTier = new NamespacedKey(plugin, "crate_tier");
        this.legacyKey = new NamespacedKey(plugin, "crate_key");
    }

    static Component text(String legacy) {
        return SER.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    ItemStack crate(CrateDef c, int amount) {
        ItemStack item = base(c.item(), amount);
        style(item, c.name(), c.lore());
        tag(item, crateIdTag, c.id());
        return item;
    }

    ItemStack key(KeyDef k, int amount) {
        ItemStack item = base(k.item(), amount);
        style(item, k.name(), k.lore());
        tag(item, keyIdTag, k.id());
        return item;
    }

    /** Ikona wygranej do animacji/podglądu; extraLore dopisywane jako opis (np. szansa z pliku językowego). */
    ItemStack icon(Prize p, List<Component> extraLore) {
        ItemStack item = base(p.icon(), Math.min(64, p.icon().amount()));
        item.setData(DataComponentTypes.CUSTOM_NAME, text(p.name()));
        if (!extraLore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (Component c : extraLore) lines.add(c.decoration(TextDecoration.ITALIC, false));
            item.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        }
        return item;
    }

    /** Id skrzynki z przedmiotu (stara skrzynka po tierze -> pozycja w pliku), albo null. */
    String crateIdOf(ItemStack item, CrateConfig config) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(crateIdTag, PersistentDataType.STRING);
        if (id != null) return id;
        if (pdc.has(legacyBox, PersistentDataType.BYTE)) {
            Integer tier = pdc.get(legacyTier, PersistentDataType.INTEGER);
            return CrateOdds.legacyCrateId(tier != null ? tier : 1, config.crateIdsInOrder());
        }
        return null;
    }

    /** Id klucza z przedmiotu (stary klucz -> universal_key), albo null. */
    String keyIdOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(keyIdTag, PersistentDataType.STRING);
        if (id != null) return id;
        return pdc.has(legacyKey, PersistentDataType.BYTE) ? "universal_key" : null;
    }

    private ItemStack base(ItemRef ref, int amount) {
        if (ref.isCustom()) {
            ItemStack custom = items != null ? items.create(ref.customId(), amount) : null;
            if (custom != null) return custom;
            plugin.getLogger().warning("Custom item '" + ref.customId() + "' not found in the item catalog - using STONE.");
            return new ItemStack(Material.STONE, amount);
        }
        Material m = Material.matchMaterial(ref.material());
        return new ItemStack(m != null && m.isItem() ? m : Material.STONE, amount);
    }

    private static void style(ItemStack item, String name, List<String> lore) {
        item.setData(DataComponentTypes.CUSTOM_NAME, text(name));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String l : lore) lines.add(text(l));
            item.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        }
    }

    private static void tag(ItemStack item, NamespacedKey key, String value) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        item.setItemMeta(meta);
    }
}
