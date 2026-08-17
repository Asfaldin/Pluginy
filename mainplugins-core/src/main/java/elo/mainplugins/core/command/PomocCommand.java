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
 * /komendy, /komendy2, /komendy3, /help, /pomoc - wyłącznie komendy DLA GRACZA, ręcznie
 * dobrane i opisane (w przeciwieństwie do /wszystkiekomendy, które przez CommandCatalog wypisuje
 * DOSŁOWNIE wszystko z każdego plugin.yml, w tym komendy testowe/administracyjne - to
 * świadomie osobna, "techniczna" ścieżka dla operatora).
 *
 * Podzielone na strony, żeby nie zalać czatu jedną wielką wiadomością - "/komendy2" i
 * "/komendy3" to osobne wpisy w plugin.yml (ten sam executor), rozróżniane po ostatniej
 * cyfrze w "label" (czyli w alisie, którego gracz faktycznie użył). "/help" i "/pomoc"
 * są aliasami WYŁĄCZNIE pierwszej strony - nie mają swoich "2"/"3" wariantów.
 *
 * Sekcje/opisy trzymane ręcznie (nie auto-generowane) - to jest świadomy wybór, żeby dać
 * krótkie, sensowne opisy pogrupowane tematycznie zamiast suchej listy z plugin.yml. Przy
 * dodaniu nowej komendy dla gracza trzeba dopisać ją tutaj ręcznie.
 */
public class PomocCommand implements CommandExecutor {

    private record Wpis(String uzycie, String opis) {}
    private record Sekcja(String nazwa, List<Wpis> wpisy) {}

    private static final List<Sekcja> STRONA_1 = List.of(
            new Sekcja("Wyspa", List.of(
                    new Wpis("/is", "Teleport na wyspę (\"/is menu\" otwiera panel ze wszystkimi opcjami)"),
                    new Wpis("/home", "Teleport na wyspę")
            )),
            new Sekcja("Ekonomia", List.of(
                    new Wpis("/portfel (/p, /money)", "Sprawdź ile masz pieniędzy"),
                    new Wpis("/pay <gracz> <kwota> (/daj)", "Przelej pieniądze innemu graczowi")
            )),
            new Sekcja("Sklep i Targ", List.of(
                    new Wpis("/sklep (/buy)", "Otwórz sklep serwerowy"),
                    new Wpis("/sell", "Sprzedaj przedmiot trzymany w ręce"),
                    new Wpis("/sellall", "Sprzedaj wszystkie przedmioty tego typu z ekwipunku"),
                    new Wpis("/targ", "Otwórz targ - handel między graczami")
            ))
    );

    private static final List<Sekcja> STRONA_2 = List.of(
            new Sekcja("Teleportacja", List.of(
                    new Wpis("/spawn", "Teleport na spawn serwera"),
                    new Wpis("/tp <gracz>", "Wyślij prośbę o teleportację do gracza"),
                    new Wpis("/tpaccept", "Zaakceptuj prośbę o teleport"),
                    new Wpis("/tpdeny", "Odrzuć prośbę o teleport")
            )),
            new Sekcja("Lochy i Bossy", List.of(
                    new Wpis("/tpdun", "Teleport do lochu (fale mobów, na końcu boss)"),
                    new Wpis("/tpboss", "Teleport na arenę do walki z bossem 1v1")
            )),
            new Sekcja("Narzędzia i Zadania", List.of(
                    new Wpis("/narzedzia", "Odbierz startowe, ulepszalne narzędzia"),
                    new Wpis("/quest", "Otwórz listę zadań i odbierz nagrody")
            ))
    );

    private static final List<Sekcja> STRONA_3 = List.of(
            new Sekcja("Inne", List.of(
                    new Wpis("/itemy", "Otwórz schowek - bezpieczne miejsce na przedmioty"),
                    new Wpis("/mute <gracz> (/wycisz)", "Wycisz gracza tylko dla siebie (ponownie = odcisz)"),
                    new Wpis("/menu", "Otwórz główne menu z szybkim dostępem do wszystkiego")
            ))
    );

    private static final List<List<Sekcja>> STRONY = List.of(STRONA_1, STRONA_2, STRONA_3);

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        int strona = numerStrony(label);
        if (strona < 1 || strona > STRONY.size()) strona = 1;

        sender.sendMessage(Component.text(
                "=== Komendy gracza (" + strona + "/" + STRONY.size() + ") ===",
                NamedTextColor.GOLD, TextDecoration.BOLD));

        for (Sekcja sekcja : STRONY.get(strona - 1)) {
            sender.sendMessage(Component.text("▸ " + sekcja.nazwa(), NamedTextColor.AQUA, TextDecoration.BOLD));
            for (Wpis wpis : sekcja.wpisy()) {
                sender.sendMessage(Component.text("  " + wpis.uzycie(), NamedTextColor.YELLOW)
                        .append(Component.text(" - " + wpis.opis(), NamedTextColor.GRAY)));
            }
        }

        if (strona < STRONY.size()) {
            sender.sendMessage(Component.text("Więcej komend: /komendy" + (strona + 1), NamedTextColor.DARK_GRAY));
        }
        return true;
    }

    /** Ostatnia cyfra w labelu ("komendy2" -> 2) - brak cyfry ("komendy"/"help"/"pomoc") = strona 1. */
    private int numerStrony(String label) {
        Matcher m = Pattern.compile("(\\d+)$").matcher(label);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }
}
