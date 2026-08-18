package elo.mainplugins.chatfilter;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.Rank;
import elo.mainplugins.core.api.RankService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.regex.Pattern;

/**
 * Blokuje reklamę na czacie: linki (http/https/www), domeny z popularnymi końcówkami
 * (np. "twojastrona.pl") i adresy IP (np. "17.123.521.21" - do innego serwera). Celowo
 * lista końcówek zamiast "cokolwiek.cokolwiek" - łapie zdecydowaną większość reklam przy
 * praktycznie zerowym ryzyku złapania normalnego zdania z kropką (np. skrótu). Bez
 * obchodzenia trików typu "strona kropka pl" - jeśli to się realnie pojawi, dopiszemy.
 *
 * IP dopasowywane po SAMYM KSZTAŁCIE (cztery grupy cyfr oddzielone kropkami), bez
 * walidacji zakresu 0-255 - łapie też "zepsute"/spreparowane adresy używane do obejścia,
 * a false-positive na czterech kolejnych liczbach oddzielonych kropkami w zwykłej
 * rozmowie jest praktycznie zerowy.
 *
 * Admin bez ograniczenia (tak samo jak ChatLengthManager) - Gracz i VIP objęci filtrem.
 */
public class AntiAdManager implements Listener {

    private static final String KONCOWKI = "pl|com|net|org|gg|io|eu|de|co|xyz|info|tv|me|shop|site|online|club|top|biz";

    private static final Pattern WZORZEC_REKLAMY = Pattern.compile(
            "(?i)(https?://\\S+|www\\.\\S+|\\b[a-z0-9-]+\\.(?:" + KONCOWKI + ")\\b|\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b)"
    );

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (pobierzRange(event.getPlayer()) == Rank.ADMIN) return;

        if (WZORZEC_REKLAMY.matcher(event.getMessage()).find()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "Nie możesz tego napisać - nie wolno reklamować linków, stron ani innych serwerów na czacie!",
                    NamedTextColor.RED));
        }
    }

    private Rank pobierzRange(Player player) {
        RankService rankService = CoreAPI.getRankService();
        return rankService != null ? rankService.getRank(player.getUniqueId()) : Rank.GRACZ;
    }
}
