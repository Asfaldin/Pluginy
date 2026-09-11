package elo.mainplugins.announcer.schedule;

import elo.mainplugins.announcer.config.AnnouncerConfig;
import elo.mainplugins.announcer.model.AnnGroup;
import elo.mainplugins.announcer.model.AnnMessage;
import elo.mainplugins.announcer.send.Dispatcher;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Niezależny harmonogram JEDNEJ grupy ogłoszeń (osobny BukkitTask). Co interwał
 * wybiera wiadomość zgodnie z {@code order} (SEQUENTIAL / RANDOM / WEIGHTED),
 * pilnuje {@code no-repeat}, respektuje ciche godziny i okno {@code schedule:}.
 */
public final class GroupRunner {

    private final Plugin plugin;
    private final AnnGroup group;
    private final Dispatcher dispatcher;
    private final AnnouncerConfig config;

    private BukkitTask task;
    private int seqIndex = 0;
    private String lastId = null;

    public GroupRunner(Plugin plugin, AnnGroup group, Dispatcher dispatcher, AnnouncerConfig config) {
        this.plugin = plugin;
        this.group = group;
        this.dispatcher = dispatcher;
        this.config = config;
    }

    public void start() {
        stop();
        long ticks = Math.max(20L, group.effectiveIntervalSeconds(config.intervalSeconds) * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    private void tick() {
        if (config.isQuietNow()) return;
        if (!group.schedule.isActiveNow()) return;
        emitOne();
    }

    /** Jednorazowa wysyłka teraz, z pominięciem cichych godzin i okna schedule (pod /announce as). */
    public boolean forceTick() {
        return emitOne();
    }

    private boolean emitOne() {
        List<AnnMessage> pool = new ArrayList<>();
        for (AnnMessage m : group.messages) if (m.enabled) pool.add(m);
        if (pool.isEmpty()) return false;

        AnnMessage chosen = switch (group.ordering) {
            case SEQUENTIAL -> sequential(pool);
            case RANDOM -> random(pool);
            case WEIGHTED -> weighted(pool);
        };
        if (chosen == null) return false;

        dispatcher.dispatchGroupMessage(group, chosen);
        lastId = chosen.id;
        return true;
    }

    private AnnMessage sequential(List<AnnMessage> pool) {
        if (seqIndex >= pool.size()) seqIndex = 0;
        AnnMessage m = pool.get(seqIndex);
        seqIndex++;
        return m;
    }

    private AnnMessage random(List<AnnMessage> pool) {
        if (pool.size() == 1) return pool.get(0);
        for (int attempt = 0; attempt < 5; attempt++) {
            AnnMessage m = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
            if (!group.noRepeat || !m.id.equals(lastId)) return m;
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private AnnMessage weighted(List<AnnMessage> pool) {
        if (pool.size() == 1) return pool.get(0);
        for (int attempt = 0; attempt < 5; attempt++) {
            int total = 0;
            for (AnnMessage m : pool) total += Math.max(1, m.weight);
            int r = ThreadLocalRandom.current().nextInt(total);
            AnnMessage picked = pool.get(pool.size() - 1);
            for (AnnMessage m : pool) {
                r -= Math.max(1, m.weight);
                if (r < 0) { picked = m; break; }
            }
            if (!group.noRepeat || !picked.id.equals(lastId)) return picked;
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }
}
