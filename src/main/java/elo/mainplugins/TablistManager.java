package elo.mainplugins;

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

public class TablistManager implements Listener {

    private final Plugin plugin;
    private final EconomyManager economyManager;
    private BukkitTask updateTask;

    public TablistManager(Plugin plugin, EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        startTablistUpdater();
    }

    private void startTablistUpdater() {
        updateTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                aktualizujTabliste(player);
            }
        }, 0L, 20L);
    }

    public void wyczyscZadania() {
        if (updateTask != null) {
            updateTask.cancel();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        aktualizujTabliste(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {}

    private void aktualizujTabliste(Player player) {
        Component header = stworzNaglowek();
        Component footer = stworzStopke(player);

        player.sendPlayerListHeaderAndFooter(header, footer);
    }

    private Component stworzNaglowek() {
        int onlineCount = Bukkit.getOnlinePlayers().size();
        int maxSlots = Bukkit.getMaxPlayers();

        return Component.text("■ ", NamedTextColor.DARK_GRAY)
                .append(Component.text("SERWER SURVIVAL / SKYBLOCK", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text(" ■\n", NamedTextColor.DARK_GRAY))
                .append(Component.text("Gracze online: ", NamedTextColor.GRAY))
                .append(Component.text(onlineCount + "/" + maxSlots, NamedTextColor.GREEN))
                .append(Component.text("\n"));
    }

    private Component stworzStopke(Player player) {
        // Pobieramy prawdziwe pieniądze bezpośrednio metodą getKasa z Twojego EconomyManager
        double balance = economyManager.getKasa(player.getUniqueId());

        return Component.text("\n")
                .append(Component.text("   TWOJE STATYSTYKI      ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("│", NamedTextColor.DARK_GRAY))
                .append(Component.text("      TOPKA SERWERA   \n", NamedTextColor.GREEN, TextDecoration.BOLD))

                .append(Component.text(" Nick: " + player.getName() + "       ", NamedTextColor.GRAY))
                .append(Component.text("│", NamedTextColor.DARK_GRAY))
                .append(Component.text(" 1. [Top Island] - 15k", NamedTextColor.WHITE))
                .append(Component.text("\n"))

                .append(Component.text(" Portfel: " + balance + " $    ", NamedTextColor.AQUA))
                .append(Component.text("│", NamedTextColor.DARK_GRAY))
                .append(Component.text(" 2. [Top Island] - 10k", NamedTextColor.WHITE))
                .append(Component.text("\n"))

                .append(Component.text(" Wyspa: Brak             ", NamedTextColor.GRAY))
                .append(Component.text("│", NamedTextColor.DARK_GRAY))
                .append(Component.text(" 3. [Top Island] - 5k ", NamedTextColor.WHITE))
                .append(Component.text("\n"))

                .append(Component.text(" Poziom: 0 lvl           ", NamedTextColor.GRAY))
                .append(Component.text("│", NamedTextColor.DARK_GRAY))
                .append(Component.text(" Ping: " + player.getPing() + " ms       ", NamedTextColor.YELLOW))
                .append(Component.text("\n"))

                .append(Component.text("────────────────────────────────────────\n", NamedTextColor.DARK_GRAY))
                .append(Component.text("Komendy: /sklep  |  /targ  |  /help  |  /itemy", NamedTextColor.DARK_AQUA));
    }
}