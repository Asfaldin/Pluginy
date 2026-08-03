package elo.mainplugins.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Wspólne klocki do budowania GUI, którymi wcześniej każdy manager (Sklep, Targ,
 * Wyspa, Schowek, Questy...) zajmował się osobno i identycznie. Zmiana wyglądu
 * tła albo przycisku powrotu robi się teraz w jednym miejscu dla całego serwera.
 */
public final class GuiUtils {

    private GuiUtils() {}

    /** Wypełnia całe GUI podanym materiałem (domyślnie szare szkło jako tło). */
    public static void fillBackground(Inventory gui, Material material) {
        ItemStack tlo = new ItemStack(material);
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        for (int i = 0; i < gui.getSize(); i++) {
            gui.setItem(i, tlo);
        }
    }

    public static void fillBackground(Inventory gui) {
        fillBackground(gui, Material.GRAY_STAINED_GLASS_PANE);
    }

    /** Standardowy przedmiot z nazwą i (opcjonalnie) opisem. */
    public static ItemStack namedItem(Material material, Component displayName, Component... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(displayName);
        if (lore.length > 0) {
            meta.lore(java.util.List.of(lore));
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Przycisk "wróć/zamknij" powtarzający się w każdym GUI wywoływanym zarówno
     * z komendy jak i z głównego /menu (patrz {@link MenuBridge}). Gwiazda Netheru
     * -> wracamy do /menu, Barrier -> zwykłe zamknięcie panelu.
     */
    public static ItemStack backOrCloseButton(boolean zMenu, String labelPowrot, String labelZamknij) {
        Material material = zMenu ? Material.NETHER_STAR : Material.BARRIER;
        String label = zMenu ? labelPowrot : labelZamknij;
        return namedItem(material, Component.text(label, NamedTextColor.RED, TextDecoration.BOLD));
    }

    public static ItemStack backOrCloseButton(boolean zMenu) {
        return backOrCloseButton(zMenu, "« Wróć do Menu głównego", "Zamknij");
    }
}