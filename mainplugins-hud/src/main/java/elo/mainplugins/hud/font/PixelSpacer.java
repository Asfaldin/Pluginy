package elo.mainplugins.hud.font;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;

/**
 * Buduje niewidzialne "dystanse" o precyzyjnej szerokosci w pikselach, korzystajac z
 * customowego fontu mainplugins:spacer (patrz resourcepack/assets/mainplugins/font/
 * spacer.json w module) - 11 znakow z Private Use Area, kazdy o szerokosci bedacej
 * potega 2 (1..1024px, kody U+F801..U+F80B). Dowolna nieujemna liczbe pikseli da sie
 * zlozyc z sumy podzbioru tych poteg (rozklad binarny), wiec dopelnienie do dokladnej
 * szerokosci nigdy nie wymaga wiecej niz 11 znakow.
 *
 * WAZNE: znaki wpisane jako escape'owy zapis Unicode w kodzie (czysty ASCII w pliku zrodlowym), NIE jako
 * surowe bajty Unicode - inaczej kodowanie pliku na Windowsie potrafi je po cichu
 * uszkodzic przy zapisie/odczycie, dajac przy kompilacji INNE (przypadkowe) kody niz
 * zamierzone, mimo ze kod nadal sie kompiluje bez bledu (patrz historia tego pliku -
 * to byl faktyczny powod "losowych prostokatow" w tabie zamiast pustych dystansow).
 *
 * WYMAGA, zeby gracz mial zaakceptowany ten resource pack - jesli go nie ma, font
 * mainplugins:spacer nie istnieje po jego stronie i klient wyswietli znaki zastepcze
 * (kwadraciki) zamiast pustych dystansow. Dlatego TablistManager uzywa tego tylko dla
 * graczy, ktorzy potwierdzili pobranie paczki (patrz PlayerResourcePackStatusEvent) -
 * reszta dostaje stary fallback (dopelnianie zwyklymi spacjami, mniej dokladne, ale
 * zawsze bezpieczne).
 */
public final class PixelSpacer {

    private static final Key FONT = Key.key("mainplugins", "spacer");

    /** Malejaco - zachlanny rozklad binarny wymaga kolejnosci od najwiekszej "cegielki". */
    private static final int[] WIDTHS = {1024, 512, 256, 128, 64, 32, 16, 8, 4, 2, 1};
    private static final char[] CHARS = {
            '\uF80B', '\uF80A', '\uF809', '\uF808', '\uF807',
            '\uF806', '\uF805', '\uF804', '\uF803', '\uF802', '\uF801'
    };

    private PixelSpacer() {}

    /** Komponent-dystans o dokladnie `pikseli` szerokosci (0 lub mniej = pusty komponent). */
    public static Component ofWidth(int pikseli) {
        if (pikseli <= 0) return Component.empty();
        StringBuilder sb = new StringBuilder();
        int pozostalo = pikseli;
        for (int i = 0; i < WIDTHS.length; i++) {
            if (pozostalo >= WIDTHS[i]) {
                sb.append(CHARS[i]);
                pozostalo -= WIDTHS[i];
            }
        }
        return Component.text(sb.toString()).style(Style.style().font(FONT).build());
    }

    /**
     * Dokleja do `tekst` niewidzialny dystans, zeby CALOSC (tekst + dystans) miala
     * dokladnie `docelowaSzerokoscPx` szerokosci - odpowiednik dopelniania spacjami,
     * tylko co do piksela zamiast co do znaku (patrz komentarz klasowy). `surowyTekst`
     * to sama tresc (bez kolorow/pogrubienia) - sluzy tylko do policzenia jej szerokosci.
     * Jesli tekst jest juz szerszy niz cel, dystans wynosi 0 (bez przycinania).
     */
    public static Component padTo(Component tekst, String surowyTekst, int docelowaSzerokoscPx) {
        int brakujace = docelowaSzerokoscPx - FontWidths.widthOf(surowyTekst);
        return tekst.append(ofWidth(brakujace));
    }
}
