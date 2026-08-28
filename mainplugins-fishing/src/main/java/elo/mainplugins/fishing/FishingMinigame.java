package elo.mainplugins.fishing;

import elo.mainplugins.fishing.config.FishingConfig;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Minigra łowienia w stylu Stardew Valley: rybka błądzi losowo po pasku 0..1, gracz
 * PPM-em "podbija" swój suwak w górę (rytmiczne klikanie, nie przytrzymanie - patrz
 * FishingManager.onInteract), grawitacja ściąga go w dół. Nakładanie się suwaka na
 * rybkę napełnia miernik połowu (BossBar.progress), brak nakładania go opróżnia.
 * Trudność (szerokość suwaka, prędkość rybki, tempo napełniania/opróżniania) zależy od
 * rzadkości gatunku wylosowanego jeszcze przed startem minigry (bazowe wartości z
 * fishing-config.yml, patrz FishingConfig) - a NA TO z kolei nakłada się profil trzymanej
 * wędki (patrz WedkaProfil), który tymi samymi czterema wartościami kręci dalej w swoją
 * stronę (mnożnik 1.0 = bez zmian, czyli WedkaProfil.ZROWNOWAZONA).
 */
final class FishingMinigame {

    // Patrz zbudujMiernikPostepu - krótszy niż szerokoscPaska, bo to tylko dodatkowy
    // miernik ogólnego postępu (WYŁĄCZNIE dla action bara/PozycjaPaska.DOL), nie sam
    // suwak. Stała, nie w fishing-config.yml - to czysto kosmetyczny wymiar renderowania,
    // nie tuning trudności minigry jak pola niżej.
    private static final int SZEROKOSC_MIERNIKA_POSTEPU = 12;

    // Progi postępu, przy których (raz, przy pierwszym przekroczeniu) leci narastający
    // dźwięk "coraz bliżej" - patrz tick(). Czysto kosmetyczne, nie wpływają na trudność.
    private static final double[] KAMIENIE_MILOWE_POSTEPU = {0.5, 0.75, 0.9};

    // Tuning minigry wczytany z fishing-config.yml (patrz FishingConfig/FishingConfigLoader)
    // - przeładowywalny bez restartu przez /@reloadfishing (FishingManager.aktualizujKonfiguracje).
    private final int szerokoscPaska;
    private final double grawitacja;
    private final double impulsKlikniecia;
    private final long okresTickow;
    private final double dt;

    private final Player player;
    private final Runnable naSukces;
    private final Runnable naPorazke;
    private final PozycjaPaska pozycja;
    /** Tylko dla PozycjaPaska.GORA - patrz konstruktor i tick(). Dla DOL zostaje null, bo w ogóle nie pokazujemy bossbara. */
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

    // Feedback dźwiękowy - patrz tick(). poprzednioNaRybie wykrywa MOMENT wejścia/wyjścia
    // z trafienia (nie stan ciągły, żeby nie grać dźwięku co tyknięcie), kamienieOdpalone
    // pilnuje żeby każdy próg z KAMIENIE_MILOWE_POSTEPU zagrał tylko raz, nawet jeśli
    // postęp później spadnie i znów go przekroczy.
    private boolean poprzednioNaRybie = false;
    private final boolean[] kamienieOdpalone = new boolean[KAMIENIE_MILOWE_POSTEPU.length];

