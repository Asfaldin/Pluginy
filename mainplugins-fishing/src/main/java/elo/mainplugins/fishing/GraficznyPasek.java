package elo.mainplugins.fishing;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomItemService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Graficzny (a nie tekstowy) pasek minigry łowienia - user 2026-08-30: "ten drugi
 * ładniejszy lewo prawo" (w odróżnieniu od zwykłego tekstowego, który zostaje WYŁĄCZNIE
 * góra/dół - patrz PozycjaPaska/FishingMinigame). Zbudowany z 6 płaskich custom-itemów
 * (patrz custom-items.yml, LOWIENIE_*) wyświetlanych jako ItemDisplay encje "dosiadające"
 * gracza (dokładnie ten sam, bezpieczny, czysto-Bukkit mechanizm co MistycznyLaser - ZERO
 * ProtocolLib) - podąża za obrotem CIAŁA gracza (lewo/prawo), nie za pochyleniem głowy
 * (patrz rozmowa z userem: świadomy kompromis, inaczej trzeba by śledzić kamerę pakietami,
 * a to już raz rozłączyło gracza w tym projekcie).
 *
 * Warstwy (od tyłu do przodu, patrz Z w transformacjach): tło (gradient nieba/wody) ->
 * ramka (drewniana krawędź) -> suwak (zielony cel, jeździ pionowo wg pozycjaSuwaka) ->
 * rybka (biały znacznik, jeździ pionowo wg pozycjaRyby) -> miernik-tło + miernik-fill
 * (osobny pionowy słupek postępu połowu, obok głównej rurki).
 *
 * STROJENIE: wszystkie stałe niżej (offsety/skala) są pierwszym przybliżeniem - dopiero
 * live-test w grze pokaże czy trzeba je poprawić (nie da się tego zweryfikować bez
 * realnego klienta Minecraft).
 */
final class GraficznyPasek {

    // Ile bloków w bok/przod/gore wzgledem gracza (w lokalnym układzie encji-kotwicy,
    // wiec obraca sie razem z cialem gracza) - DO DOSTROJENIA na zywo.
    private static final float OFFSET_BOK = 0.9f;
    private static final float OFFSET_PRZOD = -0.4f; // ujemne = lekko przed gracza (w strone patrzenia ciala)
    private static final float OFFSET_GORA = 0.3f;

    // Bazowa skala calej rurki - "item/generated" renderuje tekstury 1:1 proporcji
    // pikseli, wiec ten jeden wspolczynnik utrzymuje wszystkie warstwy w tych samych
    // proporcjach wzgledem siebie.
    private static final float SKALA = 0.045f;

    // Wysokosc "rurki" w pikselach tekstury (lowienie_tlo.png ma 128px) - do przeliczania
    // pozycjaRyby/pozycjaSuwaka (0..1) na przesuniecie w pionie.
    private static final float WYSOKOSC_TLA_PX = 128f;
    private static final float ODSTEP_MIERNIKA_PX = 26f; // ile pikseli (w skali tla) boczny miernik postepu stoi dalej od glownej rurki

    private final ItemDisplay kotwica;
    private final ItemDisplay tlo;
    private final ItemDisplay ramka;
    private final ItemDisplay suwak;
    private final ItemDisplay rybka;
    private final ItemDisplay miernikTlo;
    private final ItemDisplay miernikFill;

    private final long okresTickow;
    /** Bazowy pionowy offset TEGO konkretnego paska (patrz pokaz) - GORA/DOL (tryb testowy, patrz StronaPaska) mają inny niż domyślny OFFSET_GORA, więc przesuwanie suwaka/rybki/miernika w aktualizuj() musi liczyć WZGLĘDEM TEGO, nie względem stałej. */
    private final float goraY;

    private GraficznyPasek(ItemDisplay kotwica, ItemDisplay tlo, ItemDisplay ramka, ItemDisplay suwak,
                            ItemDisplay rybka, ItemDisplay miernikTlo, ItemDisplay miernikFill, long okresTickow, float goraY) {
        this.kotwica = kotwica;
        this.tlo = tlo;
        this.ramka = ramka;
        this.suwak = suwak;
        this.rybka = rybka;
        this.miernikTlo = miernikTlo;
        this.goraY = goraY;
        this.miernikFill = miernikFill;
        this.okresTickow = okresTickow;
    }

