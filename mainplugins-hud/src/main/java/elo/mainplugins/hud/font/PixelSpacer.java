package elo.mainplugins.hud.font;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.Style;

/**
 * Buduje niewidzialne "dystanse" o precyzyjnej szerokości w pikselach, korzystając z
 * customowego fontu mainplugins:spacer (patrz resourcepack/assets/mainplugins/font/
 * spacer.json w module) - 11 znaków z Private Use Area, każdy o szerokości będącej
 * potęgą 2 (1..1024px, kody U+F801..U+F80B). Dowolną nieujemną liczbę pikseli da się
 * złożyć z sumy podzbioru tych potęg (rozkład binarny), więc dopełnienie do dokładnej
 * szerokości nigdy nie wymaga więcej niż 11 znaków.
 *
 * WYMAGA, żeby gracz miał zaakceptowany ten resource pack - jeśli go nie ma, font
 * mainplugins:spacer nie istnieje po jego stronie i klient wyświetli znaki zastępcze
 * (kwadraciki) zamiast pustych dystansów. Dlatego TablistManager używa tego tylko dla
 * graczy, którzy potwierdzili pobranie paczki (patrz PlayerResourcePackStatusEvent) -
 * reszta dostaje stary fallback (dopełnianie zwykłymi spacjami, mniej dokładne, ale
 * zawsze bezpieczne).
 */
public final class PixelSpacer {

    private static final Key FONT = Key.key("mainplugins", "spacer");

    /** Malejąco - zachłanny rozkład binarny wymaga kolejności od największej "cegiełki". */
    private static final int[] WIDTHS = {1024, 512, 256, 128, 64, 32, 16, 8, 4, 2, 1};
    private static final char[] CHARS = {
            '', '', '', '', '',
            '', '', '', '', '', ''
    };

    private PixelSpacer() {}

    /** Komponent-dystans o dokładnie `pikseli` szerokości (0 lub mniej = pusty komponent). */
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
     * Dokleja do `tekst` niewidzialny dystans, żeby CAŁOŚĆ (tekst + dystans) miała
     * dokładnie `docelowaSzerokoscPx` szerokości - odpowiednik dopełniania spacjami,
     * tylko co do piksela zamiast co do znaku (patrz komentarz klasowy). `surowyTekst`
     * to sama treść (bez kolorów/pogrubienia) - służy tylko do policzenia jej szerokości.
     * Jeśli tekst jest już szerszy niż cel, dystans wynosi 0 (bez przycinania).
     */
    public static Component padTo(Component tekst, String surowyTekst, int docelowaSzerokoscPx) {
        int brakujace = docelowaSzerokoscPx - FontWidths.widthOf(surowyTekst);
        return tekst.append(ofWidth(brakujace));
    }
}