package elo.mainplugins.fishing.config;

import elo.mainplugins.fishing.RybaGatunek;

import java.util.List;

/**
 * Cała konfiguracja łowienia wczytana z fishing-config.yml (patrz FishingConfigLoader) -
 * jeden niemutowalny snapshot, podmieniany w całości przy /@reloadfishing.
 */
public record FishingConfig(List<RybaGatunek> gatunki, MinigryConfig minigra, double bonusowaSkrzynkaSzansaProcent) {

    /** Formuła liniowa bazowa + naTrudnosc*trudnosc, przycięta do [min, max]. */
    public record Formula(double bazowa, double naTrudnosc, double min, double max) {
        public double wartosc(int trudnosc) {
            return Math.max(min, Math.min(max, bazowa + naTrudnosc * trudnosc));
        }
    }

    public record MinigryConfig(
            int szerokoscPaska, double grawitacja, double impulsKlikniecia,
            long okresTickow, long maksymalnyCzasTickow,
            Formula polowaSzerokosciSuwaka, Formula predkoscRyby, Formula tempoNapelniania, Formula tempoOprozniania
    ) {}
}
