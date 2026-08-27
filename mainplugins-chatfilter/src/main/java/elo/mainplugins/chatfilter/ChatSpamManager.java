package elo.mainplugins.chatfilter;

import elo.mainplugins.chatfilter.config.ChatFilterConfig;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cooldown między kolejnymi wiadomościami na czacie tego samego gracza - blokuje spam.
 * Świadomie BEZ ignoreCancelled - działa niezależnie od tego, czy jakiś inny plugin już
 * anulował wiadomość z innego powodu (np. przyszły filtr przekleństw), więc nie da się
 * obejść cooldownu przez wywołanie akurat takiej blokady równolegle.
 *
 * Nie koliduje z wewnętrznymi "czatowymi promptami" innych pluginów (np. potwierdzenie
 * usunięcia wyspy przez wpisanie "usun" w IslandManager) - te sprawdzają własne stany
 * niezależnie od tego czy event jest już anulowany, więc nawet jeśli akurat trafią na
 * cooldown i to konkretne wystąpienie zostanie skasowane z publicznego czatu, ich logika
 * i tak się wykona normalnie.
 *
 * Permisja bypass (mainplugins.chatfilter.bypass, w plugin.yml) zostaje na stałe - to
 * identyfikator protokołu, nie wartość do strojenia. Sama długość cooldownu i włącz/wyłącz
 * są w chatfilter-config.yml - patrz ChatFilterConfigLoader.
 */
public class ChatSpamManager implements Listener {

    private static final String PERMISJA_BYPASS = "mainplugins.chatfilter.bypass";

    private volatile ChatFilterConfig.AntiSpam cfg;

    // ConcurrentHashMap, nie HashMap - AsyncChatEvent leci na puli wątków czatu (nie
    // gwarantowane, że kolejne wiadomości tego samego gracza trafią na ten sam wątek),
    // więc zwykła HashMap ma tu realne ryzyko niewidoczności zapisu między wątkami -
    // patrz ten sam problem w IslandManager (pendingDeleteConfirmation).
    private final Map<UUID, Long> ostatniaWiadomoscMillis = new ConcurrentHashMap<>();

    public ChatSpamManager(ChatFilterConfig.AntiSpam cfg) {
        this.cfg = cfg;
    }

    public void aktualizujKonfiguracje(ChatFilterConfig.AntiSpam cfg) {
        this.cfg = cfg;
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        ChatFilterConfig.AntiSpam aktualna = cfg;
        if (!aktualna.enabled()) return;

        Player player = event.getPlayer();
        if (player.hasPermission(PERMISJA_BYPASS)) return;

        long teraz = System.currentTimeMillis();
        Long ostatnia = ostatniaWiadomoscMillis.get(player.getUniqueId());

        // UWAGA: znacznik czasu aktualizujemy TYLKO gdy wiadomość faktycznie przechodzi.
        // Gdyby aktualizować go przy każdej próbie (także zablokowanej), gracz mashujący
        // enter szybciej niż co 5s sam sobie ciągle odsuwałby licznik w przyszłość i nigdy
        // by się nie doczekał - odliczanie musi liczyć się od OSTATNIEJ UDANEJ wiadomości.
        if (ostatnia != null && teraz - ostatnia < aktualna.cooldownMillis()) {
            event.setCancelled(true);
            double pozostalo = (aktualna.cooldownMillis() - (teraz - ostatnia)) / 1000.0;
            player.sendMessage(Component.text(
                    String.format(Locale.US, "Poczekaj jeszcze %.1fs, zanim znowu napiszesz na czacie.", pozostalo),
                    NamedTextColor.RED));
            return;
        }

        ostatniaWiadomoscMillis.put(player.getUniqueId(), teraz);
    }
}
