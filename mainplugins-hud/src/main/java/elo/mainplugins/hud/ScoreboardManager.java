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
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Panel po prawej stronie ekranu. Wcześniej 2 z 8 linii były czystymi
 * wypełniaczami (gołe kody koloru bez treści) a jedna pokazywała zawsze
 * to samo, zmyślone "Poziom Wyspy: 1" - teraz wszystkie 8 linii niesie
 * realną informację (własna wyspa, aktualny lider topki graczy/wysp, TPS).
 *
 * Przy okazji: linie budowane są teraz właściwym API Adventure (NamedTextColor)
 * zamiast wklejania surowych kodów "§" prosto do Component.text(...) - to
 * ostatnie nie jest w ogóle interpretowane jako kolor przez nowe API drużyn
 * (Team#prefix(Component)), więc dawało w praktyce widoczne, zepsute znaki
 * zamiast kolorowania.
 */
public class ScoreboardManager implements Listener {

    private final EconomyService ekonomia;
    private final BukkitTask updateTask;

    public ScoreboardManager(Plugin plugin, EconomyService ekonomia) {
        this.ekonomia = ekonomia;
        this.updateTask = Bukkit.getScheduler().runTaskTimer(plugin, this::odswiezWszystkim, 0L, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        stworzPanel(event.getPlayer());
    }

    private void stworzPanel(Player player) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("sb", "dummy", Component.text(" ✦ SKYBLOCK ✦ ", NamedTextColor.AQUA, TextDecoration.BOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Użycie refleksji - ukryje cyfry na silnikach 1.20.3+, a na starszych nie wywali błędu!
        ukryjCyfry(obj);

        // Rejestracja 8 linii (Zespołów) dla płynnego odświeżania bez migania.
        // "§1".."§8" to tylko techniczne, niewidoczne ID wpisu - o wyświetlanej
        // treści decyduje wyłącznie team.prefix(...) ustawiany w odswiezWszystkim().
        for (int i = 1; i <= 8; i++) {
            Team team = board.registerNewTeam("linia" + i);
            String entry = "§" + i;
            team.addEntry(entry);
            obj.getScore(entry).setScore(i); // Kolejność od dołu (1) do góry (8)
        }

        player.setScoreboard(board);
    }

    private void ukryjCyfry(Objective obj) {
        try {
            // Próba ukrycia dla API Paper/Purpur (1.20.3+)
            Class<?> formatClass = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
            Object blankFormat = formatClass.getMethod("blank").invoke(null);
            obj.getClass().getMethod("numberFormat", formatClass).invoke(obj, blankFormat);
        } catch (Throwable e1) {
            try {
                // Próba ukrycia dla standardowego API Spigot (1.20.3+)
                Class<?> formatClass = Class.forName("org.bukkit.scoreboard.NumberFormat");
                Object blankFormat = formatClass.getMethod("blank").invoke(null);
                try {
                    obj.getClass().getMethod("numberFormat", formatClass).invoke(obj, blankFormat);
                } catch (Throwable e2) {
                    obj.getClass().getMethod("setNumberFormat", formatClass).invoke(obj, blankFormat);
                }
            } catch (Throwable e3) {
                // Jeśli serwer fizycznie działa na np. 1.20.1 lub 1.20.2, czerwone cyfry muszą zostać.
            }
        }
    }

    private void odswiezWszystkim() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) return;

        // Wspólne dane liczone raz na cykl (nie osobno dla każdego gracza)
        List<TopGracz> topGracze = HudData.pobierzTopGraczy(ekonomia, 1);
        List<IslandSummary> topWyspy = HudData.pobierzTopWysp(1);
        TopGracz lider = topGracze.isEmpty() ? null : topGracze.get(0);
        IslandSummary najwiekszaWyspa = topWyspy.isEmpty() ? null : topWyspy.get(0);
        double tps = Math.min(Bukkit.getTPS()[0], 20.0);
        int onlineCount = online.size();
        int maxSlots = Bukkit.getMaxPlayers();

        for (Player p : online) {
            Scoreboard board = p.getScoreboard();
            if (board.getObjective("sb") == null) continue;

            double kasa = ekonomia.getKasa(p.getUniqueId());
            IslandSummary wlasnaWyspa = HudData.pobierzWlasnaWyspe(p.getUniqueId());

            ustawLinie(board, "linia8", etykieta("Gracz", p.getName(), NamedTextColor.AQUA));
            ustawLinie(board, "linia7", etykieta("Kasa", formatKasa(kasa) + "$", NamedTextColor.GOLD));
            ustawLinie(board, "linia6", etykieta("Wyspa", wlasnaWyspa != null
                    ? wlasnaWyspa.borderSize() + " bl."
                    : "Brak", NamedTextColor.GREEN));

            ustawLinie(board, "linia5", Component.text("▬▬▬▬▬▬▬▬▬▬▬▬▬", NamedTextColor.DARK_GRAY));

            ustawLinie(board, "linia4", etykieta("Lider", lider != null ? lider.nick() + " " + formatKasa(lider.kasa()) + "$" : "brak", NamedTextColor.YELLOW));
            ustawLinie(board, "linia3", etykieta("Wyspa#1", najwiekszaWyspa != null ? najwiekszaWyspa.ownerName() + " " + najwiekszaWyspa.borderSize() + "bl." : "brak", NamedTextColor.YELLOW));

            ustawLinie(board, "linia2", etykieta("Online", onlineCount + "/" + maxSlots, NamedTextColor.WHITE));
            ustawLinie(board, "linia1", etykieta("Ping", p.getPing() + "ms", NamedTextColor.GRAY)
                    .append(Component.text(" TPS:", NamedTextColor.WHITE))
                    .append(Component.text(String.format(Locale.US, "%.1f", tps), kolorTps(tps))));
        }
    }

    private Component etykieta(String etykieta, String wartosc, NamedTextColor kolorWartosci) {
        return Component.text(etykieta + ": ", NamedTextColor.WHITE)
                .append(Component.text(wartosc, kolorWartosci));
    }

    private NamedTextColor kolorTps(double tps) {
        if (tps >= 18.0) return NamedTextColor.GREEN;
        if (tps >= 15.0) return NamedTextColor.YELLOW;
        return NamedTextColor.RED;
    }

    private String formatKasa(double kasa) {
        return String.format(Locale.US, "%,.0f", kasa);
    }

    private void ustawLinie(Scoreboard board, String teamName, Component tekst) {
        Team team = board.getTeam(teamName);
        if (team != null) {
            team.prefix(tekst);
        }
    }

    public void wyczysc() {
        if (updateTask != null) updateTask.cancel();
    }
}