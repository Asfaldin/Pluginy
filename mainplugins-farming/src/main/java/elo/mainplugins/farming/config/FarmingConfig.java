package elo.mainplugins.farming.config;

/**
 * Konfiguracja upraw specjalnych wczytana z farming-config.yml (patrz FarmingConfigLoader)
 * - jeden niemutowalny snapshot, podmieniany w całości przy /@reloadfarming.
 */
public record FarmingConfig(int zlotaMarchewkaIloscMin, int zlotaMarchewkaIloscMax) {

    public int losowaIloscZlotejMarchewki() {
        int rozpietosc = Math.max(0, zlotaMarchewkaIloscMax - zlotaMarchewkaIloscMin);
        return zlotaMarchewkaIloscMin + (int) (Math.random() * (rozpietosc + 1));
    }
}
