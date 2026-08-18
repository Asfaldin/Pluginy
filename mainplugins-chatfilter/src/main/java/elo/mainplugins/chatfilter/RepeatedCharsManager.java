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
    public void onChat(AsyncChatEvent event) {
        if (pobierzRange(event.getPlayer()) == Rank.ADMIN) return;

        String wiadomosc = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (POWTORZENIA.matcher(wiadomosc).find()) {
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
