package elo.mainplugins.shop.gui;

import java.util.List;

/** Jeden ekran GUI sklepu - rozmiar okna (wielokrotność 9, 9-54) + lista slotów. */
public record ScreenLayout(int size, List<ShopSlotEntry> layout) {

    public List<ShopSlotEntry> slotsWithRole(ShopSlotRole role) {
        return layout.stream().filter(e -> e.role() == role).toList();
    }
}
