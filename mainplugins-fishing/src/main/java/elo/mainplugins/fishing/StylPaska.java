package elo.mainplugins.fishing;

/**
 * Styl paska minigry łowienia - user 2026-08-30: chciał wybór między dotychczasowym
 * tekstowym paskiem (Unicode bloki ░▒▓█ w BossBarze/action barze, patrz PozycjaPaska -
 * WYŁĄCZNIE góra/dół) a nowym graficznym (custom itemy jako ItemDisplay encje, patrz
 * GraficznyPasek - WYŁĄCZNIE lewo/prawo, patrz StronaPaska). Te dwa style mają WŁASNE,
 * rozłączne ustawienia pozycji - to świadomy wybór usera, nie da się np. graficznego
 * ustawić na górę.
 */
enum StylPaska {
    TEKSTOWY("Tekstowy"),
    GRAFICZNY("Graficzny");

    private final String opis;

    StylPaska(String opis) {
        this.opis = opis;
    }

    String opis() {
        return opis;
    }
}
