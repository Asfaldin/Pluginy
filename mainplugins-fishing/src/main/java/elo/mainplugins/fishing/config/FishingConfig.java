package elo.mainplugins.fishing.config;

import java.util.Map;

/**
 * Tuning minigry i bonusowej skrzynki wczytany z fishing-config.yml (patrz
 * FishingConfigLoader) - jeden niemutowalny snapshot, podmieniany w całości przy
 * /@reloadfishing. Gatunki ryb NIE są tutaj - patrz ryby.yml / FishingManager.wczytajGatunki.
 */
public record FishingConfig(MinigryConfig minigra, double bonusowaSkrzynkaSzansaProcent) {

    /**
     * Formuła liniowa bazowa + naTrudnosc*trudnosc, przycięta do [min, max] - PLUS opcjonalne
     * nadpisania dla pojedynczych rzadkości (klucz = RybaGatunek.Rzadkosc.ordinal()), patrz
     * FishingConfigLoader i sekcja "nadpisania" w fishing-config.yml. Dodane 2026-08-31c: user
     * chciał dostroić SAMĄ RZADKĄ bez ruszania sąsiadów, a jedna wspólna prosta na wszystkie
     * 6 rzadkości fizycznie na to nie pozwala (podniesienie/obniżenie jednego punktu zawsze
     * ciągnie za sobą resztę krzywej) - nadpisanie omija formułę CAŁKOWICIE dla danej rzadkości,
     * reszta dalej liczona z bazowa/naTrudnosc jak dotychczas.
     */
    public record Formula(double bazowa, double naTrudnosc, double min, double max, Map<Integer, Double> nadpisania) {
        public double wartosc(int trudnosc) {
            Double nadpisane = nadpisania.get(trudnosc);
            if (nadpisane != null) return nadpisane;
            return Math.max(min, Math.min(max, bazowa + naTrudnosc * trudnosc));
        }
    }

    public record MinigryConfig(
            int szerokoscPaska, double grawitacja, double impulsKlikniecia,
            long okresTickow,
            Formula polowaSzerokosciSuwaka, Formula predkoscRyby, Formula tempoNapelniania, Formula tempoOprozniania
    ) {}
}
