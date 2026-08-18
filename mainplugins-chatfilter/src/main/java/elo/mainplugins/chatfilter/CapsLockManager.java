package elo.mainplugins.chatfilter;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.Rank;
import elo.mainplugins.core.api.RankService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Blokuje wiadomości pisane w większości WIELKIMI LITERAMI (>60% liter to caps). Krótkie
 * wiadomości (poniżej MIN_DLUGOSC znaków) są pominięte, żeby nie łapać normalnych "OK",
 * "GG", "LOL" itp. - to naturalny sposób pisania krótkich zwrotów, nie "krzyczenie".
 *
 * Liczymy TYLKO litery (Character.isUpperCase/isLowerCase - działa też na polskie znaki
 * jak Ą/Ł/Ż) - cyfry, spacje i symbole nie wliczają się do proporcji w żadną stronę.
 */
public class CapsLockManager implements Listener {

    private static final int MIN_DLUGOSC = 8;
    private static final double PROG_CAPS = 0.6;

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (pobierzRange(event.getPlayer()) == Rank.ADMIN) return;

        String wiadomosc = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (wiadomosc.length() < MIN_DLUGOSC) return;

        int wielkie = 0;
        int litery = 0;
        for (int i = 0; i < wiadomosc.length(); i++) {
            char znak = wiadomosc.charAt(i);
            if (Character.isUpperCase(znak)) {
                wielkie++;
                litery++;
            } else if (Character.isLowerCase(znak)) {
                litery++;
            }
        }
        if (litery == 0) return; // brak liter (same cyfry/symbole) - nie ma czego oceniać

        if ((double) wielkie / litery > PROG_CAPS) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "Nie możesz tego napisać - za dużo wielkich liter, wyłącz caps locka!",
                    NamedTextColor.RED));
        }
    }

    private Rank pobierzRange(Player player) {
        RankService rankService = CoreAPI.getRankService();
        return rankService != null ? rankService.getRank(player.getUniqueId()) : Rank.GRACZ;
    }
}