    FishingMinigame(Plugin plugin, Player player, RybaGatunek gatunek, WedkaProfil profil, FishingConfig.MinigryConfig cfg, PozycjaPaska pozycja, Runnable naSukces, Runnable naPorazke) {
        this.player = player;
        this.naSukces = naSukces;
        this.naPorazke = naPorazke;
        this.pozycja = pozycja;

        this.szerokoscPaska = cfg.szerokoscPaska();
        this.grawitacja = cfg.grawitacja();
        this.impulsKlikniecia = cfg.impulsKlikniecia();
        this.okresTickow = cfg.okresTickow();
        this.dt = okresTickow / 20.0;

        int trudnosc = gatunek.rzadkosc().ordinal(); // 0 (zwykła) .. 4 (legendarna)
        // Bazowa wartość zależna od rzadkości gatunku liczona z fishing-config.yml (patrz
        // FishingConfig.Formula.wartosc) - profil wędki (patrz WedkaProfil) potem jeszcze
        // przemnaża ją w swoją stronę. Zewnętrzny clamp to tylko twardy bezpiecznik przed
        // degeneratywnymi wartościami (np. suwak węższy niż da się fizycznie trafić), nie
        // tuning sam w sobie.
        this.polowaSzerokosciSuwaka = clamp(cfg.polowaSzerokosciSuwaka().wartosc(trudnosc) * profil.mnoznikSzerokosciSuwaka(), 0.03, 0.17);
        this.predkoscRyby = clamp(cfg.predkoscRyby().wartosc(trudnosc) * profil.mnoznikPredkosciRyby(), 0.02, 0.6);
        this.tempoNapelniania = clamp(cfg.tempoNapelniania().wartosc(trudnosc) * profil.mnoznikTempaNapelniania(), 0.04, 0.34);
        this.tempoOprozniania = clamp(cfg.tempoOprozniania().wartosc(trudnosc) * profil.mnoznikTempaOprozniania(), 0.05, 0.32);

        // Patrz PozycjaPaska - GORA dostaje prawdziwy bossbar (u góry ekranu, z natywnym
        // paskiem wypełnienia), DOL w ogóle go nie tworzy (patrz tick() - tam zamiast tego
        // leci action bar co tyknięcie).
        if (pozycja == PozycjaPaska.GORA) {
            this.pasek = BossBar.bossBar(Component.text("Łowienie..."), (float) postep, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            player.showBossBar(pasek);
        } else {
            this.pasek = null;
        }

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, okresTickow, okresTickow);
    }

    /** Wywoływane z FishingManager.onInteract przy PPM, dopóki minigra trwa - "podbicie" suwaka w górę. */
    void kliknij() {
        predkoscSuwaka = impulsKlikniecia;
    }

