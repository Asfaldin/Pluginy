package elo.mainplugins.fishing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Efekt "widowiskowy" dla najrzadszych ryb (Mistyczna Rybka i wyżej) - w 100% na
 * bezpiecznym, standardowym Bukkit API (Display entity + cząsteczki + dźwięk + tytuł),
 * ZERO fałszywych pakietów/bloków.
 *
 * Poprzednia wersja tej klasy próbowała fejkować PRAWDZIWY laser beacona przez
 * ProtocolLib (fałszywe pakiety BLOCK_CHANGE/TILE_ENTITY_DATA, widoczne tylko dla
 * pobliskich graczy) - porzucone po tym jak niepoprawnie zbudowany TILE_ENTITY_DATA
 * ROZŁĄCZYŁ GRACZA z serwera na żywym teście (błąd dekodowania pakietu po stronie
 * klienta), a nawet po jego usunięciu sam blok+szkło i tak nie pokazywał promienia
 * (patrz historia gita/rozmowy). ProtocolLib nie jest już w ogóle potrzebny w tym
 * module - patrz pom.xml/plugin.yml.
 *
 * To poniżej: BlockDisplay to PRAWDZIWA, bezpieczna encja (NIE blok!) - nie da się jej
 * złamać/zdupić, bo nie ma czegoś takiego jak "zniszcz encję wyświetlającą". Rozciągnięta
 * transformacją w wysoką, cienką, świecącą (setGlowColorOverride) belkę w kolorze
 * gatunku, oplecioną obracającym się korkociągiem kolorowych cząsteczek (ta sama
 * matematyka co poprzednio). Do tego jednorazowo przy złowieniu: wybuch
 * Particle.TOTEM_OF_UNDYING, mocniejszy dźwięk i duży tytuł na ekranie łowcy.
 */
final class MistycznyLaser {

    // Naprawdę wysoko i grubiej niż pierwsza wersja (było 10 / 0.18) - za cienki i za
    // niski słup optycznie "przechylał się" pod kątem (naturalna perspektywa przy
    // cienkim, wysokim obiekcie) i nie wyglądał jak "sięga w niebo". Grubszy + naprawdę
    // wysoki słup stoi wizualnie stabilniej z każdego kąta i realnie sięga w niebo.
    private static final double WYSOKOSC_SLUPA = 100.0;
    private static final float SZEROKOSC_SLUPA = 0.32f;
    private static final long OKRES_ANIMACJI_TICKOW = 2L;
    private static final double PROMIEN_HELISY = 0.6;
    // Krok korkociągu zwiększony proporcjonalnie do wysokości (było 0.6 przy 30 blokach) -
    // inaczej przy 100 blokach byłoby to ~170 cząsteczek co klatkę animacji, bez potrzeby.
    private static final double KROK_HELISY = 1.5;
    private static final double OBROTOW_NA_BLOK = 0.5; // ile pelnych obrotow korkociagu na 1 blok wysokosci

    private MistycznyLaser() {}

