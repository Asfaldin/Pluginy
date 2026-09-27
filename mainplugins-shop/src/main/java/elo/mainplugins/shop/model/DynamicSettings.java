package elo.mainplugins.shop.model;

/**
 * Ceny dynamiczne skupu (shop.yml dynamic-prices). announceEvents = ogłoszenie eventu na czacie,
 * announceReset = ogłoszenie globalnego resetu cen. tuning = strojenie mechaniki (patrz Tuning).
 */
public record DynamicSettings(boolean enabled, int cycleMinutes, double minMultiplier, double maxMultiplier,
                              int resetDays, double maxSellShare, boolean announceEvents, boolean announceReset,
                              Tuning tuning) {

    /**
     * Liczby strojące zachowanie cyklu. Domyślne wartości = te, na których system był
     * zaprojektowany i przetestowany - zmiana ich zmienia tempo całego rynku.
     *
     * @param maxDropPerCycle   maksymalny spadek w jednym cyklu przy zwykłej cenie (0.05 = 5%)
     * @param dropAtTop         ile razy mocniejszy jest spadek, gdy cena stoi na szczycie
     * @param recoverFromBelow  jaka część drogi do zwykłej ceny wraca w jednym cyklu ciszy (0.8 = 80%)
     * @param risePerCycle      o ile rośnie cena na cykl, gdy przedmiot leży odłogiem (0.125 = 12,5%)
     * @param quietThreshold    sprzedaż poniżej tej części normy to "cisza" (0.1 = 10%)
     * @param cyclesToRise      ile cykli ciszy, zanim cena zacznie rosnąć ponad zwykłą
     * @param cyclesFrozen      ile cykli cena stoi na zwykłej po zejściu z góry
     * @param normLearnRate     jak szybko norma zapomina stare cykle (0.02 ≈ tydzień przy cyklu godzinnym)
     */
    public record Tuning(double maxDropPerCycle, double dropAtTop, double recoverFromBelow, double risePerCycle,
                         double quietThreshold, int cyclesToRise, int cyclesFrozen, double normLearnRate) {

        public static Tuning defaults() {
            return new Tuning(0.05, 4.2, 0.80, 0.125, 0.10, 2, 2, 0.02);
        }
    }

    public static DynamicSettings defaults() {
        return new DynamicSettings(true, 60, 0.5, 1.5, 14, 0.9, true, true, Tuning.defaults());
    }
}
