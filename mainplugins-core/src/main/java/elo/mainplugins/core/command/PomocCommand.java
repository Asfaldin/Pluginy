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
 * /komendy, /komendy2, /komendy3, /komendy4, /help, /pomoc - wyłącznie komendy DLA GRACZA,
 * ręcznie dobrane i opisane (w przeciwieństwie do /wszystkiekomendy, które przez CommandCatalog
 * wypisuje DOSŁOWNIE wszystko z każdego plugin.yml, w tym komendy testowe/administracyjne - to
 * świadomie osobna, "techniczna" ścieżka dla operatora).
 *
 * Podzielone na strony, żeby nie zalać czatu jedną wielką wiadomością - "/komendy2"/"3"/"4"
 * to osobne wpisy w plugin.yml (ten sam executor), rozróżniane po ostatniej cyfrze w "label"
 * (czyli w alisie, którego gracz faktycznie użył). "/help" i "/pomoc" są aliasami WYŁĄCZNIE
 * pierwszej strony - nie mają swoich "2"/"3"/"4" wariantów.
 *
 * Wyspa (/is) ma tyle podkomend (patrz IslandManager#handleCommand), że dostała całą stronę
 * dla siebie - rozpisane pojedynczo, zamiast jednej ogólnej linijki "/is menu otwiera panel",
 * żeby gracz od razu widział co faktycznie da się wpisać. Angielskie aliasy (border/guests/
 * mobs/upgrade/members/invite/accept/deny/leave/promote/demote/remove/home/sethome/deposit/
 * withdraw) celowo pominięte tutaj - liczy się polska forma główna, angielska nadal działa.
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
                    new Wpis("/is", "Teleport do punktu z \"/is ustawspawn\" (stwórz wyspę, jeśli jeszcze jej nie masz)"),
                    new Wpis("/is ustawspawn", "Ustaw punkt teleportu dla samego /is, tam gdzie stoisz"),
                    new Wpis("/is ustawdom", "Ustaw punkt teleportu dla /dom i /home, tam gdzie stoisz"),
                    new Wpis("/dom (/home)", "Teleport do punktu z \"/is ustawdom\" - NIEZALEŻNY punkt od /is"),
                    new Wpis("/is dom", "To samo co /dom/home"),
                    new Wpis("/is menu", "Panel wyspy ze wszystkimi opcjami"),
                    new Wpis("/is usun", "Usuń całą wyspę (nieodwracalne, wymaga potwierdzenia)"),
                    new Wpis("/is granica", "Włącz/wyłącz widoczną granicę wyspy"),
                    new Wpis("/is budowanie", "Zezwól/zablokuj budowanie gościom"),
                    new Wpis("/is pvp", "Włącz/wyłącz PvP na wyspie"),
                    new Wpis("/is potwory", "Włącz/wyłącz spawn potworów na wyspie"),
                    new Wpis("/is ulepszenia", "Panel ulepszeń (powiększanie terenu itd.)"),
                    new Wpis("/is czlonkowie", "Panel członków wyspy"),
                    new Wpis("/is zapros <gracz>", "Zaproś gracza na członka wyspy"),
                    new Wpis("/is akceptuj", "Zaakceptuj zaproszenie na czyjąś wyspę"),
                    new Wpis("/is odrzuc", "Odrzuć zaproszenie"),
                    new Wpis("/is opusc", "Opuść wyspę, na której jesteś członkiem"),
                    new Wpis("/is awansuj <gracz>", "Awansuj członka na admina wyspy"),
                    new Wpis("/is degraduj <gracz>", "Cofnij admina do zwykłego członka"),
                    new Wpis("/is wyrzuc <gracz>", "Wyrzuć gracza z wyspy"),
                    new Wpis("/is wplac <kwota>", "Wpłać pieniądze do banku wyspy"),
                    new Wpis("/is wyplac <kwota>", "Wypłać pieniądze z banku wyspy")
            ))
    );

    private static final List<Sekcja> STRONA_2 = List.of(
            new Sekcja("Ekonomia", List.of(
                    new Wpis("/portfel (/p, /money)", "Sprawdź ile masz pieniędzy"),
                    new Wpis("/przelej <gracz> <kwota> (/pay)", "Przelej pieniądze innemu graczowi")
            )),
            new Sekcja("Sklep i Targ", List.of(
                    new Wpis("/sklep (/buy)", "Otwórz sklep serwerowy"),
                    new Wpis("/sprzedaj (/sell)", "Sprzedaj przedmiot trzymany w ręce"),
                    new Wpis("/sprzedajwszystko (/sellall)", "Sprzedaj wszystkie przedmioty tego typu z ekwipunku"),
                    new Wpis("/targ", "Otwórz targ - handel między graczami"),
                    new Wpis("/targ wystaw <cena>", "Wystaw przedmiot trzymany w ręce na targ")
            ))
    );

    private static final List<Sekcja> STRONA_3 = List.of(
            new Sekcja("Teleportacja", List.of(
                    new Wpis("/spawn", "Teleport na spawn serwera"),
                    new Wpis("/warp [nazwa]", "Teleport do nazwanego warpu (niektóre wymagają ukończenia questa)"),
                    new Wpis("/teleportuj <gracz> (/tp)", "Wyślij prośbę o teleportację do gracza"),
                    new Wpis("/tpakceptuj (/tpaccept)", "Zaakceptuj prośbę o teleport"),
                    new Wpis("/tpodrzuc (/tpdeny)", "Odrzuć prośbę o teleport")
            )),
            new Sekcja("Lochy i Bossy", List.of(
                    new Wpis("/tpdun", "Teleport do lochu (fale mobów, na końcu boss)"),
                    new Wpis("/tpboss", "Teleport na arenę do walki z bossem 1v1")
            ))
    );

    private static final List<Sekcja> STRONA_4 = List.of(
            new Sekcja("Narzędzia i Zadania", List.of(
                    new Wpis("/narzedzia", "Odbierz startowe, ulepszalne narzędzia"),
                    new Wpis("/zadania (/quest)", "Otwórz listę zadań i odbierz nagrody"),
                    new Wpis("/wedka", "Odbierz wędkę do łowienia")
            )),
            new Sekcja("Inne", List.of(
                    new Wpis("/itemy", "Otwórz schowek - bezpieczne miejsce na przedmioty"),
                    new Wpis("/wycisz <gracz> (/mute)", "Wycisz gracza tylko dla siebie (ponownie = odcisz)"),
                    new Wpis("/menu", "Otwórz główne menu z szybkim dostępem do wszystkiego"),
                    new Wpis("/discord", "Wyślij sobie klikalny link do naszego Discorda")
            ))
    );

    private static final List<List<Sekcja>> STRONY = List.of(STRONA_1, STRONA_2, STRONA_3, STRONA_4);

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
