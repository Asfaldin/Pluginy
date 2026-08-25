package elo.mainplugins.tools.evolving;

import java.util.Map;
import java.util.NavigableMap;

/**
 * Jedna instancja efektu z YAML - "kitchen sink" rekord (nie każde pole ma sens dla
 * każdego {@link EffectType}, patrz komentarz przy typie w EvolvingToolManager#zastosujEfekt)
 * zamiast osobnej klasy na każdy typ, żeby loader (EvolvingToolLoader) został jeden prosty
 * parser zamiast osobnej gałęzi na typ.
 *
 * Dwa RÓWNOLEGŁE tryby skalowania z poziomem narzędzia - konfigurator wybiera któremu ufać:
 * 1) LINIOWY (domyślny, jak dotychczas) - szansa/kwota = bazowa + poziom * naPoziom, capowane
 *    na max. Szybkie "procentowe" dostrajanie jednym wzorem.
 * 2) JAWNA PROGRESJA (opcjonalna, patrz szansaProgresja/kwotaProgresja) - dokładnie taki sam
 *    mechanizm co EnchantProgress: mapa POZIOM -> WARTOŚĆ, używana wartość to najwyższy próg
 *    <= aktualny poziom. Gdy mapa jest NIEPUSTA, całkowicie ZASTĘPUJE wzór liniowy - pełna,
 *    ręczna kontrola "ile dokładnie na którym poziomie", bez kompromisu jednego wzoru na cały
 *    zakres.
 */
public record ToolEffect(
        EffectType typ,
        double szansaBazowa,
        double szansaNaPoziom,
        double szansaMax,
        double kwotaBazowa,
        double kwotaNaPoziom,
        String mikstura,
        int poziomMikstury,
        String czastka,
        String dzwiek,
        double promien,
        String przedmiotMaterial,
        String przedmiotCustomId,
        NavigableMap<Integer, Double> szansaProgresja,
        NavigableMap<Integer, Double> kwotaProgresja
) {
    public double szansaNaPoziomie(int poziom) {
        if (szansaProgresja != null && !szansaProgresja.isEmpty()) {
            Map.Entry<Integer, Double> wpis = szansaProgresja.floorEntry(poziom);
            return wpis == null ? 0 : wpis.getValue();
        }
        return Math.min(szansaMax, szansaBazowa + poziom * szansaNaPoziom);
    }

    public double kwotaNaPoziomie(int poziom) {
        if (kwotaProgresja != null && !kwotaProgresja.isEmpty()) {
            Map.Entry<Integer, Double> wpis = kwotaProgresja.floorEntry(poziom);
            return wpis == null ? 0 : wpis.getValue();
        }
        return kwotaBazowa + poziom * kwotaNaPoziom;
    }
}
