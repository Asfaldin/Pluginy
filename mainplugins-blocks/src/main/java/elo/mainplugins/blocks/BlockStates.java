package elo.mainplugins.blocks;

import java.util.List;

/**
 * Stany note blocka używane przez własne bloki - ta sama numeracja co w aplikacji
 * (desktop-app/src/lib/blockModel.ts, noteState): 16 instrumentów × 25 nut × zasilanie.
 * Stan 0 (harfa, nuta 0, bez zasilania) należy do zwykłych note blocków.
 */
public final class BlockStates {

    public static final List<String> INSTRUMENTS = List.of(
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar",
            "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling");

    public static final int MAX_STATE = INSTRUMENTS.size() * 50 - 1;

    /** Stan zwykłego note blocka - każdy postawiony z ekwipunku dostaje właśnie ten. */
    public static final String VANILLA = state(0);

    private BlockStates() {}

    /** "minecraft:note_block[instrument=...,note=...,powered=...]" - właściwości w kolejności alfabetycznej, jak BlockData#getAsString. */
    public static String state(int index) {
        if (index < 0 || index > MAX_STATE) throw new IllegalArgumentException("Stan poza zakresem 0-" + MAX_STATE + ": " + index);
        String instrument = INSTRUMENTS.get(index / 50);
        int rest = index % 50;
        return "minecraft:note_block[instrument=" + instrument + ",note=" + (rest % 25) + ",powered=" + (rest >= 25) + "]";
    }
}
