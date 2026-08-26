package elo.mainplugins.fishing.config;

/**
 * Tuning minigry i bonusowej skrzynki wczytany z fishing-config.yml (patrz
 * FishingConfigLoader) - jeden niemutowalny snapshot, podmieniany w całości przy
 * /@reloadfishing. Gatunki ryb NIE są tutaj - patrz ryby.yml / FishingManager.wczytajGatunki.
 */
public record FishingConfig(MinigryConfig minigra, double bonusowaSkrzynkaSzansaProcent) {

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
