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
 * Limit długości wiadomości na czacie (konfigurowalny, domyślnie 128 - połowa
 * wanilijskiego limitu klienta 256) dla ról objętych filtrem. Wyjęte rangi (domyślnie
 * Admin) i tak nie przebiją wanilijskiego twardego limitu klienta, więc "bez ograniczenia
 * z tej strony" w praktyce znaczy "wanilijskie 256".
 *
 * ignoreCancelled = true: jeśli wiadomość już została zablokowana z innego powodu (np.
 * cooldown z ChatSpamManager), nie ma sensu jeszcze dokładać drugiego komunikatu o
 * długości - gracz i tak nic nie wysłał.
 *
 * Bez RankService (mainplugins-ranks niewgrany/wyłączony) każdy jest traktowany jak
 * Gracz - patrz RankService#getRank, ten sam wzorzec co reszta ekosystemu.
 *
 * Konfiguracja (włącz/wyłącz, limit-znakow, wyjęte rangi) w chatfilter-config.yml -
 * patrz ChatFilterConfigLoader.
 */
public class ChatLengthManager implements Listener {

    private volatile ChatFilterConfig.DlugoscWiadomosci cfg;

    public ChatLengthManager(ChatFilterConfig.DlugoscWiadomosci cfg) {
        this.cfg = cfg;
    }

    public void aktualizujKonfiguracje(ChatFilterConfig.DlugoscWiadomosci cfg) {
        this.cfg = cfg;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ChatFilterConfig.DlugoscWiadomosci aktualna = cfg;
        if (!aktualna.enabled()) return;
        if (aktualna.exemptRangi().contains(pobierzRange(event.getPlayer()))) return;

        String wiadomosc = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (wiadomosc.length() <= aktualna.limitZnakow()) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text(
                "Wiadomość jest za długa! Maksymalnie " + aktualna.limitZnakow() + " znaków (masz " + wiadomosc.length() + ").",
                NamedTextColor.RED));
    }

    private Rank pobierzRange(Player player) {
        RankService rankService = CoreAPI.getRankService();
        return rankService != null ? rankService.getRank(player.getUniqueId()) : Rank.GRACZ;
    }
}
