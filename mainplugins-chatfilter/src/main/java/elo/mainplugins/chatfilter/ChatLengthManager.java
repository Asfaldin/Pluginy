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

/**
 * Limit długości wiadomości na czacie - połowa wanilijskiego limitu klienta (256 znaków,
 * stąd 128 tutaj) dla Gracza i VIP-a, żeby nie było kilometrowych wiadomości. Admin bez
 * dodatkowego ograniczenia - i tak nie przebije wanilijskiego twardego limitu klienta,
 * więc "ile chce" w praktyce znaczy "wanilijskie 256".
 *
 * ignoreCancelled = true: jeśli wiadomość już została zablokowana z innego powodu (np.
 * cooldown z ChatSpamManager), nie ma sensu jeszcze dokładać drugiego komunikatu o
 * długości - gracz i tak nic nie wysłał.
 *
 * Bez RankService (mainplugins-ranks niewgrany/wyłączony) każdy jest traktowany jak
 * Gracz - patrz RankService#getRank, ten sam wzorzec co reszta ekosystemu.
 */
public class ChatLengthManager implements Listener {

    private static final int LIMIT_GRACZ_VIP = 128;

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (pobierzRange(event.getPlayer()) == Rank.ADMIN) return;

        String wiadomosc = event.getMessage();
        if (wiadomosc.length() <= LIMIT_GRACZ_VIP) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text(
                "Wiadomość jest za długa! Maksymalnie " + LIMIT_GRACZ_VIP + " znaków (masz " + wiadomosc.length() + ").",
                NamedTextColor.RED));
    }

    private Rank pobierzRange(Player player) {
        RankService rankService = CoreAPI.getRankService();
        return rankService != null ? rankService.getRank(player.getUniqueId()) : Rank.GRACZ;
    }
}
