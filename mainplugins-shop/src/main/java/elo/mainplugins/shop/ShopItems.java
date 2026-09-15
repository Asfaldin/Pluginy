package elo.mainplugins.shop;

import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.shop.model.ShopItem;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.MusicInstrument;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Przedmioty sklepu: tworzenie (zwykłe i z katalogu), rozpoznawanie przy sprzedaży, nazwy. */
final class ShopItems {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final CustomItemService catalog;
    private final Set<String> warned = new HashSet<>();

    ShopItems(Plugin plugin, CustomItemService catalog) {
        this.plugin = plugin;
        this.catalog = catalog;
    }

    /** Nowy stos tej pozycji (amount sztuk, może przekroczyć 64 - dzieli wołający). Null, gdy przedmiotu z katalogu nie ma. */
    ItemStack create(ShopItem item, int amount, Player player) {
        ItemStack stack;
        if (item.customId() != null) {
            stack = catalog == null ? null : catalog.create(item.customId(), Math.max(1, amount), player);
            if (stack == null) {
                if (warned.add(item.customId())) {
                    plugin.getLogger().warning("Shop: item 'custom: " + item.customId() + "' is not in the item catalog (plugin missing?) - hidden in the shop.");
                }
                return null;
            }
        } else {
            Material m = Material.matchMaterial(item.material());
            if (m == null) return null;
            stack = new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
        }
        applyLook(stack, item);
        return stack;
    }

    /** Własna nazwa, opis i instrument (rogi kóz) z pliku kategorii. */
    private void applyLook(ItemStack stack, ShopItem item) {
        if (item.instrument() != null) {
            MusicInstrument inst = MusicInstrument.getByKey(NamespacedKey.minecraft(item.instrument().toLowerCase(Locale.ROOT)));
            if (inst != null) stack.setData(DataComponentTypes.INSTRUMENT, inst);
            else if (warned.add("instrument:" + item.instrument())) plugin.getLogger().warning("Shop: unknown instrument '" + item.instrument() + "'.");
        }
        if (item.name() == null && item.lore().isEmpty()) return;
        ItemMeta meta = stack.getItemMeta();
        if (item.name() != null) meta.displayName(SER.deserialize(item.name()).decoration(TextDecoration.ITALIC, false));
        if (!item.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String l : item.lore()) lore.add(SER.deserialize(l).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
    }

    /** Klucz przedmiotu z ekwipunku - jak ShopItem.key(): "custom:<id>" dla naszych przedmiotów, inaczej materiał. */
    String keyOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        String id = catalog != null ? catalog.idOf(stack) : null;
        return id != null ? "custom:" + id.toLowerCase(Locale.ROOT) : stack.getType().name();
    }

    /** Nazwa do wiadomości: własna, z katalogu, albo tłumaczona przez grę (w języku gracza). */
    Component name(ShopItem item) {
        if (item.name() != null) return SER.deserialize(item.name());
        if (item.customId() != null) {
            ItemStack s = catalog == null ? null : catalog.create(item.customId(), 1, null);
            if (s != null && s.getItemMeta() != null && s.getItemMeta().hasDisplayName()) return s.getItemMeta().displayName();
            return Component.text(item.customId());
        }
        Material m = Material.matchMaterial(item.material());
        return m != null ? Component.translatable(m.translationKey()) : Component.text(item.material());
    }

    /** Zwykły tekst nazwy - do wyszukiwarki i konsoli. */
    String plainName(ShopItem item) {
        if (item.name() != null) return PlainTextComponentSerializer.plainText().serialize(SER.deserialize(item.name()));
        return item.customId() != null ? item.customId() : item.material().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
