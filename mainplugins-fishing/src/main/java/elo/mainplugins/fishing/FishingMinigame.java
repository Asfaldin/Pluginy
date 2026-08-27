package elo.mainplugins.fishing;

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
 * rzadkości gatunku wylosowanego jeszcze przed startem minigry - a NA TO z kolei nakłada
 * się profil trzymanej wędki (patrz WedkaProfil), który tymi samymi czterema wartościami
 * kręci dalej w swoją stronę (mnożnik 1.0 = bez zmian, czyli WedkaProfil.ZROWNOWAZONA).
 */
final class FishingMinigame {

    private static final int SZEROKOSC_PASKA = 40;
    // Patrz zbudujMiernikPostepu - krótszy niż SZEROKOSC_PASKA, bo to tylko dodatkowy
    // miernik ogólnego postępu (WYŁĄCZNIE dla action bara/PozycjaPaska.DOL), nie sam suwak.
    private static final int SZEROKOSC_MIERNIKA_POSTEPU = 12;
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
    // Okres tyknięcia zmniejszony 2->1 (10Hz -> 20Hz, co tick serwera) wyłącznie dla
    // płynności animacji paska - cała fizyka liczona jest przez DT (sekundy na tyknięcie),
    // więc trudność/tempo gry się NIE zmieniają, tylko rozdzielczość czasowa ruchu.
    private static final long OKRES_TICKOW = 1L;
    private static final double DT = OKRES_TICKOW / 20.0;

    // Progi postępu, przy których (raz, przy pierwszym przekroczeniu) leci narastający
    // dźwięk "coraz bliżej" - patrz tick(). Czysto kosmetyczne, nie wpływają na trudność.
    private static final double[] KAMIENIE_MILOWE_POSTEPU = {0.5, 0.75, 0.9};

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

    FishingMinigame(Plugin plugin, Player player, RybaGatunek gatunek, WedkaProfil profil, PozycjaPaska pozycja, Runnable naSukces, Runnable naPorazke) {
        this.player = player;
        this.naSukces = naSukces;
        this.naPorazke = naPorazke;
        this.pozycja = pozycja;

        int trudnosc = gatunek.rzadkosc().ordinal(); // 0 (zwykła) .. 4 (legendarna)
        // Suwak (okno, które musi nakrywać ✦) ok. o połowę węższy niż wcześniej (było
        // 0.09-0.20 połówki szerokości) - trudniej trafić i utrzymać się na rybie.
        // Profil wędki (patrz WedkaProfil) potem jeszcze przemnaża tę wartość w swoją
        // stronę - clamp na końcu to tylko twardy bezpiecznik przed degeneratywnymi
        // wartościami (np. suwak węższy niż da się fizycznie trafić), nie tuning sam w sobie.
        double szerokoscBazowa = clamp(0.10 - 0.011 * trudnosc, 0.05, 0.10);
        this.polowaSzerokosciSuwaka = clamp(szerokoscBazowa * profil.mnoznikSzerokosciSuwaka(), 0.03, 0.17);
        // Ryba "wyrywa się" (ucieka do losowego celu) wyraźnie wolniej niż wcześniej -
        // było 0.35 + 0.18*trudnosc, co przy zwykłej rybie robiło pełny przelot paska
        // w ~1.4s. Teraz ~3.5x wolniej.
        this.predkoscRyby = clamp((0.10 + 0.05 * trudnosc) * profil.mnoznikPredkosciRyby(), 0.02, 0.6);
        // Napełnianie miernika połowu ok. o połowę wolniejsze niż wcześniej (było
        // 0.20-0.35) - cały połów trwa wyraźnie dłużej nawet przy trafianiu w rybę.
        double napelnianieBazowe = clamp(0.18 - 0.015 * trudnosc, 0.10, 0.18);
        this.tempoNapelniania = clamp(napelnianieBazowe * profil.mnoznikTempaNapelniania(), 0.04, 0.34);
        // Kara za pudłowanie była wyraźnie ostrzejsza niż nagroda za trafienie (było
        // 0.20-0.32, ~1.5-2x tempoNapelniania) - jedno spudłowanie kasowało więcej postępu
        // niż zdążyło się zdobyć trafiając. Teraz zbliżone do tempa napełniania (nawet
        // lekko łagodniejsze), żeby chwilowe zgubienie ryby nie zerowało progresu.
        double oprozznianieBazowe = clamp(0.12 + 0.02 * trudnosc, 0.12, 0.20);
        this.tempoOprozniania = clamp(oprozznianieBazowe * profil.mnoznikTempaOprozniania(), 0.05, 0.32);

        // Patrz PozycjaPaska - GORA dostaje prawdziwy bossbar (u góry ekranu, z natywnym
        // paskiem wypełnienia), DOL w ogóle go nie tworzy (patrz tick() - tam zamiast tego
        // leci action bar co tyknięcie).
        if (pozycja == PozycjaPaska.GORA) {
            this.pasek = BossBar.bossBar(Component.text("Łowienie..."), (float) postep, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            player.showBossBar(pasek);
        } else {
            this.pasek = null;
        }

        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, OKRES_TICKOW, OKRES_TICKOW);
    }

    /** Wywoływane z FishingManager.onInteract przy PPM, dopóki minigra trwa - "podbicie" suwaka w górę. */
    void kliknij() {
        predkoscSuwaka = IMPULS_KLIKNIECIA;
    }

    private void tick() {
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
        int slotRyby = (int) Math.round(pozycjaRyby * (SZEROKOSC_PASKA - 1));
        double startCiagly = (pozycjaSuwaka - polowaSzerokosciSuwaka) * (SZEROKOSC_PASKA - 1);
        double koniecCiagly = (pozycjaSuwaka + polowaSzerokosciSuwaka) * (SZEROKOSC_PASKA - 1);

        Component wynik = Component.empty();
        for (int i = 0; i < SZEROKOSC_PASKA; i++) {
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