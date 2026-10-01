package elo.mainplugins.blocks;

/**
 * Tempo kopania własnego bloku. Klient kopie go jak note block (twardość 0.8, szybciej siekierą),
 * więc na czas kopania dostaje mnożnik atrybutu block_break_speed = tempo, jakie ma mieć nasz blok,
 * podzielone przez tempo note blocka. Efekty (pośpiech, woda, skok) mnożą oba tak samo i się skracają.
 *
 * Wzór z gry na postęp na tick: szybkość narzędzia / twardość / (30 z właściwym narzędziem, inaczej 100).
 */
public final class BreakSpeed {

    static final double NOTE_BLOCK_HARDNESS = 0.8;
    /** Górna granica atrybutu block_break_speed w grze. */
    static final double MAX_MULTIPLIER = 1024;

    private BreakSpeed() {}

    /**
     * @param noteSpeed  szybkość trzymanego przedmiotu na note blocku (z zaklęciami)
     * @param toolSpeed  szybkość trzymanego przedmiotu na bloku wzorcowym dla narzędzia bloku (1 = ręka / złe narzędzie)
     * @param hardness   twardość naszego bloku (mniej niż 0 = niezniszczalny)
     * @param canHarvest czy trzymany przedmiot pozwala na drop (inaczej kopanie jak w grze ~3,3× wolniejsze)
     * @return mnożnik atrybutu (0 = nie da się wykopać)
     */
    public static double multiplier(double noteSpeed, double toolSpeed, double hardness, boolean canHarvest) {
        if (hardness < 0) return 0;
        if (hardness == 0) return MAX_MULTIPLIER;
        double current = Math.max(noteSpeed, 0.0001) / NOTE_BLOCK_HARDNESS / 30.0;
        double wanted = Math.max(toolSpeed, 0) / hardness / (canHarvest ? 30.0 : 100.0);
        return Math.max(0, Math.min(MAX_MULTIPLIER, wanted / current));
    }
}
