package elo.mainplugins.announcer.send;

import elo.mainplugins.announcer.claim.ClaimManager;
import elo.mainplugins.announcer.model.AnnGroup;
import elo.mainplugins.announcer.model.AnnMessage;
import elo.mainplugins.announcer.model.Channel;
import elo.mainplugins.announcer.model.EventSpec;
import elo.mainplugins.announcer.model.OnboardingStep;
import elo.mainplugins.announcer.render.TextRenderer;
import elo.mainplugins.announcer.stats.StatsStore;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jedyne miejsce, które faktycznie "wypycha" ogłoszenie na wybrane kanały
 * (CHAT / ACTIONBAR / TITLE / BOSSBAR), dokleja przycisk "odbierz", gra dźwiękiem,
 * liczy statystyki i - jeśli włączone - mirroruje na Discord. Reszta silnika
 * (harmonogram grup, eventy, onboarding) tylko decyduje CO i KIEDY tu przekazać.
 */
public final class Dispatcher {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private volatile TextRenderer renderer;
    private final DiscordWebhook discord;
    private final StatsStore stats;
    private final ClaimManager claims;

    private final Set<BossBar> activeBars = ConcurrentHashMap.newKeySet();

    public Dispatcher(Plugin plugin, TextRenderer renderer, DiscordWebhook discord, StatsStore stats, ClaimManager claims) {
        this.plugin = plugin;
        this.renderer = renderer;
        this.discord = discord;
        this.stats = stats;
        this.claims = claims;
    }

    /** Podmiana renderera po /@reloadannouncer (zmienia się tylko flaga PlaceholderAPI). */
    public void setRenderer(TextRenderer renderer) {
        this.renderer = renderer;
    }

    // ---------------------------------------------------------------- scheduled group message

    public void dispatchGroupMessage(AnnGroup group, AnnMessage msg) {
        if (!msg.enabled) return;

        List<Player> audience = resolveAudience(msg);
        if (audience.size() < msg.minPlayers) return;
        if (audience.isEmpty()) return;

        List<Channel> channels = group.channelsFor(msg);
        String sound = group.soundFor(msg);
        String token = msg.claim != null ? claims.openOffer(group, msg) : null;

        for (Player p : audience) {
            Component base = renderer.render(msg.textLegacy, msg.minimessage, p, Map.of(),
                    channels.contains(Channel.CHAT) ? group.prefixLegacy : "",
                    msg.clickType, msg.clickValue, msg.hover, msg.center);
            deliver(p, base, channels, msg, group.frame, token);
            playSound(p, sound);
        }

        stats.recordShown(msg.id);
        maybeMirror(group.discordFor(msg), msg.textLegacy, msg.minimessage);
    }

    // ---------------------------------------------------------------- event-triggered

    public void dispatchEvent(EventSpec spec, Player subject, Map<String, String> extra) {
        if (!spec.enabled() || spec.textLegacy().isBlank()) return;
        Collection<? extends Player> audience = Bukkit.getOnlinePlayers();
        if (audience.isEmpty()) return;

        List<Channel> channels = spec.channelsOrChat();
        for (Player p : audience) {
            Component base = renderer.render(spec.textLegacy(), false, p, extra);
            deliver(p, base, channels, null, false, null);
            playSound(p, spec.sound());
        }
        stats.recordShown("event:" + spec.key());
        if (spec.discord()) {
            discord.send(renderer.plain(spec.textLegacy(), subject, extra, false));
        }
    }

    // ---------------------------------------------------------------- onboarding (single player)

    public void dispatchOnboarding(Player p, OnboardingStep step) {
        Component base = renderer.render(step.textLegacy(), false, p, Map.of());
        deliver(p, base, step.channelsOrChat(), null, false, null);
    }

    // ---------------------------------------------------------------- ad-hoc /announce

