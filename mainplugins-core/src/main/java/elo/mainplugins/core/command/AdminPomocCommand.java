package elo.mainplugins.core.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /@komendy, /@komendy2, /@komendy3 - odpowiednik PomocCommand, ale dla komend ADMINA
 * (wszystkie zaczynają się od "@" - patrz konwencja ustalona 2026-08-17). Sama komenda
 * jest zablokowana permisją (mainplugins.core.admin w plugin.yml) - zwykły gracz nie
 * tylko nie może jej użyć, ale dzięki temu, że Paper filtruje drzewo komend per-gracza,
 * w ogóle nie zobaczy jej w podpowiedziach/autouzupełnianiu.
 *
 * Tak samo jak PomocCommand - lista jest RĘCZNA, nie auto-generowana (patrz komentarz
 * tam). Techniczny "wszystko naraz" dump wciąż istnieje pod /wszystkiekomendy (dawne
 * /adminhelp) - to jest świadomie osobna, krótsza i pogrupowana ściągawka.
 */
public class AdminPomocCommand implements CommandExecutor {

    private record Wpis(String uzycie, String opis) {}
    private record Sekcja(String nazwa, List<Wpis> wpisy) {}

    private static final List<Sekcja> STRONA_1 = List.of(
            new Sekcja("Zarządzanie graczami", List.of(
                    new Wpis("/@setranga <gracz> <gracz|vip|admin>", "Ustaw rangę graczowi"),
                    new Wpis("/@ranga <gracz>", "Sprawdź rangę gracza")
            )),
            new Sekcja("Ekonomia (testowe)", List.of(
                    new Wpis("/@moneyadd [gracz] <kwota>", "Dodaj kasę graczowi"),
                    new Wpis("/@moneyundo [gracz]", "Wyzeruj kasę graczowi")
            )),
            new Sekcja("Ogłoszenia", List.of(
                    new Wpis("/@reloadannouncer", "Wczytaj ogloszenia.yml na nowo")
            )),
            new Sekcja("Sklep", List.of(
                    new Wpis("/@reloadsklep", "Wczytaj konfigurację sklepu na nowo")
            )),
            new Sekcja("Spawn i obszary", List.of(
                    new Wpis("/@setspawn [info]", "Ustaw główny punkt spawnu"),
                    new Wpis("/@obszar <wand|usun|lista|info|moby|border> ...", "Zarządzaj chronionymi obszarami")
            ))
    );

    private static final List<Sekcja> STRONA_2 = List.of(
            new Sekcja("Skrzynki (testowe)", List.of(
                    new Wpis("/@dajklucz", "Daj sobie klucz do skrzynki"),
                    new Wpis("/@dajskrzynia", "Daj sobie Tajemniczą Skrzynkę (tier 1)"),
                    new Wpis("/@dajskrzynie1", "Daj sobie Tajemniczą Skrzynkę (tier 1)"),
                    new Wpis("/@dajskrzynie2", "Daj sobie Otchłanną Skrzynkę (tier 2)"),
                    new Wpis("/@dajskrzynie3", "Daj sobie Skrzynkę DARKSTAR (tier 3)"),
                    new Wpis("/@reloadcrates", "Wczytaj pule nagród wszystkich tierów na nowo")
            )),
            new Sekcja("Wędkarstwo (testowe)", List.of(
                    new Wpis("/@dajwedke <1|2|3>", "Daj sobie wędkę danego tieru"),
                    new Wpis("/@dajrecepture <niebianska|kosmiczna>", "Daj sobie recepturę")
            ))
    );

    private static final List<Sekcja> STRONA_3 = List.of(
            new Sekcja("Questy (testowe)", List.of(
                    new Wpis("/@addfale", "Testowe 5 fal zombie pod graczem, bez nagrody"),
                    new Wpis("/@addkruchy", "Testowe wydanie generatora kruchych surowców"),
                    new Wpis("/@dajbrukgen", "Testowe wydanie Generatora Bruku")
            )),
            new Sekcja("Narzędzia (testowe/debug)", List.of(
                    new Wpis("/@addlvl [ilość]", "Dodaje poziomy trzymanemu narzędziu"),
                    new Wpis("/@addcustompickaxe [gracz]", "Nadaje startowy kilof (Wydajnościowy)"),
                    new Wpis("/@dajwszystko [gracz]", "Nadaje komplet kilofów i gemów do testów")
            )),
            new Sekcja("Inne", List.of(
                    new Wpis("/wszystkiekomendy", "Pełna techniczna lista WSZYSTKICH komend + status modułów")
            ))
    );

    private static final List<List<Sekcja>> STRONY = List.of(STRONA_1, STRONA_2, STRONA_3);

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        int strona = numerStrony(label);
        if (strona < 1 || strona > STRONY.size()) strona = 1;

        sender.sendMessage(Component.text(
                "=== Komendy admina (" + strona + "/" + STRONY.size() + ") ===",
                NamedTextColor.RED, TextDecoration.BOLD));

        for (Sekcja sekcja : STRONY.get(strona - 1)) {
            sender.sendMessage(Component.text("▸ " + sekcja.nazwa(), NamedTextColor.AQUA, TextDecoration.BOLD));
            for (Wpis wpis : sekcja.wpisy()) {
                sender.sendMessage(Component.text("  " + wpis.uzycie(), NamedTextColor.YELLOW)
                        .append(Component.text(" - " + wpis.opis(), NamedTextColor.GRAY)));
            }
        }

        if (strona < STRONY.size()) {
            sender.sendMessage(Component.text("Więcej komend: /@komendy" + (strona + 1), NamedTextColor.DARK_GRAY));
        }
        return true;
    }

    private int numerStrony(String label) {
        Matcher m = Pattern.compile("(\\d+)$").matcher(label);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }
}
