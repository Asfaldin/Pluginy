package elo.mainplugins.core.util;

import org.bukkit.entity.Player;

/**
 * Formalizuje konwencję, która w oryginalnym kodzie już istniała, ale tylko jako
 * nieudokumentowany "magiczny" string: gdy gracz wchodzi do jakiegoś GUI z poziomu
 * głównego /menu, ostatni argument komendy wywołanej w tle to "zmenu". Dzięki temu
 * pluginy Sklep/Targ/Wyspa/Schowek/Questy w ogóle nie muszą znać się nawzajem ani
 * znać pluginu Menu - komunikują się wyłącznie przez komendę Bukkita, a nie przez
 * bezpośrednie referencje do klas. To jest właściwy odpowiednik "wspólnego API"
 * dla rzeczy, które nie potrzebują serwisu (jak ekonomia), tylko luźnej konwencji.
 */
public final class MenuBridge {

    public static final String ZMENU_ARG = "zmenu";
    public static final String OPEN_MENU_COMMAND = "menu";

    private MenuBridge() {}

    public static boolean isZMenu(String[] args) {
        return args.length > 0 && args[args.length - 1].equalsIgnoreCase(ZMENU_ARG);
    }

    /** Zamyka aktualne GUI gracza i wraca do głównego panelu (pluginu Menu). */
    public static void returnToMainMenu(Player player) {
        player.closeInventory();
        player.performCommand(OPEN_MENU_COMMAND);
    }
}