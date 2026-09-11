package elo.mainplugins.announcer.events;

import elo.mainplugins.announcer.config.AnnouncerConfig;
import elo.mainplugins.announcer.model.EventSpec;
import elo.mainplugins.announcer.onboarding.OnboardingManager;
import elo.mainplugins.announcer.send.Dispatcher;
import elo.mainplugins.core.api.ServerAnnounceEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Zamienia zdarzenia w broadcasty wg sekcji {@code events:} z ogloszenia.yml:
 * <ul>
 *   <li>{@link ServerAnnounceEvent} z dowolnego modułu (klucz = wpis w events),</li>
 *   <li>wbudowane: "first-join", "vanilla-advancement", "death".</li>
 * </ul>
 * Trzyma też cooldown per klucz (żeby seria tych samych zdarzeń nie zalała czatu)
 * i odpala sekwencję powitalną przez {@link OnboardingManager}.
 */
public final class EventBridge implements Listener {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Dispatcher dispatcher;
    private final OnboardingManager onboarding;
    private volatile AnnouncerConfig config;
    private final Map<String, Long> lastFired = new HashMap<>();

    public EventBridge(Dispatcher dispatcher, OnboardingManager onboarding, AnnouncerConfig config) {
        this.dispatcher = dispatcher;
        this.onboarding = onboarding;
        this.config = config;
    }

    public void updateConfig(AnnouncerConfig config) {
        this.config = config;
        this.lastFired.clear();
    }

    // ---- most z innych modułów ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerAnnounce(ServerAnnounceEvent e) {
        EventSpec spec = specOf(e.getKey());
        if (spec == null) return;
        Map<String, String> extra = new HashMap<>(e.getPlaceholders());
        fire(spec, e.getPlayer(), extra);
    }

    // ---- wbudowane ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (e.getPlayer().hasPlayedBefore()) return;
        onboarding.onFirstJoin(e.getPlayer());
        EventSpec spec = specOf("first-join");
        if (spec != null) fire(spec, e.getPlayer(), Map.of());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        EventSpec spec = specOf("vanilla-advancement");
        if (spec == null) return;
        String key = e.getAdvancement().getKey().getKey();
        if (key.startsWith("recipes/") || key.equals("root") || key.endsWith("/root")) return;
        io.papermc.paper.advancement.AdvancementDisplay display = e.getAdvancement().getDisplay();
        if (display == null) return;
        String title = PLAIN.serialize(display.title());
        fire(spec, e.getPlayer(), Map.of("advancement", title));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        EventSpec spec = specOf("death");
        if (spec == null) return;
        fire(spec, e.getEntity(), Map.of());
    }

    // ---- wspólne ----

    private EventSpec specOf(String key) {
        EventSpec spec = config.events.get(key);
        return spec != null && spec.enabled() ? spec : null;
    }

    private void fire(EventSpec spec, Player subject, Map<String, String> extra) {
        long now = System.currentTimeMillis();
        if (spec.cooldownSeconds() > 0) {
            Long last = lastFired.get(spec.key());
            if (last != null && now - last < spec.cooldownSeconds() * 1000L) return;
        }
        lastFired.put(spec.key(), now);
        dispatcher.dispatchEvent(spec, subject, extra);
    }
}