    /** @return null jeśli rejestr custom itemów jest niedostępny albo któregoś z LOWIENIE_* brakuje - wołający ma wtedy spaść do trybu tekstowego. */
    static GraficznyPasek pokaz(Plugin plugin, Player player, StronaPaska strona, long okresTickow) {
        CustomItemService rejestr = CoreAPI.getCustomItemService();
        if (rejestr == null) return null;

        ItemStack itemTlo = rejestr.create("LOWIENIE_TLO", 1);
        ItemStack itemRamka = rejestr.create("LOWIENIE_RAMKA", 1);
        ItemStack itemSuwak = rejestr.create("LOWIENIE_SUWAK", 1);
        ItemStack itemRybka = rejestr.create("LOWIENIE_RYBKA", 1);
        ItemStack itemMiernikTlo = rejestr.create("LOWIENIE_MIERNIK_TLO", 1);
        ItemStack itemMiernikFill = rejestr.create("LOWIENIE_MIERNIK_FILL", 1);
        if (itemTlo == null || itemRamka == null || itemSuwak == null || itemRybka == null || itemMiernikTlo == null || itemMiernikFill == null) {
            return null;
        }

        // Docelowo TYLKO lewo/prawo (patrz javadoc StronaPaska) - gora/dol ponizej to
        // WYLACZNIE tryb testowy (user 2026-08-31), zeby sprawdzic czy sam mechanizm
        // (encje/tekstury) dziala, niezaleznie od tego czy offsety lewo/prawo sa dobrze
        // dobrane. bokX = poziomy offset (lewo/prawo wzgledem ciala gracza, 0 gdy
        // wysrodkowane), goraY = pionowy offset (wyzej dla GORA, nizej dla DOL).
        float bokX = switch (strona) {
            case LEWO -> -OFFSET_BOK;
            case PRAWO -> OFFSET_BOK;
            case GORA, DOL -> 0f;
        };
        float goraY = switch (strona) {
            case GORA -> OFFSET_GORA + 0.9f;
            case DOL -> OFFSET_GORA - 0.9f;
            case LEWO, PRAWO -> OFFSET_GORA;
        };
        // Kierunek w ktora strone od glownej rurki stoi boczny miernik postepu - przy
        // lewo/prawo dalej w tamta strone, przy gora/dol (wysrodkowane) po prostu w prawo.
        float bokMiernika = strona == StronaPaska.LEWO ? -1f : 1f;

        ItemDisplay kotwica = spawnKotwica(player);

        ItemDisplay tlo = spawnWarstwe(player, itemTlo, bokX, goraY, OFFSET_PRZOD, SKALA);
        ItemDisplay ramka = spawnWarstwe(player, itemRamka, bokX, goraY, OFFSET_PRZOD - 0.01f, SKALA);
        ItemDisplay suwak = spawnWarstwe(player, itemSuwak, bokX, goraY, OFFSET_PRZOD - 0.02f, SKALA);
        ItemDisplay rybka = spawnWarstwe(player, itemRybka, bokX, goraY, OFFSET_PRZOD - 0.03f, SKALA);
        ItemDisplay miernikTlo = spawnWarstwe(player, itemMiernikTlo, bokX + bokMiernika * ODSTEP_MIERNIKA_PX * SKALA / 16f, goraY, OFFSET_PRZOD, SKALA);
        ItemDisplay miernikFill = spawnWarstwe(player, itemMiernikFill, bokX + bokMiernika * ODSTEP_MIERNIKA_PX * SKALA / 16f, goraY, OFFSET_PRZOD - 0.01f, SKALA);

        kotwica.addPassenger(tlo);
        kotwica.addPassenger(ramka);
        kotwica.addPassenger(suwak);
        kotwica.addPassenger(rybka);
        kotwica.addPassenger(miernikTlo);
        kotwica.addPassenger(miernikFill);
        player.addPassenger(kotwica);

        GraficznyPasek pasek = new GraficznyPasek(kotwica, tlo, ramka, suwak, rybka, miernikTlo, miernikFill, okresTickow, goraY);
        pasek.aktualizuj(0.5, 0.5, 0.0);
        return pasek;
    }

