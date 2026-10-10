package elo.mainplugins.core.customitem;

import java.lang.reflect.Method;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * Własny model przedmiotu (ItemMeta#setItemModel) jest od Minecrafta 1.21.4. Plugin kompilujemy pod
 * najstarszą wspieraną wersję (1.20.6), więc metodę wołamy przez refleksję tylko tam, gdzie istnieje.
 */
final class ItemModelSupport {
    private static final Method SET_ITEM_MODEL = find();
    private static boolean warned;

    private ItemModelSupport() {
    }

    private static Method find() {
        try {
            return ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static boolean supported() {
        return SET_ITEM_MODEL != null;
    }

    /** true = model ustawiony; false = ten serwer nie obsługuje modeli. */
    static boolean apply(ItemMeta meta, NamespacedKey model) {
        if (SET_ITEM_MODEL == null) return false;
        try {
            SET_ITEM_MODEL.invoke(meta, model);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    static void warnOnce(Plugin plugin, String message) {
        if (warned) return;
        warned = true;
        plugin.getLogger().warning(message);
    }
}
