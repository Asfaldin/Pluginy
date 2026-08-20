package elo.mainplugins.core.util;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Buforowany zapis pliku konfiguracyjnego: zamiast zapisywać przy każdej zmianie,
 * manager woła {@link #oznaczZmiane()}, a faktyczny zrzut na dysk dzieje się
 * cyklicznie i asynchronicznie.
 *
 * Jak używać:
 * <pre>
 *   saver = new AsyncConfigSaver(plugin, config, plik, 30);  // co 30 sekund
 *   ...
 *   config.set(klucz, wartosc);
 *   saver.oznaczZmiane();          // zamiast config.save(plik)
 *   ...
 *   saver.zamknij();               // w onDisable() - zapis natychmiastowy
 * </pre>
 */
public class AsyncConfigSaver {

    private final Plugin plugin;
    private final FileConfiguration config;
    private final File plik;
    private final AtomicBoolean brudny = new AtomicBoolean(false);
    private final BukkitTask zadanie;

    public AsyncConfigSaver(Plugin plugin, FileConfiguration config, File plik, int cyklSekund) {
        this.plugin = plugin;
        this.config = config;
        this.plik = plik;
        long ticki = cyklSekund * 20L;
        this.zadanie = Bukkit.getScheduler().runTaskTimer(plugin, this::zrzucJesliBrudny, ticki, ticki);
    }

    /** Woła manager po każdej zmianie danych. Tanie — ustawia tylko flagę. */
    public void oznaczZmiane() {
        brudny.set(true);
    }

    /** Cykliczne sprawdzenie: jeśli od ostatniego zrzutu coś się zmieniło, zapisz. */
    private void zrzucJesliBrudny() {
        if (!brudny.compareAndSet(true, false)) return;   // nic się nie zmieniło
        zapiszAsynchronicznie();
    }

    /**
     * Serializacja na głównym wątku (config nie jest thread-safe), sam zapis
     * na dysk asynchronicznie.
     */
    private void zapiszAsynchronicznie() {
        final String tresc = config.saveToString();   // GŁÓWNY WĄTEK
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> zapiszNaDysk(tresc));
    }

    /**
     * Zapis przez plik tymczasowy i podmianę. Gdyby serwer padł w trakcie
     * zwykłego zapisu, plik zostałby uszkodzony w połowie — a to plik z kasą
     * wszystkich graczy. Przy podmianie albo mamy stary, albo nowy, nigdy połowę.
     */
    private void zapiszNaDysk(String tresc) {
        try {
            Path docelowy = plik.toPath();
            Path tymczasowy = docelowy.resolveSibling(plik.getName() + ".tmp");
            Files.writeString(tymczasowy, tresc, StandardCharsets.UTF_8);
            Files.move(tymczasowy, docelowy, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie mozna zapisac " + plik.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Zatrzymuje cykl i zapisuje NATYCHMIAST, synchronicznie.
     *
     * Synchronicznie, bo przy wyłączaniu serwera scheduler już nie działa —
     * zadanie asynchroniczne nigdy by się nie wykonało i ostatnie transakcje
     * przepadłyby. To jedyne miejsce, gdzie blokowanie wątku jest w porządku,
     * bo serwer i tak się właśnie zamyka.
     */
    public void zamknij() {
        zadanie.cancel();
        if (brudny.getAndSet(false)) {
            zapiszNaDysk(config.saveToString());
        }
    }

    /** Wymuszony zapis poza cyklem — np. po operacji administracyjnej. */
    public void zapiszTeraz() {
        brudny.set(false);
        zapiszAsynchronicznie();
    }
}
