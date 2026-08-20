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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blokuje wysłanie DOKŁADNIE tej samej wiadomości dwa razy pod rząd (ten sam gracz) -
 * wystarczy dowolna zmiana (choćby jeden dodatkowy znak, np. "cwel" -> "cwel!"), żeby
 * druga wiadomość przeszła normalnie. Liczy się TYLKO bezpośrednie sąsiedztwo - jeśli
 * między dwoma identycznymi wiadomościami pojawi się cokolwiek inne (np. "cwel", "jd",
 * "cwel"), blokada w ogóle się nie uruchamia.
 *
 * Porównanie jest dosłowne (bez trim/lowercase) - "choćby jakikolwiek znak" oznacza też
 * np. sam spacja na końcu, więc nie normalizujemy niczego, żeby tego nie zepsuć.
 *
 * ConcurrentHashMap z tego samego powodu co w MuteManager - AsyncChatEvent leci
 * na osobnym wątku.
 */
public class RepeatMessageManager implements Listener {

    private final Map<UUID, String> ostatniaWiadomosc = new ConcurrentHashMap<>();

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (pobierzRange(player) == Rank.ADMIN) return;

        String wiadomosc = PlainTextComponentSerializer.plainText().serialize(event.message());
        // put() PRZED sprawdzeniem jest celowo bezwarunkowe: gdy wiadomość zostanie
        // zablokowana jako duplikat, to co właśnie zapisaliśmy i tak jest identyczne
        // z tym co już tam było (bo dlatego wykryliśmy duplikat) - nie ma więc ryzyka
        // przypadkowego "zresetowania" stanu, tak jak przy cooldownie w ChatSpamManager.
        String poprzednia = ostatniaWiadomosc.put(player.getUniqueId(), wiadomosc);

        if (wiadomosc.equals(poprzednia)) {
            event.setCancelled(true);
            player.sendMessage(Component.text(
                    "Nie możesz napisać dwa razy pod rząd dokładnie tej samej wiadomości!",
                    NamedTextColor.RED));
        }
    }

    private Rank pobierzRange(Player player) {
        RankService rankService = CoreAPI.getRankService();
        return rankService != null ? rankService.getRank(player.getUniqueId()) : Rank.GRACZ;
    }
}
