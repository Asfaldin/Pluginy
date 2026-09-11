package elo.mainplugins.announcer.onboarding;

import elo.mainplugins.announcer.config.AnnouncerConfig;
import elo.mainplugins.announcer.model.OnboardingStep;
import elo.mainplugins.announcer.send.Dispatcher;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Sekwencja powitalna dla gracza łączącego się PIERWSZY raz w życiu
 * ({@code !player.hasPlayedBefore()}). Każdy krok to zaplanowane zadanie
 * "delay-seconds" po wejściu - wysyłane tylko jeśli gracz nadal jest online.
 */
public final class OnboardingManager {

    private final Plugin plugin;
    private final Dispatcher dispatcher;
    private volatile AnnouncerConfig config;

    public OnboardingManager(Plugin plugin, Dispatcher dispatcher, AnnouncerConfig config) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
        this.config = config;
    }

    public void updateConfig(AnnouncerConfig config) {
        this.config = config;
    }

    public void onFirstJoin(Player p) {
        AnnouncerConfig cfg = config;
        if (!cfg.onboardingEnabled || cfg.onboarding.isEmpty()) return;
        for (OnboardingStep step : cfg.onboarding) {
            long delay = Math.max(1L, step.delaySeconds() * 20L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) dispatcher.dispatchOnboarding(p, step);
            }, delay);
        }
    }
}
