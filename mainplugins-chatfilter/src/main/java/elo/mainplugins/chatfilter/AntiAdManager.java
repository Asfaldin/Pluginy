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
 * Blokuje reklamę na czacie: linki (http/https/www), domeny z konfigurowalnej listy
 * końcówek (np. "twojastrona.pl") i adresy IP (np. "17.123.521.21" - do innego serwera).
 * Celowo lista końcówek zamiast "cokolwiek.cokolwiek" - łapie zdecydowaną większość
 * reklam przy praktycznie zerowym ryzyku złapania normalnego zdania z kropką (np.
 * skrótu). Bez obchodzenia trików typu "strona kropka pl" - jeśli to się realnie
 * pojawi, dopiszemy.
 *
 * IP dopasowywane po SAMYM KSZTAŁCIE (cztery grupy cyfr oddzielone kropkami), bez
 * walidacji zakresu 0-255 - łapie też "zepsute"/spreparowane adresy używane do obejścia,
 * a false-positive na czterech kolejnych liczbach oddzielonych kropkami w zwykłej
 * rozmowie jest praktycznie zerowy.
 *
 * Konfiguracja (włącz/wyłącz, lista końcówek domen, wyjęte rangi) w chatfilter-config.yml
 * - patrz ChatFilterConfigLoader. Wzorzec regexu jest przebudowywany przy każdym
 * /@reloadchatfilter, nie tylko przy starcie serwera.
 */
public class AntiAdManager implements Listener {

    private volatile ChatFilterConfig.AntyReklama cfg;

    public AntiAdManager(ChatFilterConfig.AntyReklama cfg) {
        this.cfg = cfg;
    }

    public void aktualizujKonfiguracje(ChatFilterConfig.AntyReklama cfg) {
        this.cfg = cfg;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ChatFilterConfig.AntyReklama aktualna = cfg;
        if (!aktualna.enabled()) return;
        if (aktualna.exemptRangi().contains(pobierzRange(event.getPlayer()))) return;

        String wiadomosc = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (aktualna.wzorzec().matcher(wiadomosc).find()) {
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