    /** @return Runnable do wywołania, gdy efekt ma zniknąć (patrz FishingManager.rozpocznijMinigre) - bezpieczne do wywołania więcej niż raz. */
    static Runnable pokaz(Plugin plugin, Player lowca, Location lokalizacjaHaka, NamedTextColor kolorRzadkosci, String nazwaGatunku) {
        World world = lokalizacjaHaka.getWorld();
        // BEZ yaw/pitch (nowa Location, nie .clone()) - FishHook.getLocation() niesie ze
        // sobą kierunek w jakim leciał hak (mniej więcej gdzie patrzył gracz przy rzucie).
        // Spawnowanie encji NA takiej lokalizacji dziedziczy tę rotację jako jej bazową
        // orientację, więc nasza transformacja (rozciągnięcie w górę) doklejała się do
        // PRZEKRZYWIONEJ przestrzeni zamiast wprost do świata - stąd słup leciał w stronę
        // spojrzenia gracza zamiast prosto w górę.
        Location srodek = new Location(world, lokalizacjaHaka.getX(), lokalizacjaHaka.getY() + 0.1, lokalizacjaHaka.getZ());
        Color kolorBukkit = Color.fromRGB(kolorRzadkosci.value());
        Material szklo = szkloDlaKoloru(kolorRzadkosci);

        BlockDisplay slup = world.spawn(srodek, BlockDisplay.class, e -> {
            e.setBlock(szklo.createBlockData());
            e.setGlowing(true);
            e.setGlowColorOverride(kolorBukkit);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.FIXED);
            // Zerowa interpolacja - bez tego docelowy kształt (cienki wysoki slup) mogl
            // sie "domalowywac" od domyslnego 1x1x1 przez chwile po zesponowaniu, co
            // wygladaloby jak przechylanie sie.
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(0);
            // Origin (0,0,0) w BlockDisplay to róg bloku - centrujemy w X/Z, rozciągamy w Y.
            e.setTransformation(new Transformation(
                    new Vector3f(-SZEROKOSC_SLUPA / 2f, 0f, -SZEROKOSC_SLUPA / 2f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(SZEROKOSC_SLUPA, (float) WYSOKOSC_SLUPA, SZEROKOSC_SLUPA),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
        });

        // Jednorazowa "fanfara" na start - wybuch, dźwięk, tytuł na ekranie łowcy.
        world.spawnParticle(Particle.TOTEM_OF_UNDYING, srodek, 60, 0.4, 0.6, 0.4, 0.2);
        world.playSound(srodek, Sound.ITEM_TOTEM_USE, 1.0f, 1.3f);
        lowca.showTitle(Title.title(
                Component.text("✦ " + nazwaGatunku.toUpperCase() + " ✦", kolorRzadkosci, TextDecoration.BOLD),
                Component.text("Rzadki połów!", NamedTextColor.GRAY)));

        Particle.DustOptions kolorCzastek = new Particle.DustOptions(kolorBukkit, 1.0f);

        BukkitTask[] uchwyt = new BukkitTask[1];
        boolean[] zatrzymany = {false};
        long[] uplynioneTickow = {0};

        Runnable zatrzymaj = () -> {
            if (zatrzymany[0]) return; // bezpieczne do wywolania wiecej niz raz, patrz javadoc
            zatrzymany[0] = true;
            uchwyt[0].cancel();
            slup.remove();
        };

        uchwyt[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            uplynioneTickow[0] += OKRES_ANIMACJI_TICKOW;

            for (double y = 0; y <= WYSOKOSC_SLUPA; y += KROK_HELISY) {
                double kat = (y * OBROTOW_NA_BLOK + uplynioneTickow[0] * 0.05) * 2 * Math.PI;
                double x = Math.cos(kat) * PROMIEN_HELISY;
                double z = Math.sin(kat) * PROMIEN_HELISY;
                world.spawnParticle(Particle.DUST, srodek.clone().add(x, y, z), 1, 0, 0, 0, 0, kolorCzastek);
            }
        }, OKRES_ANIMACJI_TICKOW, OKRES_ANIMACJI_TICKOW);

        return zatrzymaj;
    }

    private static Material szkloDlaKoloru(NamedTextColor kolor) {
        if (kolor.equals(NamedTextColor.BLACK)) return Material.BLACK_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.DARK_BLUE)) return Material.BLUE_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.DARK_GREEN)) return Material.GREEN_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.DARK_AQUA)) return Material.CYAN_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.DARK_RED)) return Material.RED_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.DARK_PURPLE)) return Material.PURPLE_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.GOLD)) return Material.ORANGE_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.GRAY)) return Material.LIGHT_GRAY_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.DARK_GRAY)) return Material.GRAY_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.BLUE)) return Material.LIGHT_BLUE_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.GREEN)) return Material.LIME_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.AQUA)) return Material.CYAN_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.RED)) return Material.RED_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.LIGHT_PURPLE)) return Material.MAGENTA_STAINED_GLASS;
        if (kolor.equals(NamedTextColor.YELLOW)) return Material.YELLOW_STAINED_GLASS;
        return Material.WHITE_STAINED_GLASS;
    }
}
