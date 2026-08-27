package elo.mainplugins.fishing;

import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Profil wędki - "styl gry" wpływający na fizykę minigry (patrz FishingMinigame) ORAZ na
 * wagi losowania gatunku (patrz FishingManager.losujRybe). Świadomie side-grade'y, nie
 * tiery progresji: żaden profil nie jest po prostu "lepszy" od innego, tylko inny
 * kompromis trudność suwaka / tempo połowu / szansa na rzadszy łup.
 *
 * Mnożniki minigry mnożą wartości JUŻ wyliczone z rzadkości złowionej ryby (patrz
 * FishingMinigame) - 1.0 = bez zmian względem dotychczasowego (jedynego) zachowania, czyli
 * dokładnie ZROWNOWAZONA.
 *
 * mnoznikRzadkosci potęguje wagę gatunku przez jego rzadkosc().ordinal() (patrz
 * FishingManager.losujRybe): >1.0 faworyzuje rzadsze gatunki, <1.0 faworyzuje pospolitsze,
 * a sama ZWYKLA (ordinal 0) zawsze zostaje bez zmian niezależnie od profilu.
 */
enum WedkaProfil {

    /** Baseline - dokładnie te same liczby co przed wprowadzeniem profili wędek. */
    ZROWNOWAZONA("Zrównoważona", NamedTextColor.WHITE, 1.0, 1.0, 1.0, 1.0, 1.0),

    /** Szeroki suwak, wolna ryba, ale i wolniejsze napełnianie miernika - łatwiej nie
     *  spudłować, za to sam połów trwa wyraźnie dłużej nawet trafiając. Szanse mocno
     *  przesunięte w stronę pospolitszych gatunków (rzadkie/legendarne trafiają się rzadko). */
    CIERPLIWA("Cierpliwego", NamedTextColor.AQUA, 1.60, 0.55, 0.60, 0.60, 0.55),

    /** Wąski suwak, nerwowa/szybka ryba, ale szybkie napełnianie przy trafieniu i
     *  łagodniejsza kara za pudło - wysoki skill ceiling, szybkie połowy dla kogoś kto
     *  trafia. Szanse mocno przesunięte w stronę rzadszych gatunków. */
    SZARPANA("Szarpana", NamedTextColor.RED, 0.55, 1.65, 1.70, 0.75, 1.75);

    private final String nazwa;
    private final NamedTextColor kolor;
    private final double mnoznikSzerokosciSuwaka;
    private final double mnoznikPredkosciRyby;
    private final double mnoznikTempaNapelniania;
    private final double mnoznikTempaOprozniania;
    private final double mnoznikRzadkosci;

    WedkaProfil(String nazwa, NamedTextColor kolor, double mnoznikSzerokosciSuwaka, double mnoznikPredkosciRyby,
                double mnoznikTempaNapelniania, double mnoznikTempaOprozniania, double mnoznikRzadkosci) {
        this.nazwa = nazwa;
        this.kolor = kolor;
        this.mnoznikSzerokosciSuwaka = mnoznikSzerokosciSuwaka;
        this.mnoznikPredkosciRyby = mnoznikPredkosciRyby;
        this.mnoznikTempaNapelniania = mnoznikTempaNapelniania;
        this.mnoznikTempaOprozniania = mnoznikTempaOprozniania;
        this.mnoznikRzadkosci = mnoznikRzadkosci;
    }

    String nazwa() { return nazwa; }
    NamedTextColor kolor() { return kolor; }
    double mnoznikSzerokosciSuwaka() { return mnoznikSzerokosciSuwaka; }
    double mnoznikPredkosciRyby() { return mnoznikPredkosciRyby; }
    double mnoznikTempaNapelniania() { return mnoznikTempaNapelniania; }
    double mnoznikTempaOprozniania() { return mnoznikTempaOprozniania; }
    double mnoznikRzadkosci() { return mnoznikRzadkosci; }
}
