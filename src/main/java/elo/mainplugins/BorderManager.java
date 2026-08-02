package elo.mainplugins;

import org.bukkit.Bukkit;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

public class BorderManager implements Listener {

    private final Plugin plugin;

    public BorderManager(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        wyczyscCzerwonyEkranBorderu(event.getPlayer());
    }

    public void wyczyscCzerwonyEkranBorderu(Player player) {
        WorldBorder worldBorder = player.getWorld().getWorldBorder();

        // Tworzymy osobną instancję borderu dla gracza, która kopiuje rozmiar świata,
        // ale ma całkowicie wyłączony dystans ostrzegawczy (co usuwa czerwony ekran)
        WorldBorder playerBorder = Bukkit.createWorldBorder();
        playerBorder.setCenter(worldBorder.getCenter());
        playerBorder.setSize(worldBorder.getSize());
        playerBorder.setDamageAmount(worldBorder.getDamageAmount());
        playerBorder.setDamageBuffer(worldBorder.getDamageBuffer());

        // Ustawienie warning na 0 sprawia, że gra nigdy nie włączy czerwonego pulsowania
        playerBorder.setWarningDistance(0);

        player.setWorldBorder(playerBorder);
    }
}