    private void tick() {
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

        // Cichy "klik" DOKŁADNIE w momencie wejścia/wyjścia z trafienia (nie co tyknięcie,
        // dopóki trwa) - słyszalne potwierdzenie trafienia niezależnie od patrzenia na pasek.
        if (naRybie != poprzednioNaRybie) {
            if (naRybie) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.6f);
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.4f, 0.8f);
            }
            poprzednioNaRybie = naRybie;
        }
        for (int i = 0; i < KAMIENIE_MILOWE_POSTEPU.length; i++) {
            if (!kamienieOdpalone[i] && postep >= KAMIENIE_MILOWE_POSTEPU[i]) {
                kamienieOdpalone[i] = true;
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.8f + 0.3f * i);
            }
        }

        if (pozycja == PozycjaPaska.GORA) {
            pasek.progress((float) postep);
            pasek.color(naRybie ? BossBar.Color.GREEN : BossBar.Color.RED);
            pasek.name(zbudujSuwakStrip(naRybie));
        } else {
            // Action bar nie ma natywnego paska wypełnienia jak bossbar (to co widać u góry
            // POD tytułem, patrz pasek.progress/pasek.color wyżej) - więc dorzucamy własny,
            // krótki miernik postępu połowu (patrz zbudujMiernikPostepu) przed suwakiem,
            // inaczej dół w ogóle nie pokazywałby jak blisko końca jest połów.
            // Trzeba wysyłać co tyknięcie (inaczej znika po kilku sekundach, tak jak każdy action bar).
            player.sendActionBar(zbudujMiernikPostepu(naRybie).append(Component.text(" ")).append(zbudujSuwakStrip(naRybie)));
        }

        // Świadomie BEZ limitu czasu - dopóki gracz utrzymuje postęp powyżej zera (choćby
        // ledwo), ryba NIE wyrywa się sama z siebie po jakimś czasie. Porażka wyłącznie
        // gdy miernik realnie spadnie do zera (patrz historia: wcześniej był tu jeszcze
        // twardy limit ok. 30s kończący się porażką NIEZALEŻNIE od tego jak szła walka -
        // mylące, bo "ryba się wyrywała" nawet w trakcie wygrywanej minigry).
        if (postep >= 1.0) {
            zakoncz(true);
        } else if (postep <= 0.0) {
            zakoncz(false);
        }
    }

    /**
     * Sam pasek, identyczny w obu trybach wyświetlania (patrz PozycjaPaska/tick()) - bez
     * żadnej etykiety tekstowej ("Łowienie:", procent itp.) przed nim, wyłącznie sam pasek
     * ładowania. Krawędzie suwaka rysowane z cieniowaniem (░▒▓█, ten sam blok Unicode co
     * dotychczas używane ░/█ - a więc ten sam font/glyph, zero ryzyka że coś nie
     * wyrenderuje się w grze) zamiast twardego zaokrąglenia do najbliższego znaku - "skoki"
     * suwaka o cały znak wcześniej dawały wrażenie schodkowego ruchu, teraz krawędź
     * płynniej się wypełnia w miarę jak suwak przesuwa się o ułamek szerokości znaku.
     */
    private Component zbudujSuwakStrip(boolean naRybie) {
        int slotRyby = (int) Math.round(pozycjaRyby * (szerokoscPaska - 1));
        double startCiagly = (pozycjaSuwaka - polowaSzerokosciSuwaka) * (szerokoscPaska - 1);
        double koniecCiagly = (pozycjaSuwaka + polowaSzerokosciSuwaka) * (szerokoscPaska - 1);

        Component wynik = Component.empty();
        for (int i = 0; i < szerokoscPaska; i++) {
            if (i == slotRyby) {
                wynik = wynik.append(Component.text("✦", naRybie ? NamedTextColor.GREEN : NamedTextColor.RED));
                continue;
            }
            // Pokrycie tego znaku (traktowanego jako przedział [i-0.5, i+0.5]) przez suwak.
            double pokrycie = clamp(Math.min(i + 0.5, koniecCiagly) - Math.max(i - 0.5, startCiagly), 0.0, 1.0);
            String znak;
            NamedTextColor kolor;
            if (pokrycie < 0.15) {
                znak = "░";
                kolor = NamedTextColor.DARK_GRAY;
            } else if (pokrycie < 0.5) {
                znak = "▒";
                kolor = NamedTextColor.DARK_AQUA;
            } else if (pokrycie < 0.85) {
                znak = "▓";
                kolor = NamedTextColor.AQUA;
            } else {
                znak = "█";
                kolor = NamedTextColor.AQUA;
            }
            wynik = wynik.append(Component.text(znak, kolor));
        }
        return wynik;
    }

    /**
     * WYŁĄCZNIE dla PozycjaPaska.DOL (patrz tick()) - krótki miernik ogólnego postępu
     * połowu (ten sam postep co pasek.progress/color na górze) w formie "[████░░░░]",
     * bo action bar (w odróżnieniu od bossbara) nie ma żadnego własnego, natywnego paska
     * wypełnienia - bez tego dół w ogóle nie pokazywałby jak blisko końca jest połów,
     * tylko sam suwak/rybkę (patrz zbudujSuwakStrip).
     */
    private Component zbudujMiernikPostepu(boolean naRybie) {
        int wypelnione = (int) Math.round(clamp(postep, 0.0, 1.0) * SZEROKOSC_MIERNIKA_POSTEPU);
        NamedTextColor kolorWypelnienia = naRybie ? NamedTextColor.GREEN : NamedTextColor.RED;

        Component wynik = Component.text("[", NamedTextColor.GRAY);
        for (int i = 0; i < SZEROKOSC_MIERNIKA_POSTEPU; i++) {
            wynik = wynik.append(Component.text("█", i < wypelnione ? kolorWypelnienia : NamedTextColor.DARK_GRAY));
        }
        return wynik.append(Component.text("]", NamedTextColor.GRAY));
    }

    private void zakoncz(boolean sukces) {
        task.cancel();
        ukryjPasek();
        if (sukces) naSukces.run(); else naPorazke.run();
    }

    /** Twarde przerwanie bez wywołania callbacków (rozłączenie gracza) - patrz FishingManager.onQuit. */
    void przerwij() {
        task.cancel();
        ukryjPasek();
    }

    /** Sprząta jakikolwiek trwały ślad paska na ekranie gracza - bossbar (GORA) albo ostatnią linię action bara (DOL, inaczej wisiałaby kilka sekund po zakończeniu minigry). */
    private void ukryjPasek() {
        if (pozycja == PozycjaPaska.GORA) {
            player.hideBossBar(pasek);
        } else {
            player.sendActionBar(Component.empty());
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
