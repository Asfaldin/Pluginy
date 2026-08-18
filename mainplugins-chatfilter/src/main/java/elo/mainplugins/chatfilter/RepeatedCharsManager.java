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
 * Blokuje wiadomości z tym samym znakiem powtórzonym 5+ razy POD RZĄD (np. "aaaaaaaaa",
 * "!!!!!!!!!"). To co innego niż RepeatMessageManager - tamten łapie powtórki CAŁEJ
 * wiadomości MIĘDZY dwoma wiadomościami, ten łapie spam znakiem WEWNĄTRZ jednej.
 *
 * Próg 5 celowo pozwala na normalne, casualowe "noooo"/"hahaha" (te mają max 3-4
 * powtórzenia tego samego znaku pod rząd), łapiąc tylko realny spam.
 */
public class RepeatedCharsManager implements Listener {

    private static final Pattern POWTORZENIA = Pattern.compile("(.)\\1{4,}");

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (pobierzRange(event.getPlayer()) == Rank.ADMIN) return;

        if (POWTORZENIA.matcher(event.getMessage()).find()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text(
                    "Nie możesz tego napisać - zbyt wiele powtarzających się znaków pod rząd!",
                    NamedTextColor.RED));
        }
    }

    private Rank pobierzRange(Player player) {
        RankService rankService = CoreAPI.getRankService();
        return rankService != null ? rankService.getRank(player.getUniqueId()) : Rank.GRACZ;
    }
}
