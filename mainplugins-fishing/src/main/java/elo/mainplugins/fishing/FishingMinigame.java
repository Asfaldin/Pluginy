package elo.mainplugins.fishing;

import elo.mainplugins.fishing.config.FishingConfig;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Minigra łowienia w stylu Stardew Valley: rybka błądzi losowo po pasku 0..1, gracz
 * PPM-em "podbija" swój suwak w górę (rytmiczne klikanie, nie przytrzymanie - patrz
 * FishingManager.onInteract), grawitacja ściąga go w dół. Nakładanie się suwaka na
 * rybkę napełnia miernik połowu (BossBar.progress), brak nakładania go opróżnia.
 * Trudność (szerokość suwaka, prędkość rybki, tempo napełniania/opróżniania) zależy
 * od rzadkości gatunku wylosowanego jeszcze przed startem minigry.
 */
final class FishingMinigame {

    private final int szerokoscPaska;
    private final double grawitacja;
    private final double impulsKlikniecia;
    private final long okresTickow;
    private final double dt;
    private final long maksymalnyCzasTickow;

    private final Player player;
    private final Runnable naSukces;
    private final Runnable naPorazke;
    private final BossBar pasek;
    private final BukkitTask task;

    private final double polowaSzerokosciSuwaka;
    private final double predkoscRyby;
    private final double tempoNapelniania;
    private final double tempoOprozniania;

    private double pozycjaRyby = ThreadLocalRandom.current().nextDouble(0.3, 0.7);
    private double celRyby = pozycjaRyby;
    private double pozycjaSuwaka = 0.5;
    private double predkoscSuwaka = 0.0;
    private double postep = 0.45;
    private long tickiOdStartu = 0;

    FishingMinigame(Plugin plugin, Player player, RybaGatunek gatunek, FishingConfig.MinigryConfig cfg, Runnable naSukces, Runnable naPorazke) {
        this.player = player;
        this.naSukces = naSukces;
        this.naPorazke = naPorazke;

        this.szerokoscPaska = cfg.szerokoscPaska();
        this.grawitacja = cfg.grawitacja();
        this.impulsKlikniecia = cfg.impulsKlikniecia();
        this.okresTickow = cfg.okresTickow();
        this.dt = okresTickow / 20.0;
        this.maksymalnyCzasTickow = cfg.maksymalnyCzasTickow();

        int trudnosc = gatunek.rzadkosc().ordinal(); // 0 (zwykła) .. 4 (legendarna)
        this.polowaSzerokosciSuwaka = cfg.polowaSzerokosciSuwaka().wartosc(trudnosc);
        this.predkoscRyby = cfg.predkoscRyby().wartosc(trudnosc);
        this.tempoNapelniania = cfg.tempoNapelniania().wartosc(trudnosc);
        this.tempoOprozniania = cfg.tempoOprozniania().wartosc(trudnosc);

        this.pasek = BossBar.bossBar(Component.text("Łowienie..."), (float) postep, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        player.showBossBar(pasek);

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, okresTickow, okresTickow);
    }

    /** Wywoływane z FishingManager.onInteract przy PPM, dopóki minigra trwa - "podbicie" suwaka w górę. */
    void kliknij() {
        predkoscSuwaka = impulsKlikniecia;
    }

    private void tick() {
        tickiOdStartu += okresTickow;

        if (Math.abs(celRyby - pozycjaRyby) < 0.02) {
            celRyby = ThreadLocalRandom.current().nextDouble(0.05, 0.95);
        }
        double kierunek = Math.signum(celRyby - pozycjaRyby);
        pozycjaRyby = clamp(pozycjaRyby + kierunek * predkoscRyby * dt, 0.0, 1.0);

        predkoscSuwaka -= grawitacja * dt;
        pozycjaSuwaka += predkoscSuwaka * dt;
        if (pozycjaSuwaka < polowaSzerokosciSuwaka) {
            pozycjaSuwaka = polowaSzerokosciSuwaka;
            predkoscSuwaka = 0.0;
        } else if (pozycjaSuwaka > 1 - polowaSzerokosciSuwaka) {
            pozycjaSuwaka = 1 - polowaSzerokosciSuwaka;
            predkoscSuwaka = 0.0;
        }

        boolean naRybie = Math.abs(pozycjaRyby - pozycjaSuwaka) <= polowaSzerokosciSuwaka;
        postep = clamp(postep + (naRybie ? tempoNapelniania : -tempoOprozniania) * dt, 0.0, 1.0);

        pasek.progress((float) postep);
        pasek.color(naRybie ? BossBar.Color.GREEN : BossBar.Color.RED);
        pasek.name(zbudujPasek());

        if (postep >= 1.0) {
            zakoncz(true);
        } else if (postep <= 0.0) {
            zakoncz(false);
        } else if (tickiOdStartu >= maksymalnyCzasTickow) {
            zakoncz(false);
        }
    }

    private Component zbudujPasek() {
        int slotRyby = (int) Math.round(pozycjaRyby * (szerokoscPaska - 1));
        int start = (int) Math.round((pozycjaSuwaka - polowaSzerokosciSuwaka) * (szerokoscPaska - 1));
        int koniec = (int) Math.round((pozycjaSuwaka + polowaSzerokosciSuwaka) * (szerokoscPaska - 1));

        Component wynik = Component.text("Łowienie: ", NamedTextColor.GOLD);
        for (int i = 0; i < szerokoscPaska; i++) {
            boolean wSuwaku = i >= start && i <= koniec;
            if (i == slotRyby) {
                wynik = wynik.append(Component.text("✦", wSuwaku ? NamedTextColor.GREEN : NamedTextColor.RED));
            } else if (wSuwaku) {
                wynik = wynik.append(Component.text("█", NamedTextColor.AQUA));
            } else {
                wynik = wynik.append(Component.text("░", NamedTextColor.DARK_GRAY));
            }
        }
        return wynik;
    }

    private void zakoncz(boolean sukces) {
        task.cancel();
        player.hideBossBar(pasek);
        if (sukces) naSukces.run(); else naPorazke.run();
    }

    /** Twarde przerwanie bez wywołania callbacków (rozłączenie gracza) - patrz FishingManager.onQuit. */
    void przerwij() {
        task.cancel();
        player.hideBossBar(pasek);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}