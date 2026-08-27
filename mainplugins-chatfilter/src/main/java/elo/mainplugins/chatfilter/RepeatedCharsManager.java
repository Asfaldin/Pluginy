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
 * Blokuje wiadomości z tym samym znakiem powtórzonym min-powtorzen+ razy POD RZĄD (np.
 * "aaaaaaaaa", "!!!!!!!!!"). To co innego niż RepeatMessageManager - tamten łapie
 * powtórki CAŁEJ wiadomości MIĘDZY dwoma wiadomościami, ten łapie spam znakiem WEWNĄTRZ
 * jednej.
 *
 * Domyślny próg 5 celowo pozwala na normalne, casualowe "noooo"/"hahaha" (te mają max
 * 3-4 powtórzenia tego samego znaku pod rząd), łapiąc tylko realny spam.
 *
 * Konfiguracja (włącz/wyłącz, min-powtorzen, wyjęte rangi) w chatfilter-config.yml -
 * patrz ChatFilterConfigLoader. Wzorzec regexu jest przebudowywany przy każdym
 * /@reloadchatfilter, nie tylko przy starcie serwera.
 */
public class RepeatedCharsManager implements Listener {

    private volatile ChatFilterConfig.PowtarzajaceZnaki cfg;

    public RepeatedCharsManager(ChatFilterConfig.PowtarzajaceZnaki cfg) {
        this.cfg = cfg;
    }

    public void aktualizujKonfiguracje(ChatFilterConfig.PowtarzajaceZnaki cfg) {
        this.cfg = cfg;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ChatFilterConfig.PowtarzajaceZnaki aktualna = cfg;
        if (!aktualna.enabled()) return;
        if (aktualna.exemptRangi().contains(pobierzRange(event.getPlayer()))) return;

        String wiadomosc = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (aktualna.wzorzec().matcher(wiadomosc).find()) {
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
