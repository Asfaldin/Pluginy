package elo.mainplugins.quests;

import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.quests.model.ItemRef;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Przedmioty z ItemRef (custom z katalogu core), nazwy w języku gry gracza, liczenie i zabieranie z ekwipunku. */
final class QuestItems {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final CustomItemService items;
    private final Set<String> warned = new HashSet<>();

    QuestItems(Plugin plugin, CustomItemService items) {
        this.plugin = plugin;
        this.items = items;
    }

    static Component text(String legacy) {
        return SER.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    ItemStack stack(ItemRef ref, int amount) {
        if (ref.isCustom()) {
            ItemStack custom = items != null ? items.create(ref.customId(), amount) : null;
            if (custom != null) return custom;
            if (warned.add(ref.customId())) {
                plugin.getLogger().warning("Custom item '" + ref.customId() + "' not found in the item catalog - using STONE.");
            }
            return new ItemStack(Material.STONE, amount);
        }
        Material m = Material.matchMaterial(ref.material());
        return new ItemStack(m != null && m.isItem() ? m : Material.STONE, amount);
    }

    /** Zwykły item: nazwa tłumaczona przez grę gracza; custom: nazwa z katalogu. */
    Component name(ItemRef ref) {
        return stack(ref, 1).effectiveName();
    }

    /** "16x Oak Log, 1x Trophy" */
    Component list(List<ItemRef> refs) {
        Component out = Component.empty();
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) out = out.append(Component.text(", "));
            out = out.append(Component.text(refs.get(i).amount() + "x ")).append(name(refs.get(i)));
        }
        return out;
    }

    /** Ikona w menu: przedmiot + nazwa + opis bez kursywy; glow = delikatny blask. */
    ItemStack icon(ItemRef ref, Component name, List<Component> lore, boolean glow) {
        ItemStack item = stack(ref, 1);
        item.setData(DataComponentTypes.CUSTOM_NAME, name.decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (Component c : lore) lines.add(c.decoration(TextDecoration.ITALIC, false));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        if (glow) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return item;
    }

    /** Tło menu bez nazwy; material z pola FILLER albo domyślne z settings.filler. */
    ItemStack filler(String material, ItemRef fallback) {
        ItemStack item = material != null ? stack(new ItemRef(material, null, 1), 1) : stack(fallback, 1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    boolean matches(ItemStack is, ItemRef ref) {
        if (is == null || is.getType().isAir()) return false;
        String id = items != null ? items.idOf(is) : null;
        if (ref.isCustom()) return id != null && id.equalsIgnoreCase(ref.customId());
        return id == null && is.getType().name().equals(ref.material());
    }

    int count(Player player, ItemRef ref) {
        int sum = 0;
        for (ItemStack is : player.getInventory().getStorageContents()) if (matches(is, ref)) sum += is.getAmount();
        return sum;
    }

    void take(Player player, ItemRef ref, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            if (!matches(contents[i], ref)) continue;
            int t = Math.min(contents[i].getAmount(), left);
            contents[i].setAmount(contents[i].getAmount() - t);
            left -= t;
            if (contents[i].getAmount() <= 0) contents[i] = null;
        }
        player.getInventory().setStorageContents(contents);
    }
}
