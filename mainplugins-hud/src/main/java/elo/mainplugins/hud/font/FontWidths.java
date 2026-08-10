package elo.mainplugins.hud.font;

import java.util.HashMap;
import java.util.Map;

/**
 * Szerokości znaków (w pikselach) domyślnej, niepogrubionej czcionki Minecrafta -
 * dane społecznościowe, powszechnie używane w tego typu narzędziach (ta sama baza,
 * na której opiera się m.in. plugin TAB). Nieznany znak dostaje domyślną szerokość
 * DEFAULT_WIDTH (najczęstsza wartość w tej czcionce) - jeśli jakiś znak wygląda
 * "krzywo" w tabie, dopisz go tutaj z poprawną wartością.
 *
 * Polskie znaki diakrytyczne (ą/ć/ę/ł/ń/ó/ś/ź/ż) są przybliżone do szerokości ich
 * łacińskiego odpowiednika (w praktyce różnica rzędu 1px, niezauważalna) - dokładne
 * wartości nie są nigdzie oficjalnie udokumentowane przez Mojang.
 */
public final class FontWidths {

    private static final int DEFAULT_WIDTH = 6;
    private static final Map<Character, Integer> WIDTHS = new HashMap<>();

    static {
        put(" ", 4);
        put("!", 2);
        put("\"", 5);
        put("'", 3);
        put(",", 2);
        put(".", 2);
        put(":", 2);
        put(";", 2);
        put("(", 5);
        put(")", 5);
        put("*", 5);
        put("<", 5);
        put(">", 5);
        put("@", 7);
        put("[", 4);
        put("]", 4);
        put("`", 3);
        put("{", 5);
        put("|", 2);
        put("}", 5);
        put("~", 7);
        put("I", 4);
        put("f", 5);
        put("i", 2);
        put("k", 5);
        put("l", 3);
        put("t", 4);
        // Reszta liter/cyfr (w tym polskie znaki diakrytyczne) i tak trafiłaby w
        // DEFAULT_WIDTH - wypisane jawnie tylko po to, żeby było widać w kodzie, że
        // zostały świadomie uwzględnione, a nie pominięte.
        put("0123456789ABCDEFGHJKLMNOPQRSTUVWXYZabcdeghjmnopqrsuvwxyz", DEFAULT_WIDTH);
        put("ąćęłńóśźżĄĆĘŁŃÓŚŹŻ", DEFAULT_WIDTH);
    }

    private FontWidths() {}

    private static void put(String chars, int width) {
        for (int i = 0; i < chars.length(); i++) WIDTHS.put(chars.charAt(i), width);
    }

    public static int widthOf(char c) {
        return WIDTHS.getOrDefault(c, DEFAULT_WIDTH);
    }

    /** Sumaryczna szerokość (px) całego tekstu w domyślnej, niepogrubionej czcionce. */
    public static int widthOf(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) total += widthOf(text.charAt(i));
        return total;
    }
}
