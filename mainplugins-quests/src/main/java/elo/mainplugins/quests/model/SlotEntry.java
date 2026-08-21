package elo.mainplugins.quests.model;

import org.bukkit.Material;

/**
 * Jeden wpis w layoucie GUI (main-menu.layout albo page-layout kategorii) - slot (0-53) +
 * jego rola. {@code material} ma sens tylko dla {@link SlotRole#FILLER} (nadpisuje domyślne
 * czarne szkło na tym konkretnym slocie) - null oznacza "użyj domyślnego materiału wypełniacza".
 */
public record SlotEntry(int slot, SlotRole role, Material material) {

    public static SlotEntry of(int slot, SlotRole role) {
        return new SlotEntry(slot, role, null);
    }
}
