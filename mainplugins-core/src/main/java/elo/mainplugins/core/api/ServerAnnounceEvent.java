package elo.mainplugins.core.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Ogólny "coś ważnego się stało" - most między dowolnym modułem a
 * mainplugins-announcer, który jako jedyny go słucha (ale event jest publiczny
 * jak każdy customowy event Bukkita). Moduł-źródło NIE musi znać announcera ani
 * mieć go w zależnościach - wystarczy że oba mają mainplugins-core.
 *
 * <p>Wołający robi po prostu:
 * <pre>Bukkit.getPluginManager().callEvent(
 *     new ServerAnnounceEvent("rare-fish", player, Map.of("fish", nazwa)));</pre>
 *
 * a announcer decyduje w ogloszenia.yml (sekcja {@code events:}), czy i jak to
 * pokazać - z podmianą {@code %klucz%} na wartości z {@link #getPlaceholders()}
 * oraz {@code %player%} na nick gracza. Jeśli announcera nie ma na serwerze,
 * event po prostu przelatuje bez efektu.
 */
public class ServerAnnounceEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String key;
    private final Player player;
    private final Map<String, String> placeholders;

    /**
     * @param key          identyfikator wpisu w {@code events:} (np. "dungeon-boss", "rare-fish")
     * @param player       gracz, którego dotyczy zdarzenie - może być null dla zdarzeń serwerowych
     * @param placeholders dodatkowe podstawienia {@code %klucz% -> wartosc} (może być null/puste)
     */
    public ServerAnnounceEvent(String key, Player player, Map<String, String> placeholders) {
        this.key = key;
        this.player = player;
        this.placeholders = placeholders == null ? Collections.emptyMap() : new HashMap<>(placeholders);
    }

    public String getKey() { return key; }

    /** Może być null (zdarzenie nie związane z konkretnym graczem). */
    public Player getPlayer() { return player; }

    /** Niemodyfikowalna mapa dodatkowych podstawień do tekstu ogłoszenia. */
    public Map<String, String> getPlaceholders() { return Collections.unmodifiableMap(placeholders); }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
