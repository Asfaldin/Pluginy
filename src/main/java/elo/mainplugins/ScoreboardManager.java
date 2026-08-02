package elo.mainplugins;

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

public class ScoreboardManager implements Listener {

    private final EconomyManager ekonomia;
    private final BukkitTask updateTask;

    public ScoreboardManager(Plugin plugin, EconomyManager ekonomia) {
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

        // Rejestracja 8 linii (Zespołów) dla płynnego odświeżania bez migania
        for (int i = 1; i <= 8; i++) {
            Team team = board.registerNewTeam("linia" + i);
            String entry = "§" + i; // Niewidzialny znak jako ID linii
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
        for (Player p : Bukkit.getOnlinePlayers()) {
            Scoreboard board = p.getScoreboard();
            if (board.getObjective("sb") == null) continue;

            double kasa = ekonomia.getKasa(p.getUniqueId());

            // Aktualizacja poszczególnych linii
            ustawLinie(board, "linia8", "§1");
            ustawLinie(board, "linia7", "§fGracz: §b" + p.getName());
            ustawLinie(board, "linia6", "§fGotówka: §6" + String.format("%.2f", kasa) + " $");
            ustawLinie(board, "linia5", "§fPoziom Wyspy: §a1");
            ustawLinie(board, "linia4", "§2");
            ustawLinie(board, "linia3", "§fPing: §7" + p.getPing() + " ms");
            ustawLinie(board, "linia2", "§fOnline: §e" + Bukkit.getOnlinePlayers().size());
            ustawLinie(board, "linia1", "§dwww.twojserwer.pl");
        }
    }

    private void ustawLinie(Scoreboard board, String teamName, String tekst) {
        Team team = board.getTeam(teamName);
        if (team != null) {
            team.prefix(Component.text(tekst));
        }
    }

    public void wyczysc() {
        if (updateTask != null) updateTask.cancel();
    }
}