package elo.mainplugins.hud;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.TopGracz;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

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
        startTablistUpdater();
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
        aktualizujTabliste(event.getPlayer(), pobierzTopGraczy(), pobierzTopWysp(), pobierzTps(), aktualnaPodpowiedz());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {}

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

    private List<TopGracz> pobierzTopGraczy() {
        return HudData.pobierzTopGraczy(economyManager, 3);
    }

    private List<IslandSummary> pobierzTopWysp() {
        return HudData.pobierzTopWysp(3);
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

    /**
     * Jedna kolumna, ułożona w sekcje jedna pod drugą - zamiast próby ręcznego
     * wyrównywania spacjami w kilku kolumnach obok siebie. Domyślna czcionka
     * Minecrafta NIE jest monospace (litery mają różną szerokość), więc żadne
     * dopełnianie spacjami nigdy nie wyjdzie naprawdę równo - stąd poprzednia
     * wersja z 3 kolumnami wyglądała "rozlanie". Pojedyncza kolumna nie ma
     * tego problemu, bo nic nie musi się wyrównywać w poziomie.
     */
    private Component stworzStopke(Player player, List<TopGracz> topGracze, List<IslandSummary> topWyspy, String podpowiedz) {
        double balance = economyManager.getKasa(player.getUniqueId());
        IslandSummary wlasnaWyspa = HudData.pobierzWlasnaWyspe(player.getUniqueId());

        Component wynik = Component.text("\n")
                .append(sekcja("★ Top Wyspy ★", NamedTextColor.GOLD));
        for (int i = 0; i < topWyspy.size(); i++) {
            IslandSummary w = topWyspy.get(i);
            wynik = wynik.append(pozycja((i + 1) + ". " + w.ownerName() + " - " + w.borderSize() + " bloków", NamedTextColor.YELLOW));
        }

        wynik = wynik.append(Component.text("\n")).append(sekcja("★ Top Gracze ★", NamedTextColor.GREEN));
        for (int i = 0; i < topGracze.size(); i++) {
            TopGracz g = topGracze.get(i);
            wynik = wynik.append(pozycja((i + 1) + ". " + g.nick() + " - " + formatKasa(g.kasa()) + " $", NamedTextColor.WHITE));
        }

        wynik = wynik.append(Component.text("\n")).append(sekcja("★ Twoje Statystyki ★", NamedTextColor.AQUA));
        wynik = wynik
                .append(pozycja("Nick: " + player.getName(), NamedTextColor.GRAY))
                .append(pozycja("Portfel: " + formatKasa(balance) + " $", NamedTextColor.GRAY))
                .append(pozycja(wlasnaWyspa != null
                        ? "Wyspa: " + wlasnaWyspa.borderSize() + " bloków (" + wlasnaWyspa.memberCount() + " członków)"
                        : "Wyspa: Brak (wpisz /is)", NamedTextColor.GRAY));

        wynik = wynik
                .append(Component.text("\n▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬\n", NamedTextColor.DARK_GRAY))
                .append(Component.text(podpowiedz, NamedTextColor.DARK_AQUA));

        return wynik;
    }

    private Component sekcja(String tytul, NamedTextColor kolor) {
        return Component.text(tytul, kolor, TextDecoration.BOLD).append(Component.text("\n"));
    }

    private Component pozycja(String tekst, NamedTextColor kolor) {
        return Component.text(" " + tekst, kolor).append(Component.text("\n"));
    }

    private String formatKasa(double kasa) {
        return String.format(java.util.Locale.US, "%,.0f", kasa);
    }
}