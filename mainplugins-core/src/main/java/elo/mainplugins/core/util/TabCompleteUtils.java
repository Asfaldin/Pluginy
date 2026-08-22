package elo.mainplugins.core.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Wspólny helper do podpowiedzi Tab - żeby każdy plugin nie pisał tego samego
 * `StringUtil.copyPartialMatches(...)` od nowa. Bez zarejestrowanego TabCompletera
 * Bukkit sam podpowiada nazwy graczy online na KAŻDEJ pozycji argumentu (myląca
 * podpowiedź przy podkomendach typu /is menu) - stąd potrzeba jawnego onTabComplete
 * niemal wszędzie, nawet dla komend bez argumentów (patrz PUSTA).
 */
public final class TabCompleteUtils {

    private TabCompleteUtils() {}

    /** Brak podpowiedzi - dla komend bez argumentów, żeby nie pokazywały listy graczy z domyślnego fallbacku Bukkita. */
    public static final List<String> PUSTA = List.of();

    public static List<String> dopasuj(String wpisane, Collection<String> opcje) {
        return StringUtil.copyPartialMatches(wpisane, opcje, new ArrayList<>());
    }

    /** Nazwy graczy online - do podpowiedzi argumentu <gracz>. */
    public static List<String> dopasujGraczy(String wpisane) {
        List<String> nazwy = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        return dopasuj(wpisane, nazwy);
    }
}