    /** Niewidoczna, zerowa "kotwica" jadąca na graczu - jedyny bezpośredni pasażer gracza, żeby wanilijska logika rozmieszczania wielu pasażerów na jednym wierzchowcu nie kłóciła się z naszymi transformacjami (patrz javadoc klasy - wszystkie 6 warstw jeździ NA NIEJ, nie na graczu). */
    private static ItemDisplay spawnKotwica(Player player) {
        return player.getWorld().spawn(player.getLocation(), ItemDisplay.class, e -> {
            e.setItemStack(new ItemStack(org.bukkit.Material.AIR));
            e.setPersistent(false);
            e.setGravity(false);
            e.setInvulnerable(true);
            e.setInterpolationDelay(0);
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(0, 0, 0, 1), new Vector3f(0f, 0f, 0f), new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    private static ItemDisplay spawnWarstwe(Player player, ItemStack item, float x, float y, float z, float skala) {
        return player.getWorld().spawn(player.getLocation(), ItemDisplay.class, e -> {
            e.setItemStack(item);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setBillboard(Display.Billboard.CENTER);
            e.setPersistent(false);
            e.setGravity(false);
            e.setInvulnerable(true);
            e.setInterpolationDelay(0);
            e.setTransformation(new Transformation(
                    new Vector3f(x, y, z),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(skala, skala, skala),
                    new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    /**
     * Wywoływane co tyknięcie minigry (patrz FishingMinigame.tick) - przesuwa suwak/rybkę
     * pionowo wg ich pozycji 0..1 (0 = dół rurki, 1 = góra) i "podnosi" wypełnienie
     * bocznego miernika postępu jak w termometrze (rośnie OD DOŁU).
     */
    void aktualizuj(double pozycjaSuwaka, double pozycjaRyby, double postep) {
        float wysokoscSwiat = WYSOKOSC_TLA_PX * SKALA;

        przesunPionowo(suwak, pozycjaSuwaka, wysokoscSwiat);
        przesunPionowo(rybka, pozycjaRyby, wysokoscSwiat);

        // Wypelnienie miernika "rosnie od dolu" - skalujemy w Y do procentu postepu i
        // przesuwamy w dol o brakujaca czesc, zeby GORNA krawedz byla tam gdzie realnie
        // konczy sie wypelniony procent, a nie zeby skalowalo sie od srodka.
        float procent = (float) Math.max(0.0, Math.min(1.0, postep));
        Transformation aktualna = miernikFill.getTransformation();
        Vector3f translacja = new Vector3f(aktualna.getTranslation().x, goraY - (1f - procent) * wysokoscSwiat / 2f, aktualna.getTranslation().z);
        Vector3f skala = new Vector3f(SKALA, SKALA * procent, SKALA);
        miernikFill.setTransformation(new Transformation(translacja, new AxisAngle4f(0, 0, 0, 1), skala, new AxisAngle4f(0, 0, 0, 1)));
    }

    private void przesunPionowo(ItemDisplay warstwa, double pozycja0do1, float wysokoscSwiat) {
        float y = goraY + ((float) pozycja0do1 - 0.5f) * wysokoscSwiat;
        Transformation aktualna = warstwa.getTransformation();
        Vector3f translacja = new Vector3f(aktualna.getTranslation().x, y, aktualna.getTranslation().z);
        warstwa.setTransformation(new Transformation(translacja, aktualna.getLeftRotation(), aktualna.getScale(), aktualna.getRightRotation()));
    }

    /** Sprzątanie po zakończeniu minigry - bezpieczne, encje i tak mają setPersistent(false), ale nie czekamy na chunk unload. */
    void usun() {
        kotwica.getPassengers().forEach(org.bukkit.entity.Entity::remove);
        kotwica.remove();
    }
}
