package elo.mainplugins.fishing;

/**
 * Strona ekranu dla GRAFICZNEGO paska minigry (patrz GraficznyPasek, StylPaska.GRAFICZNY)
 * - user 2026-08-30 chciał lewo/prawo dla tego stylu (w odróżnieniu od tekstowego, który
 * zostaje wyłącznie góra/dół, patrz PozycjaPaska). Renderowane jako ItemDisplay encje
 * "dosiadające" gracza - podążają za obrotem CIAŁA gracza, nie za pochyleniem głowy
 * (świadomy kompromis, patrz javadoc GraficznyPasek).
 *
 * GORA/DOL DOPISANE WYŁĄCZNIE do testów (user 2026-08-31: "zrób też na testa, może być
 * ten customowy góra i dół, żeby testować jak to działa") - żeby dało się sprawdzić czy
 * sam mechanizm renderowania (encje/tekstury) w ogóle działa, niezależnie od tego czy
 * offsety lewo/prawo są akurat dobrze dobrane. Docelowo GRAFICZNY ma być lewo/prawo,
 * ale na czas debugowania przełącznik w Ustawieniach przechodzi przez wszystkie 4.
 */
enum StronaPaska {
    LEWO("po lewej stronie"),
    PRAWO("po prawej stronie"),
    GORA("na górze (TEST)"),
    DOL("na dole (TEST)");

    private final String opis;

    StronaPaska(String opis) {
        this.opis = opis;
    }

    String opis() {
        return opis;
    }
}
