package elo.mainplugins.core;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * Wysyła graczom przy wejściu wspólny resourcepack Mainplugins (custom tekstury itemów,
 * np. Ewoluujący Kilof z mainplugins-tools - patrz LevelableToolsManager). Konfiguracja
 * (url/hash/wymagany) w resourcepack.yml, kopiowanym 1:1 przy pierwszym uruchomieniu,
 * żeby nie nadpisywać ręcznych zmian admina - patrz komentarz w pliku dla instrukcji
 * wgrywania paczki (źródło: mainplugins-core/resourcepack/, hosting: np. mc-packs.net).
 */
public class ResourcePackManager implements Listener {

    private final String url;
    private final String hash;
    private final boolean wymagany;

    public ResourcePackManager(Plugin plugin) {
        File plik = new File(plugin.getDataFolder(), "resourcepack.yml");
        if (!plik.exists()) {
            plugin.saveResource("resourcepack.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);
        this.url = cfg.getString("url", "");
        this.hash = cfg.getString("hash", "");
        this.wymagany = cfg.getBoolean("wymagany", false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!url.isBlank()) {
            event.getPlayer().setResourcePack(url, hash, wymagany);
        }
    }
}