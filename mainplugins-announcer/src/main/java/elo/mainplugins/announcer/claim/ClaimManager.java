package elo.mainplugins.announcer.claim;

import elo.mainplugins.announcer.model.AnnGroup;
import elo.mainplugins.announcer.model.AnnMessage;
import elo.mainplugins.announcer.model.ClaimSpec;
import elo.mainplugins.announcer.stats.StatsStore;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ogłoszenia typu "kliknij aby odebrać". Dispatcher woła {@link #openOffer} przy
 * wysyłce (dostaje token do wklejenia w klikalny przycisk), a komenda
 * {@code /odbierzogloszenie <token>} trafia do {@link #handleClaim}. Pilnuje:
 * limitu pierwszych N graczy, okna czasowego i tego, że każdy odbiera raz.
 * Nagroda = komendy z konsoli z podmianą {@code %player%}.
 */
public final class ClaimManager {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final StatsStore stats;

    private final Map<String, Offer> offers = new ConcurrentHashMap<>();
    /** Ostatni token dla danego id wiadomości - nowe ogłoszenie unieważnia stare. */
    private final Map<String, String> latestByMessage = new ConcurrentHashMap<>();

    public ClaimManager(Plugin plugin, StatsStore stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    private static final class Offer {
        final String messageId;
        final ClaimSpec spec;
        final Set<UUID> claimedBy = ConcurrentHashMap.newKeySet();
        final AtomicInteger count = new AtomicInteger();
        volatile boolean open = true;

        Offer(String messageId, ClaimSpec spec) {
            this.messageId = messageId;
            this.spec = spec;
        }
    }

    /** Rejestruje nową ofertę dla wiadomości; zamyka poprzednią o tym samym id. Zwraca token. */
    public String openOffer(AnnGroup group, AnnMessage msg) {
        ClaimSpec spec = msg.claim;
        String prev = latestByMessage.get(msg.id);
        if (prev != null) {
            Offer old = offers.remove(prev);
            if (old != null) old.open = false;
        }
        String token = Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt())
                + Long.toHexString(System.nanoTime());
        Offer offer = new Offer(msg.id, spec);
        offers.put(token, offer);
        latestByMessage.put(msg.id, token);

        int window = spec.windowSeconds();
        if (window > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                offer.open = false;
                offers.remove(token, offer);
            }, 20L * window);
        }
        return token;
    }

    public void handleClaim(Player p, String token) {
        Offer offer = offers.get(token);
        ClaimSpec spec = offer != null ? offer.spec : null;
        if (offer == null || !offer.open) {
            msg(p, spec, spec == null ? "&cTa oferta juz wygasla." : spec.fullLegacy());
            return;
        }
        if (offer.claimedBy.contains(p.getUniqueId())) {
            msg(p, spec, spec.alreadyLegacy());
            return;
        }
        int limit = spec.limit();
        if (limit > 0 && offer.count.get() >= limit) {
            offer.open = false;
            msg(p, spec, spec.fullLegacy());
            return;
        }
        // rezerwacja miejsca
        int mine = offer.count.incrementAndGet();
        if (limit > 0 && mine > limit) {
            offer.count.decrementAndGet();
            offer.open = false;
            msg(p, spec, spec.fullLegacy());
            return;
        }
        offer.claimedBy.add(p.getUniqueId());
        if (limit > 0 && mine >= limit) offer.open = false;

        for (String cmd : spec.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        stats.recordClaimed(offer.messageId);
        msg(p, spec, spec.claimedLegacy());
    }

    private void msg(Player p, ClaimSpec spec, String legacy) {
        if (legacy == null || legacy.isBlank()) return;
        p.sendMessage(LEGACY.deserialize(legacy));
    }

    public void clear() {
        offers.clear();
        latestByMessage.clear();
    }
}
