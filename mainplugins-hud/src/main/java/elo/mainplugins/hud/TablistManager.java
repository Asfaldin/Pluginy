package elo.mainplugins.hud;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.TopGracz;
import elo.mainplugins.core.util.MoneyFormat;
import elo.mainplugins.hud.font.PixelSpacer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Górna tabela graczy (Tab). Odświeżana raz na sekundę dla wszystkich online -
 * dane wspólne (topki, TPS, rotujący tip) liczone RAZ na cykl, nie osobno dla
 * każdego gracza, żeby nie sortować topki N razy przy N graczach online.
 */
public class TablistManager implements Listener {

    private final Plugin plugin;
    private final EconomyService economyManager;
    private BukkitTask updateTask;
    private int tick = 0;

    /**
     * Gracze BEZ zaakceptowanego resource packa mainplugins:spacer (patrz resourcepack/
     * w module) - dostają stary fallback (dopelnij, dopełnianie znakami), bo font
     * mainplugins:spacer po prostu u nich nie istnieje (PixelSpacer wyświetliłby im
     * kwadraciki zamiast pustych dystansów). Domyślnie (przed odpowiedzią klienta)
     * gracz jest tutaj - bezpieczny fallback, dopóki nie potwierdzi ACCEPTED/
     * SUCCESSFULLY_LOADED.
     */
    private final Set<UUID> bezPacka = new HashSet<>();

    /** Puste url = paczka wyłączona - patrz resourcepack.yml i wczytajResourcePack(). */
    private String packUrl;
    private String packHash;
    private boolean packWymagany;

    private static final String NAZWA_SERWERA = "SERWER SURVIVAL / SKYBLOCK";

    private static final String[] ROTUJACE_PODPOWIEDZI = {
            "Komendy: /sklep | /targ | /is | /quest",
            "Komendy: /itemy | /narzedzia | /sell | /sellall",
            "Wpisz /menu, aby otworzyć panel główny!",
            "Handluj z graczami na /targ!",
            "Powiększ swoją wyspę przez /is!"
    };

    public TablistManager(Plugin plugin, EconomyService economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        wczytajResourcePack();
        startTablistUpdater();
    }

