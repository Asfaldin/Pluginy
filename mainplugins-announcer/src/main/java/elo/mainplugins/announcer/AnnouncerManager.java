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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cykliczne ogłoszenia na czacie, w pełni sterowane z ogloszenia.yml (bez
 * restartu serwera - patrz {@link #przeladuj()}). Kolory przez zwykłe kody "&"
 * (np. "&aTekst"), bo to najprostszy do edycji w YAML-u format dla kogoś, kto
 * nie zna Adventure/MiniMessage.
 *
 * Każda wiadomość ma WŁASNY, niezależny harmonogram (osobny BukkitTask,
 * patrz uruchomCykl) - nie jest to już jedna wspólna pętla round-robin z
 * jednym globalnym interwałem. "interval-seconds" na najwyższym poziomie
 * pliku to tylko DOMYŚLNY interwał dla wiadomości, które nie ustawiły
 * własnego "interval" - patrz wczytajWiadomosci/OgloszenieWpis. Wiadomości ze
 * swoim interwałem mogą więc nachodzić na siebie w czacie, jeśli ich terminy
 * akurat się zbiegną - to świadomy kompromis za prostotę (każda wiadomość
 * faktycznie pokazuje się z deklarowaną częstotliwością, niezależnie od reszty).
 */
public class AnnouncerManager {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    /** Pojedynczy wpis z listy "messages" - interwalSekund=null oznacza "użyj domyślnego interval-seconds". */
    private record OgloszenieWpis(String text, Integer interwalSekund) {}

    private final Plugin plugin;
    private final File plikOgloszen;
    private FileConfiguration configOgloszen;
    private final List<BukkitTask> zadania = new ArrayList<>();

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
                    wpis("&aWitaj na serwerze! Wpisz &e/menu&a, aby zobaczyć panel gracza.", null),
                    wpis("&bOdwiedź &e/sklep &bi &e/targ&b, aby kupować i sprzedawać przedmioty!", 600),
                    wpis("&6Nie masz jeszcze wyspy? Wpisz &e/is&6, aby ją założyć!", null),
                    wpis("&dZaproś znajomych na serwer!", 900)
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

    /** Buduje jeden wpis "messages" do zapisu w YAML - pomija klucz "interval", jeśli null (wiadomość korzysta z domyślnego). */
    private Map<String, Object> wpis(String text, Integer interwalSekund) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        mapa.put("text", text);
        if (interwalSekund != null) mapa.put("interval", interwalSekund);
        return mapa;
    }

    /**
     * Czyta "messages" tolerując dwa formaty: nowy (mapa z "text"/opcjonalnym
     * "interval") i stary (goły String, sprzed wprowadzenia interwałów per
     * wiadomość) - żeby ręcznie edytowany, "stary" ogloszenia.yml nie wywalił
     * się na starcie, tylko po cichu potraktował każdy wpis jako "domyślny interwał".
     */
    private List<OgloszenieWpis> wczytajWiadomosci() {
        List<OgloszenieWpis> wynik = new ArrayList<>();
        for (Object o : configOgloszen.getList("messages", List.of())) {
            if (o instanceof String tekst) {
                wynik.add(new OgloszenieWpis(tekst, null));
            } else if (o instanceof Map<?, ?> mapa) {
                Object text = mapa.get("text");
                if (text == null) continue;
                Integer interwal = null;
                Object iv = mapa.get("interval");
                if (iv instanceof Number n) interwal = n.intValue();
                wynik.add(new OgloszenieWpis(text.toString(), interwal));
            }
        }
        return wynik;
    }

    /** Wczytuje ogloszenia.yml od nowa i restartuje harmonogram (np. jeśli zmienił się interwał) - pod komendę /reloadannouncer. */
    public void przeladuj() {
        configOgloszen = YamlConfiguration.loadConfiguration(plikOgloszen);
        uruchomCykl();
    }

    private void uruchomCykl() {
        zatrzymaj();
        long domyslneTicks = Math.max(20L, configOgloszen.getLong("interval-seconds", 300) * 20L);

        for (OgloszenieWpis w : wczytajWiadomosci()) {
            long ticks = w.interwalSekund() != null ? Math.max(20L, w.interwalSekund() * 20L) : domyslneTicks;
            zadania.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> wyslij(w.text()), ticks, ticks));
        }
    }

    private void wyslij(String tekst) {
        Component wiadomosc = SERIALIZER.deserialize(tekst);
        Bukkit.broadcast(wiadomosc);
    }

    public void zatrzymaj() {
        for (BukkitTask task : zadania) task.cancel();
        zadania.clear();
    }
}