    public void dispatchAdHoc(String legacyText, List<Channel> channels) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Component base = renderer.render(legacyText, false, p, Map.of());
            deliver(p, base, channels, null, false, null);
        }
        stats.recordShown("adhoc");
    }

    public void previewTo(Player viewer, String legacyText) {
        viewer.sendMessage(renderer.render(legacyText, false, viewer, Map.of()));
    }

    // ---------------------------------------------------------------- internals

    private void deliver(Player p, Component base, List<Channel> channels, AnnMessage msg, boolean frame, String claimToken) {
        for (Channel ch : channels) {
            switch (ch) {
                case CHAT -> {
                    if (frame) p.sendMessage(LEGACY.deserialize("&8&m                                        "));
                    Component line = base;
                    if (claimToken != null && msg != null && msg.claim != null) {
                        line = line.append(Component.text("  "))
                                .append(LEGACY.deserialize(msg.claim.buttonLegacy())
                                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                                                "/odbierzogloszenie " + claimToken)));
                    }
                    p.sendMessage(line);
                    if (frame) p.sendMessage(LEGACY.deserialize("&8&m                                        "));
                }
                case ACTIONBAR -> p.sendActionBar(base);
                case TITLE -> {
                    Component[] parts = splitTitle(base);
                    int fin = msg != null ? msg.titleFadeIn : 10;
                    int stay = msg != null ? msg.titleStay : 60;
                    int fout = msg != null ? msg.titleFadeOut : 10;
                    p.showTitle(Title.title(parts[0], parts[1], Title.Times.times(
                            Duration.ofMillis(fin * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fout * 50L))));
                }
                case BOSSBAR -> showBossBar(p, base, msg);
            }
        }
    }

    private Component[] splitTitle(Component base) {
        String plain = TextRenderer.plainOf(base);
        int nl = plain.indexOf('\n');
        if (nl < 0) return new Component[]{base, Component.empty()};
        // rozbij po pierwszym \n - proste, wystarczające dla ogłoszeń
        return new Component[]{
                LEGACY.deserialize(plain.substring(0, nl)),
                LEGACY.deserialize(plain.substring(nl + 1))
        };
    }

    private void showBossBar(Player p, Component text, AnnMessage msg) {
        BossBar.Color color = BossBar.Color.BLUE;
        int seconds = 8;
        if (msg != null) {
            try {
                color = BossBar.Color.valueOf(msg.bossbarColor);
            } catch (IllegalArgumentException ignored) { }
            seconds = msg.bossbarSeconds;
        }
        BossBar bar = BossBar.bossBar(text, 1.0f, color, BossBar.Overlay.PROGRESS);
        p.showBossBar(bar);
        activeBars.add(bar);
        final int total = Math.max(1, seconds);
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            float progress = bar.progress() - (1.0f / (total * 4));
            if (progress <= 0 || !p.isOnline()) {
                p.hideBossBar(bar);
                activeBars.remove(bar);
                task.cancel();
            } else {
                bar.progress(progress);
            }
        }, 5L, 5L);
    }

    private void playSound(Player p, String key) {
        if (key == null || key.isBlank()) return;
        try {
            p.playSound(p.getLocation(), key, 1.0f, 1.0f);
        } catch (Exception ignored) { }
    }

    private void maybeMirror(boolean on, String legacyText, boolean miniMessage) {
        if (on && discord.enabled()) {
            discord.send(renderer.plain(legacyText, null, Map.of(), miniMessage));
        }
    }

    private List<Player> resolveAudience(AnnMessage msg) {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!msg.permission.isEmpty() && !p.hasPermission(msg.permission)) continue;
            if (!msg.worlds.isEmpty() && !msg.worlds.contains(p.getWorld().getName())) continue;
            if (!msg.condition.isEmpty() && !ConditionEval.passes(msg.condition, p, renderer)) continue;
            out.add(p);
        }
        return out;
    }

    public void clearBossBars() {
        for (BossBar bar : activeBars) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        }
        activeBars.clear();
    }
}
