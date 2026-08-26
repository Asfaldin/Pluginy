package elo.mainplugins.fishing;

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

    private static final int SZEROKOSC_PASKA = 40;
    // Spowolnione (v1 byla za szybka do ogarniecia) - grawitacja/impuls ok. 2.5x
    // lagodniejsze, tempo napelniania/oprozniania ~35% wolniejsze, patrz tez
    // predkoscRyby nizej (rzeczywisty czynnik "ryba sie wyrywa").
    // Grawitacja/impuls tuningowane w dwie strony: najpierw zmniejszone (bylo 0.55/0.30),
    // bo suwak "nie wyrabial" doganiac rybki w gore - ale za wolno tez OPADAL (ruch w
    // lewo/w dol paska), wiec teraz grawitacja z powrotem podkrecona (mocniej niz nawet
    // oryginalne 0.55) przy zachowaniu mocniejszego impulsu z gory - szybszy ruch w OBIE
    // strony, nie tylko w gore.
    private static final double GRAWITACJA = 0.65;
    private static final double IMPULS_KLIKNIECIA = 0.38;
    private static final long OKRES_TICKOW = 2L;
    private static final double DT = OKRES_TICKOW / 20.0;
    private static final long MAKSYMALNY_CZAS_TICKOW = 20L * 30;

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

    FishingMinigame(Plugin plugin, Player player, RybaGatunek gatunek, Runnable naSukces, Runnable naPorazke) {
        this.player = player;
        this.naSukces = naSukces;
        this.naPorazke = naPorazke;

        int trudnosc = gatunek.rzadkosc().ordinal(); // 0 (zwykła) .. 4 (legendarna)
        // Suwak (okno, które musi nakrywać ✦) ok. o połowę węższy niż wcześniej (było
        // 0.09-0.20 połówki szerokości) - trudniej trafić i utrzymać się na rybie.
        this.polowaSzerokosciSuwaka = clamp(0.10 - 0.011 * trudnosc, 0.05, 0.10);
        // Ryba "wyrywa się" (ucieka do losowego celu) wyraźnie wolniej niż wcześniej -
        // było 0.35 + 0.18*trudnosc, co przy zwykłej rybie robiło pełny przelot paska
        // w ~1.4s. Teraz ~3.5x wolniej.
        this.predkoscRyby = 0.10 + 0.05 * trudnosc;
        // Napełnianie miernika połowu ok. o połowę wolniejsze niż wcześniej (było
        // 0.20-0.35) - cały połów trwa wyraźnie dłużej nawet przy trafianiu w rybę.
        this.tempoNapelniania = clamp(0.18 - 0.015 * trudnosc, 0.10, 0.18);
        // Kara za pudłowanie była wyraźnie ostrzejsza niż nagroda za trafienie (było
        // 0.20-0.32, ~1.5-2x tempoNapelniania) - jedno spudłowanie kasowało więcej postępu
        // niż zdążyło się zdobyć trafiając. Teraz zbliżone do tempa napełniania (nawet
        // lekko łagodniejsze), żeby chwilowe zgubienie ryby nie zerowało progresu.
        this.tempoOprozniania = clamp(0.12 + 0.02 * trudnosc, 0.12, 0.20);

        this.pasek = BossBar.bossBar(Component.text("Łowienie..."), (float) postep, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        player.showBossBar(pasek);

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, OKRES_TICKOW, OKRES_TICKOW);
    }

    /** Wywoływane z FishingManager.onInteract przy PPM, dopóki minigra trwa - "podbicie" suwaka w górę. */
    void kliknij() {
        predkoscSuwaka = IMPULS_KLIKNIECIA;
    }

    private void tick() {
        tickiOdStartu += OKRES_TICKOW;

        if (Math.abs(celRyby - pozycjaRyby) < 0.02) {
            celRyby = ThreadLocalRandom.current().nextDouble(0.05, 0.95);
        }
        double kierunek = Math.signum(celRyby - pozycjaRyby);
        pozycjaRyby = clamp(pozycjaRyby + kierunek * predkoscRyby * DT, 0.0, 1.0);

        predkoscSuwaka -= GRAWITACJA * DT;
        pozycjaSuwaka += predkoscSuwaka * DT;
        if (pozycjaSuwaka < polowaSzerokosciSuwaka) {
            pozycjaSuwaka = polowaSzerokosciSuwaka;
            predkoscSuwaka = 0.0;
        } else if (pozycjaSuwaka > 1 - polowaSzerokosciSuwaka) {
            pozycjaSuwaka = 1 - polowaSzerokosciSuwaka;
            predkoscSuwaka = 0.0;
        }

        boolean naRybie = Math.abs(pozycjaRyby - pozycjaSuwaka) <= polowaSzerokosciSuwaka;
        postep = clamp(postep + (naRybie ? tempoNapelniania : -tempoOprozniania) * DT, 0.0, 1.0);

        pasek.progress((float) postep);
        pasek.color(naRybie ? BossBar.Color.GREEN : BossBar.Color.RED);
        pasek.name(zbudujPasek());

        if (postep >= 1.0) {
            zakoncz(true);
        } else if (postep <= 0.0) {
            zakoncz(false);
        } else if (tickiOdStartu >= MAKSYMALNY_CZAS_TICKOW) {
            zakoncz(false);
        }
    }

    private Component zbudujPasek() {
        int slotRyby = (int) Math.round(pozycjaRyby * (SZEROKOSC_PASKA - 1));
        int start = (int) Math.round((pozycjaSuwaka - polowaSzerokosciSuwaka) * (SZEROKOSC_PASKA - 1));
        int koniec = (int) Math.round((pozycjaSuwaka + polowaSzerokosciSuwaka) * (SZEROKOSC_PASKA - 1));

        Component wynik = Component.text("Łowienie: ", NamedTextColor.GOLD);
        for (int i = 0; i < SZEROKOSC_PASKA; i++) {
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