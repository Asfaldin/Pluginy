package elo.mainplugins.advancements.notify;

import elo.mainplugins.advancements.config.AchievementsConfig;
import elo.mainplugins.advancements.model.AchievementDef;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;

/**
 * Sygnalizuje zdobycie WŁASNEGO osiągnięcia. Vanilla advancementy mają natywny
 * "toast" Minecrafta - ich tu nie ruszamy. Wszystko w 100% stabilnym Bukkit API
 * (tytuł + dźwięk + cząstki), świadomie bez NMS/pakietów - patrz porzucona próba
 * fejkowania lasera przez ProtocolLib w mainplugins-fishing (rozłączała graczy).
 */
public final class AchievementNotifier {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private AchievementsConfig.Powiadomienia cfg;

    public AchievementNotifier(Plugin plugin, AchievementsConfig.Powiadomienia cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void aktualizujKonfiguracje(AchievementsConfig.Powiadomienia cfg) {
        this.cfg = cfg;
    }

    /**
     * @param maNagrodeDoOdebrania true, gdy osiągnięcie ma nagrody, a gracz ich
     *                             jeszcze nie odebrał (dokleja podpowiedź o /osiagniecia)
     * @param tylkoEfekt            true, gdy natywny toast datapacka już poleci sam -
     *                             pomijamy własny tytuł i dźwięk, zostaje chat + widowisko
     */
    public void powiadom(Player player, AchievementDef def, boolean maNagrodeDoOdebrania, boolean tylkoEfekt) {
        String nazwaPlain = PlainTextComponentSerializer.plainText().serialize(def.nazwa());
        Component tytul = SER.deserialize(cfg.tytulLegacy().replace("%nazwa%", nazwaPlain));

        if (!tylkoEfekt) {
            Component podtytul = SER.deserialize(cfg.podtytulLegacy().replace("%nazwa%", nazwaPlain));
            player.showTitle(Title.title(tytul, podtytul,
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
            zagrajDzwiek(player);
        }

        player.sendMessage(Component.text("✦ ").append(tytul).append(Component.text(" ")).append(def.nazwa()));
        if (maNagrodeDoOdebrania) {
            player.sendMessage(SER.deserialize("&7Odbierz nagrodę w &f/osiagniecia&7."));
        }

        if (def.spektakularne() && cfg.efektRzadkich()) {
            widowisko(player);
        }
    }

    private void zagrajDzwiek(Player player) {
        String key = cfg.dzwiekKey();
        if (key == null || key.isBlank()) return;
        try {
            // Stabilny od zawsze overload - przyjmuje surowy klucz, bez enuma/rejestru Sound.
            player.playSound(player.getLocation(), key, 1.0f, 1.0f);
        } catch (Exception e) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    /** Świecący słup + korkociąg cząstek totemu - lżejszy wariant efektu z fishing. */
    private void widowisko(Player player) {
        final Location base = player.getLocation();
        new BukkitRunnable() {
            double y = 0;
            int ticks = 0;

            @Override
            public void run() {
                if (ticks++ >= 40 || !player.isOnline()) {
                    cancel();
                    return;
                }
                double angle = y * Math.PI;
                for (int i = 0; i < 2; i++) {
                    double a = angle + i * Math.PI;
                    double x = Math.cos(a) * 0.6;
                    double z = Math.sin(a) * 0.6;
                    Location p = base.clone().add(x, y, z);
                    base.getWorld().spawnParticle(Particle.END_ROD, p, 1, 0, 0, 0, 0);
                    base.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p, 1, 0.05, 0.05, 0.05, 0.02);
                }
                y += 0.1;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }
}
