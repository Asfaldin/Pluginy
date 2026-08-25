package elo.mainplugins.quests.generator;

/**
 * Dwa istniejące wzorce generatorów (patrz GeneratorBrukuManager/GeneratorKruchychManager,
 * oba świadomie NIETKNIĘTE - ten silnik jest dla NOWYCH tierów T2-T4, patrz javadoc
 * GeneratorManager) - PRZEPUSZCZAJACY podmienia blok na prawdziwy wanilijski materiał i
 * NIE anuluje eventu (dalsza obróbka, np. bonus z bruku kilofa, leci jak przy zwykłym
 * bloku), BEZPOSREDNI sam nadaje drop z puli i anuluje wanilijski.
 */
public enum TrybGeneratora {
    PRZEPUSZCZAJACY,
    BEZPOSREDNI;

    public static TrybGeneratora zNazwy(String nazwa, TrybGeneratora domyslny) {
        if (nazwa == null) return domyslny;
        try {
            return TrybGeneratora.valueOf(nazwa.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return domyslny;
        }
    }
}
