package elo.mainplugins.announcer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Cykliczne ogłoszenia na czacie, w pełni sterowane z ogloszenia.yml (bez
 * restartu serwera - patrz {@link #przeladuj()}). Kolory przez zwykłe kody "&"
 * (np. "&aTekst"), bo to najprostszy do edycji w YAML-u format dla kogoś, kto
 * nie zna Adventure/MiniMessage. Wiadomości wysyłane są po kolei, w pętli.
 */
public class AnnouncerManager {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final File plikOgloszen;
    private FileConfiguration configOgloszen;
    private BukkitTask task;
    private int indeks = 0;

    public AnnouncerManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikOgloszen = new File(plugin.getDataFolder(), "ogloszenia.yml");
        stworzLubWczytaj();
        uruchomCykl();
    }

    private void stworzLubWczytaj() {
        if (!plikOgloszen.exists()) {
            plikOgloszen.getParentFile().mkdirs();
            configOgloszen = new YamlConfiguration();
            configOgloszen.set("interval-seconds", 300);
            configOgloszen.set("messages", List.of(
                    "&aWitaj na serwerze! Wpisz &e/menu&a, aby zobaczyć panel gracza.",
                    "&bOdwiedź &e/sklep &bi &e/targ&b, aby kupować i sprzedawać przedmioty!",
                    "&6Nie masz jeszcze wyspy? Wpisz &e/is&6, aby ją założyć!",
                    "&dZaproś znajomych na serwer!"
            ));
            try {
                configOgloszen.save(plikOgloszen);
            } catch (IOException e) {
                plugin.getLogger().warning("Nie można zapisać ogloszenia.yml: " + e.getMessage());
            }
        } else {
            configOgloszen = YamlConfiguration.loadConfiguration(plikOgloszen);
        }
    }

    /** Wczytuje ogloszenia.yml od nowa i restartuje harmonogram (np. jeśli zmienił się interwał) - pod komendę /reloadannouncer. */
    public void przeladuj() {
        configOgloszen = YamlConfiguration.loadConfiguration(plikOgloszen);
        indeks = 0;
        uruchomCykl();
    }

    private void uruchomCykl() {
        if (task != null) task.cancel();
        long interwalTicks = Math.max(20L, configOgloszen.getLong("interval-seconds", 300) * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::wyslijKolejne, interwalTicks, interwalTicks);
    }

    private void wyslijKolejne() {
        List<String> wiadomosci = configOgloszen.getStringList("messages");
        if (wiadomosci.isEmpty()) return;
        if (indeks >= wiadomosci.size()) indeks = 0;

        Component wiadomosc = SERIALIZER.deserialize(wiadomosci.get(indeks));
        Bukkit.broadcast(wiadomosc);
        indeks++;
    }

    public void zatrzymaj() {
        if (task != null) task.cancel();
    }
}