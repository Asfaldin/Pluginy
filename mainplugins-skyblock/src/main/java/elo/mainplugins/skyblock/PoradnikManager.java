package elo.mainplugins.skyblock;

import elo.mainplugins.skyblock.event.IslandCreatedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.List;

/**
 * Daje nowemu właścicielowi wyspy (patrz IslandManager.stworzWyspe - dokładnie w momencie
 * IslandCreatedEvent) pisaną książkę tłumaczącą prostymi słowami całą resztę ekosystemu
 * Mainplugins - komendy, ekonomię, sklep, questy, ulepszenia, spawnery, itemy specjalne.
 * Czysta treść tekstowa - zero zależności od innych modułów, więc jeśli jakiś opisany
 * tu system (np. mainplugins-quests) akurat nie jest wgrany, książka po prostu opisuje
 * coś, czego serwer chwilowo nie ma - nic tu się nie wywali.
 */
public class PoradnikManager implements Listener {

    @EventHandler
    public void onIslandCreated(IslandCreatedEvent event) {
        event.getPlayer().getInventory().addItem(stworzPoradnik());
    }

    private ItemStack stworzPoradnik() {
        ItemStack ksiazka = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) ksiazka.getItemMeta();

        meta.title(Component.text("Poradnik Wyspiarza"));
        meta.author(Component.text("Serwer"));
        meta.pages(List.of(
                strona("PORADNIK WYSPIARZA",
                        "Witaj na własnej wyspie!\n\n" +
                        "Ta książka tłumaczy krok po kroku, co jest czym na serwerze. " +
                        "Przewracaj strony strzałkami na dole ekranu."),

                strona("KOMENDY WYSPY",
                        "/is - teleport na wyspę\n" +
                        "/is home - to samo co /is\n" +
                        "/is sethome - ustaw punkt powrotu\n" +
                        "/is border - powiększ granice\n" +
                        "/is invite <gracz> - zaproś kogoś"),

                strona("JAK ZARABIAĆ",
                        "Waluta serwera to zwykłe $.\n\n" +
                        "Zarabiasz sprzedając surowce w sklepie (/sklep) oraz " +
                        "kończąc zadania (/zadania) - obie drogi dają realną kasę."),

                strona("SKLEP SERWEROWY",
                        "/sklep - otwórz sklep\n" +
                        "/sprzedaj - sprzedaj to, co trzymasz w ręce\n" +
                        "/sprzedajwszystko - sprzedaj WSZYSTKO tego typu z eq\n\n" +
                        "LPM w sklepie = kup, PPM = sprzedaj."),

                strona("BANK WYSPY",
                        "Twoja wyspa ma WŁASNY bank, osobny od Twojej kasy osobistej.\n\n" +
                        "/is deposit <kwota> - wpłać do banku wyspy\n" +
                        "/is withdraw <kwota> - wypłać z banku\n\n" +
                        "Ulepszenia wyspy płacisz Z BANKU, nie z portfela!"),

                strona("ULEPSZENIA WYSPY",
                        "W panelu wyspy (/is) znajdziesz Ulepszenia Wyspy - " +
                        "powiększanie granic, poziomy spawnerów dropów i inne, " +
                        "opłacane z banku wyspy (patrz poprzednia strona)."),

                strona("ZADANIA (QUESTY)",
                        "/zadania otwiera menu w kształcie gwiazdy.\n\n" +
                        "Środek gwiazdy to GŁÓWNA ŚCIEŻKA - zadania fabularne, " +
                        "jedno po drugim. Ramiona dookoła to zadania poboczne " +
                        "i specjalne, w dowolnej kolejności."),

                strona("GŁÓWNA ŚCIEŻKA",
                        "40 zadań prowadzących Cię za rękę od pierwszego dnia " +
                        "aż po pokonanie Enderdragona.\n\n" +
                        "Zadanie zablokowane (szare) odblokuje się, gdy ukończysz " +
                        "poprzednie. Kolejne zadanie NIE zdradza nagrody z góry!"),

                strona("ZADANIA POBOCZNE",
                        "Boczne ramiona gwiazdy (Górnictwo, Rolnictwo, Łowca i inne) " +
                        "możesz robić w DOWOLNEJ kolejności.\n\n" +
                        "Questy Specjalne (ramię z gwiazdą netheru) to trudniejsze, " +
                        "rzadsze wyzwania dla zaawansowanych graczy."),

                strona("NARZĘDZIA",
                        "/narzedzia daje Ci zestaw ewoluujących narzędzi - " +
                        "levelują się same, gdy ich używasz.\n\n" +
                        "Kilof ma WŁASNE drzewko umiejętności - kliknij nim Shift + PPM, " +
                        "żeby je otworzyć."),

                strona("SPAWNERY",
                        "W sklepie (kategoria Spawnery) kupisz spawner konkretnego " +
                        "moba - postaw go na wyspie, a będzie sam generował dropy.\n\n" +
                        "Poziom spawnera podbijesz w Ulepszeniach Wyspy."),

                strona("OD CZEGO ZACZĄĆ?",
                        "1. Otwórz /zadania i zrób pierwsze zadanie Głównej Ścieżki\n" +
                        "2. Zbieraj podstawowe surowce i sprzedawaj w /sklep\n" +
                        "3. Wpłać zarobek do banku wyspy (/is deposit)\n" +
                        "4. Kupuj ulepszenia, gdy zbierzesz kwotę"),

                strona("POWODZENIA!",
                        "To wszystkie podstawy - reszty dowiesz się grając.\n\n" +
                        "Zgub tę książkę? Nie szkodzi, wiedza już w Tobie zostaje. " +
                        "Miłej zabawy na wyspie!")
        ));

        ksiazka.setItemMeta(meta);
        return ksiazka;
    }

    private Component strona(String tytul, String tresc) {
        return Component.text(tytul, NamedTextColor.DARK_BLUE, TextDecoration.BOLD)
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text(tresc, NamedTextColor.BLACK).decoration(TextDecoration.BOLD, false));
    }
}