package elo.mainplugins.tools.evolving;

import org.bukkit.enchantments.Enchantment;

/**
 * Custom nazwany stat (np. "Jakość" zamiast wanilijskiej Wydajności) - wyświetlany w
 * lore/hubie i odczytywalny przez inne pluginy (patrz EvolvingToolManager#statNaPoziomie).
 *
 * Opcjonalnie może być BEZPOŚREDNIO PODPIĘTY pod prawdziwy wanilijski enczant (enchant +
 * enchantMnoznik) - wartość staty na danym poziomie razy mnożnik (w dół do liczby całkowitej)
 * to REALNY poziom tego enczantu na przedmiocie, synchronizowany automatycznie przy każdym
 * odświeżeniu (patrz EvolvingToolManager#odswiezWyglad). Np. "Jakość" 0-15 z enchantMnoznik
 * 0.25 daje Wydajność 0-3 - "Jakość to w tym systemie 1/4 Wydajności". Jeśli enchant == null,
 * stat jest czysto informacyjny (jak dotychczas) - nie napędza żadnego enczantu.
 */
public record ToolStat(String id, String nazwa, double bazowa, double naPoziom, double max, Enchantment enchant, double enchantMnoznik) {
    public double naPoziomie(int poziom) {
        return Math.min(max, bazowa + poziom * naPoziom);
    }

    /** Realny poziom podpiętego enczantu na danym poziomie narzędzia - 0, jeśli brak podpięcia albo wynik nie dobija do 1. */
    public int enchantPoziomNa(int poziom) {
        if (enchant == null) return 0;
        return (int) Math.floor(naPoziomie(poziom) * enchantMnoznik);
    }
}
