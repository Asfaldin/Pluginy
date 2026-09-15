package elo.mainplugins.shop.model;

import java.util.List;

/** Okno sklepu: rozmiar i układ pól. */
public record MenuScreen(int size, List<SlotEntry> layout) {

    public List<SlotEntry> withRole(SlotRole role) {
        return layout.stream().filter(e -> e.role() == role).toList();
    }

    /** Pierwsze pole o danej roli albo null. */
    public Integer first(SlotRole role) {
        for (SlotEntry e : layout) if (e.role() == role) return e.slot();
        return null;
    }
}