    /** Wczytuje resourcepack.yml (patrz plik dla opisu kluczy) - kopiowany 1:1 przy pierwszym uruchomieniu, żeby nie nadpisywać ręcznych zmian admina. */
    private void wczytajResourcePack() {
        File plik = new File(plugin.getDataFolder(), "resourcepack.yml");
        if (!plik.exists()) {
            plugin.saveResource("resourcepack.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);
        packUrl = cfg.getString("url", "");
        packHash = cfg.getString("hash", "");
        packWymagany = cfg.getBoolean("wymagany", false);
    }

    private void startTablistUpdater() {
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::odswiezWszystkim, 0L, 20L);
    }

    public void wyczyscZadania() {
        if (updateTask != null) {
            updateTask.cancel();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        bezPacka.add(player.getUniqueId());
        if (!packUrl.isBlank()) {
            player.setResourcePack(packUrl, packHash, packWymagany);
        }
        aktualizujTabliste(player, pobierzTopGraczy(), pobierzTopWysp(), pobierzTps(), aktualnaPodpowiedz());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bezPacka.remove(event.getPlayer().getUniqueId());
    }

    /** Aktualizuje status paczki gracza - patrz komentarz przy polu bezPacka. */
    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> bezPacka.remove(event.getPlayer().getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> bezPacka.add(event.getPlayer().getUniqueId());
            default -> { /* ACCEPTED - jeszcze się pobiera, poczekaj na kolejny status */ }
        }
    }

    private void odswiezWszystkim() {
        tick++;
        List<TopGracz> topGracze = pobierzTopGraczy();
        List<IslandSummary> topWyspy = pobierzTopWysp();
        double tps = pobierzTps();
        String podpowiedz = aktualnaPodpowiedz();

        for (Player player : Bukkit.getOnlinePlayers()) {
            aktualizujTabliste(player, topGracze, topWyspy, tps, podpowiedz);
        }
    }

    private static final int MAX_TOP = 10;

    private List<TopGracz> pobierzTopGraczy() {
        return HudData.pobierzTopGraczy(economyManager, MAX_TOP);
    }

    private List<IslandSummary> pobierzTopWysp() {
        return HudData.pobierzTopWysp(MAX_TOP);
    }

    private double pobierzTps() {
        double tps = Bukkit.getTPS()[0];
        return Math.min(tps, 20.0);
    }

    private String aktualnaPodpowiedz() {
        int indeks = (tick / 5) % ROTUJACE_PODPOWIEDZI.length;
        return ROTUJACE_PODPOWIEDZI[indeks];
    }

    private void aktualizujTabliste(Player player, List<TopGracz> topGracze, List<IslandSummary> topWyspy, double tps, String podpowiedz) {
        player.sendPlayerListHeaderAndFooter(
                stworzNaglowek(tps),
                stworzStopke(player, topGracze, topWyspy, podpowiedz)
        );
    }

    private Component stworzNaglowek(double tps) {
        int onlineCount = Bukkit.getOnlinePlayers().size();
        int maxSlots = Bukkit.getMaxPlayers();

        return Component.text("■ ", NamedTextColor.DARK_GRAY)
                .append(Component.text(NAZWA_SERWERA, NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" ■\n", NamedTextColor.DARK_GRAY))
                .append(Component.text("Gracze online: ", NamedTextColor.GRAY))
                .append(Component.text(onlineCount + "/" + maxSlots, NamedTextColor.GREEN))
                .append(Component.text("  │  ", NamedTextColor.DARK_GRAY))
                .append(Component.text("TPS: ", NamedTextColor.GRAY))
                .append(Component.text(String.format("%.1f", tps), kolorTps(tps)))
                .append(Component.text("\n"));
    }

    private NamedTextColor kolorTps(double tps) {
        if (tps >= 18.0) return NamedTextColor.GREEN;
        if (tps >= 15.0) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    // Szerokości pierwszych dwóch kolumn - trzecia (Twoje Statystyki) jest ostatnia, więc
    // nie musi być dopełniana. DWIE wersje: w ZNAKACH (stary fallback, dopelnij()) i w
    // PIKSELACH (PixelSpacer, dla graczy z zaakceptowanym resource packem mainplugins:
    // spacer - patrz pole bezPacka). Czcionka Minecrafta NIE jest monospace (litery mają
    // różną szerokość), więc dopełnianie po liczbie ZNAKÓW nigdy nie wyjdzie idealnie
    // równo - stąd w ogóle ta cała para wersji zamiast jednej.
    private static final int SZEROKOSC_KOLUMNY_GRACZY = 26;
    private static final int SZEROKOSC_KOLUMNY_WYSP = 22;
    private static final int SZEROKOSC_KOLUMNY_GRACZY_PX = 26 * 6;
    private static final int SZEROKOSC_KOLUMNY_WYSP_PX = 22 * 6;

    /** 3 kolumny obok siebie: Top Gracze | Top Wyspy | Twoje Statystyki - budowane wiersz po wierszu. */
    private Component stworzStopke(Player player, List<TopGracz> topGracze, List<IslandSummary> topWyspy, String podpowiedz) {
        double balance = economyManager.getKasa(player.getUniqueId());
        IslandSummary wlasnaWyspa = HudData.pobierzWlasnaWyspe(player.getUniqueId());

        List<String> kolGracze = new ArrayList<>();
        kolGracze.add("★ Top Gracze ★");
        for (int i = 0; i < MAX_TOP; i++) {
            kolGracze.add(i < topGracze.size()
                    ? (i + 1) + ". " + topGracze.get(i).nick() + " - " + MoneyFormat.kompaktowo(topGracze.get(i).kasa()) + " $"
                    : "");
        }

        List<String> kolWyspy = new ArrayList<>();
        kolWyspy.add("★ Top Wyspy ★");
        for (int i = 0; i < MAX_TOP; i++) {
            kolWyspy.add(i < topWyspy.size()
                    ? (i + 1) + ". " + topWyspy.get(i).ownerName() + " - " + topWyspy.get(i).borderSize() + " bl."
                    : "");
        }

        List<String> kolTy = new ArrayList<>();
        kolTy.add("★ Twoje Statystyki ★");
        kolTy.add("Nick: " + player.getName());
        kolTy.add("Portfel: " + MoneyFormat.kompaktowo(balance) + " $");
        kolTy.add(wlasnaWyspa != null
                ? "Wyspa: " + wlasnaWyspa.borderSize() + " bl. (" + wlasnaWyspa.memberCount() + " członków)"
                : "Wyspa: Brak (wpisz /is)");

        int wiersze = Math.max(kolGracze.size(), Math.max(kolWyspy.size(), kolTy.size()));
        boolean pixelPerfect = !bezPacka.contains(player.getUniqueId());

        Component wynik = Component.text("\n");
        for (int i = 0; i < wiersze; i++) {
            boolean naglowek = (i == 0);
            String a = i < kolGracze.size() ? kolGracze.get(i) : "";
            String b = i < kolWyspy.size() ? kolWyspy.get(i) : "";
            String c = i < kolTy.size() ? kolTy.get(i) : "";

            wynik = wynik
                    .append(komorkaDopelniona(a, NamedTextColor.GREEN, naglowek, SZEROKOSC_KOLUMNY_GRACZY_PX, SZEROKOSC_KOLUMNY_GRACZY, pixelPerfect))
                    .append(komorkaDopelniona(b, NamedTextColor.GOLD, naglowek, SZEROKOSC_KOLUMNY_WYSP_PX, SZEROKOSC_KOLUMNY_WYSP, pixelPerfect))
                    .append(komorka(c, NamedTextColor.AQUA, naglowek))
                    .append(Component.text("\n"));
        }

        return wynik
                .append(Component.text("▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬\n", NamedTextColor.DARK_GRAY))
                .append(Component.text(podpowiedz, NamedTextColor.DARK_AQUA));
    }

    private Component komorka(String tekst, NamedTextColor kolor, boolean naglowek) {
        return Component.text(tekst, kolor).decoration(TextDecoration.BOLD, naglowek);
    }

    /**
     * Komórka kolumny wyrównanej do stałej szerokości - dwie ścieżki: PIKSEL-PERFECT
     * (PixelSpacer, tylko gracze z zaakceptowanym resource packem) albo stary fallback
     * (dopelnij, dopełnianie znakami) dla reszty. Patrz pole bezPacka.
     */
    private Component komorkaDopelniona(String tekst, NamedTextColor kolor, boolean naglowek, int szerokoscPx, int szerokoscZnakow, boolean pixelPerfect) {
        if (pixelPerfect) {
            Component base = Component.text(tekst, kolor).decoration(TextDecoration.BOLD, naglowek);
            return PixelSpacer.padTo(base, tekst, szerokoscPx);
        }
        return komorka(dopelnij(tekst, szerokoscZnakow), kolor, naglowek);
    }

    /** Dopełnia spacjami do stałej liczby znaków (albo przycina, jeśli tekst jest dłuższy) - stary fallback, patrz komorkaDopelniona. */
    private String dopelnij(String tekst, int szerokosc) {
        if (tekst.length() >= szerokosc) return tekst.substring(0, szerokosc);
        return tekst + " ".repeat(szerokosc - tekst.length());
    }
}