package elo.mainplugins.core.api;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Map;

/**
 * Wspólne tłumaczenia wszystkich pluginów Mainplugins. Każdy plugin trzyma napisy w
 * swoim jarze w lang/en.yml i lang/pl.yml; przy starcie woła {@link #registerDefaults(Plugin)},
 * co kopiuje brakujące pliki do plugins/<Plugin>/lang/. Język serwera = "language" w
 * config.yml core. Brak napisu w wybranym języku -> angielski -> sam klucz (+ ostrzeżenie).
 * Kolory "&", placeholdery w klamrach: {player}, {amount}.
 */
public interface LangService {

    /** Wołać w onEnable pluginu, zanim użyje msg/send. */
    void registerDefaults(Plugin owner);

    /** Aktualny kod języka serwera, np. "en". */
    String language();

    Component msg(Plugin owner, String key, Map<String, String> placeholders);

    default Component msg(Plugin owner, String key) {
        return msg(owner, key, Map.of());
    }

    void send(CommandSender to, Plugin owner, String key, Map<String, String> placeholders);

    default void send(CommandSender to, Plugin owner, String key) {
        send(to, owner, key, Map.of());
    }

    /** Czyta na nowo język z config.yml core i pliki językowe wszystkich zarejestrowanych pluginów. */
    void reload();
}
