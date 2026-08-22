package elo.mainplugins.shop.gui;

import org.bukkit.Material;

/**
 * Jeden wpis w layoucie GUI - slot (0-53) + jego rola. {@code material} ma sens tylko dla
 * {@link ShopSlotRole#FILLER} (nadpisuje domyślny szary panel na tym konkretnym slocie).
 * {@code amount} ma sens tylko dla {@link ShopSlotRole#AMOUNT_SLOT} (ile sztuk reprezentuje
 * ta opcja w ekranie wyboru ilości - dawne równoległe ILOSCI_DO_WYBORU[i]).
 */
public record ShopSlotEntry(int slot, ShopSlotRole role, Material material, Integer amount) {

    public static ShopSlotEntry of(int slot, ShopSlotRole role) {
        return new ShopSlotEntry(slot, role, null, null);
    }
}
