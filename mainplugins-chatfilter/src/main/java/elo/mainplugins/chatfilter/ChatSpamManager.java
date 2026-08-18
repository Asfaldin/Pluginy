package elo.mainplugins.chatfilter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
 */
public class ChatSpamManager implements Listener {

    private static final long COOLDOWN_MILLIS = 5000L;
    private static final String PERMISJA_BYPASS = "mainplugins.chatfilter.bypass";

    private final Map<UUID, Long> ostatniaWiadomoscMillis = new HashMap<>();

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(PERMISJA_BYPASS)) return;

        long teraz = System.currentTimeMillis();
        Long ostatnia = ostatniaWiadomoscMillis.get(player.getUniqueId());

        // UWAGA: znacznik czasu aktualizujemy TYLKO gdy wiadomość faktycznie przechodzi.
        // Gdyby aktualizować go przy każdej próbie (także zablokowanej), gracz mashujący
        // enter szybciej niż co 5s sam sobie ciągle odsuwałby licznik w przyszłość i nigdy
        // by się nie doczekał - odliczanie musi liczyć się od OSTATNIEJ UDANEJ wiadomości.
        if (ostatnia != null && teraz - ostatnia < COOLDOWN_MILLIS) {
            event.setCancelled(true);
            double pozostalo = (COOLDOWN_MILLIS - (teraz - ostatnia)) / 1000.0;
            player.sendMessage(Component.text(
                    String.format(Locale.US, "Poczekaj jeszcze %.1fs, zanim znowu napiszesz na czacie.", pozostalo),
                    NamedTextColor.RED));
            return;
        }

        ostatniaWiadomoscMillis.put(player.getUniqueId(), teraz);
    }
}
