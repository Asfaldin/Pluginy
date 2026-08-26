package elo.mainplugins.chatfilter;

import elo.mainplugins.chatfilter.config.ChatFilterConfig;
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
 * Blokuje wiadomości pisane w większości WIELKIMI LITERAMI (próg konfigurowalny). Krótkie
 * wiadomości (poniżej konfigurowalnej minimalnej długości) są pominięte, żeby nie łapać
 * normalnych "OK", "GG", "LOL" itp. - to naturalny sposób pisania krótkich zwrotów, nie
 * "krzyczenie".
 *
 * Liczymy TYLKO litery (Character.isUpperCase/isLowerCase - działa też na polskie znaki
 * jak Ą/Ł/Ż) - cyfry, spacje i symbole nie wliczają się do proporcji w żadną stronę.
 *
 * Konfiguracja (włącz/wyłącz, min-dlugosc, prog-procent, wyjęte rangi) w
 * chatfilter-config.yml - patrz ChatFilterConfigLoader.
 */
public class CapsLockManager implements Listener {

    private volatile ChatFilterConfig.AntyCaps cfg;

    public CapsLockManager(ChatFilterConfig.AntyCaps cfg) {
        this.cfg = cfg;
    }

    public void aktualizujKonfiguracje(ChatFilterConfig.AntyCaps cfg) {
        this.cfg = cfg;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ChatFilterConfig.AntyCaps aktualna = cfg;
        if (!aktualna.enabled()) return;
        if (aktualna.exemptRangi().contains(pobierzRange(event.getPlayer()))) return;

        String wiadomosc = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (wiadomosc.length() < aktualna.minDlugosc()) return;

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

        if ((double) wielkie / litery > aktualna.progUlamek()) {
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
