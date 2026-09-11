package elo.mainplugins.announcer;

import elo.mainplugins.announcer.claim.ClaimManager;
import elo.mainplugins.announcer.config.AnnouncerConfig;
import elo.mainplugins.announcer.events.EventBridge;
import elo.mainplugins.announcer.model.AnnGroup;
import elo.mainplugins.announcer.onboarding.OnboardingManager;
import elo.mainplugins.announcer.render.TextRenderer;
import elo.mainplugins.announcer.schedule.GroupRunner;
import elo.mainplugins.announcer.send.Dispatcher;
import elo.mainplugins.announcer.send.DiscordWebhook;
import elo.mainplugins.announcer.stats.StatsStore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spina cały moduł: wczytuje ogloszenia.yml, buduje warstwy (render / wysyłka /
 * Discord / claim / statystyki / eventy / onboarding) i uruchamia po jednym
 * niezależnym harmonogramie na grupę. {@link #przeladuj()} robi to samo od nowa
 * bez restartu serwera (/@reloadannouncer).
 */
public final class AnnouncerManager {

    private final Plugin plugin;
    private final File plik;

    private final StatsStore stats;
    private final ClaimManager claims;
    private final DiscordWebhook discord;
    private final Dispatcher dispatcher;
    private final OnboardingManager onboarding;
    private final EventBridge eventBridge;

    private AnnouncerConfig config;
    private final Map<String, GroupRunner> runners = new LinkedHashMap<>();

    public AnnouncerManager(Plugin plugin) {
        this.plugin = plugin;
        this.plik = new File(plugin.getDataFolder(), "ogloszenia.yml");
        this.config = AnnouncerConfig.load(plik, plugin.getLogger());

        this.stats = new StatsStore(plugin);
        this.claims = new ClaimManager(plugin, stats);
        this.discord = new DiscordWebhook(plugin.getLogger(),
                config.discordWebhookUrl, config.discordUsername, config.discordAvatarUrl);
        this.dispatcher = new Dispatcher(plugin, new TextRenderer(config.placeholdersEnabled), discord, stats, claims);
        this.onboarding = new OnboardingManager(plugin, dispatcher, config);
        this.eventBridge = new EventBridge(dispatcher, onboarding, config);
        Bukkit.getPluginManager().registerEvents(eventBridge, plugin);

        stats.start();
        startRunners();
    }

    private void startRunners() {
        for (AnnGroup g : config.groups) {
            GroupRunner runner = new GroupRunner(plugin, g, dispatcher, config);
            runners.put(g.name, runner);
            runner.start();
        }
    }

    private void stopRunners() {
        runners.values().forEach(GroupRunner::stop);
        runners.clear();
    }

    public void przeladuj() {
        stopRunners();
        claims.clear();
        dispatcher.clearBossBars();

        this.config = AnnouncerConfig.load(plik, plugin.getLogger());
        dispatcher.setRenderer(new TextRenderer(config.placeholdersEnabled));
        discord.update(config.discordWebhookUrl, config.discordUsername, config.discordAvatarUrl);
        eventBridge.updateConfig(config);
        onboarding.updateConfig(config);
        startRunners();
    }

    public void zatrzymaj() {
        stopRunners();
        claims.clear();
        dispatcher.clearBossBars();
        stats.save();
        stats.stop();
    }

    // --- pod komendy ---

    public ClaimManager claims() {
        return claims;
    }

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public boolean fireGroupNow(String name) {
        GroupRunner runner = runners.get(name);
        return runner != null && runner.forceTick();
    }

    public List<String> groupNames() {
        return new ArrayList<>(runners.keySet());
    }
}
