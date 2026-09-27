package elo.mainplugins.crates.model;

/**
 * Wygląd i szybkość okna otwierania (settings.animation w crates.yml).
 * enabled = false: bez ruletki, nagroda od razu. speed: fast | normal | slow.
 * Dźwięki to klucze Minecrafta (np. ui.button.click); pusty = bez dźwięku.
 */
public record CrateAnimation(boolean enabled, String speed, String background, String pointer, String tickSound, String winSound) {

    public static final CrateAnimation DEFAULT =
            new CrateAnimation(true, "normal", "BLACK_STAINED_GLASS_PANE", "YELLOW_STAINED_GLASS_PANE", "ui.button.click", "entity.player.levelup");

    /** Mnożnik opóźnień między klatkami: szybka = krócej, wolna = dłużej. */
    public double delayFactor() {
        return switch (speed) {
            case "fast" -> 0.5;
            case "slow" -> 1.6;
            default -> 1.0;
        };
    }
}
