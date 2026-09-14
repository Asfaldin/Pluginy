package elo.mainplugins.core.api;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.function.BiFunction;

/**
 * Placeholdery %mainplugins_<nazwa>% dla PlaceholderAPI (TAB, scoreboardy innych pluginów).
 * Core wystawia jedną ekspansję "mainplugins"; pluginy dokładają swoje nazwy przez
 * {@link #register} - resolver dostaje (gracz lub null, nazwa) i zwraca wartość albo null,
 * gdy nazwy nie zna. Bez PlaceholderAPI na serwerze wszystko działa, tylko nikt nie pyta.
 */
public interface PlaceholderService {

    /** Wyrejestrowywany automatycznie, gdy plugin się wyłącza. */
    void register(Plugin owner, BiFunction<OfflinePlayer, String, String> resolver);

    /** Wartość naszego placeholdera bez PlaceholderAPI (nazwa bez "mainplugins_"), null gdy nikt jej nie zna. */
    String resolve(OfflinePlayer player, String name);

    /** Podmienia w tekście wszystkie %...% (nasze i innych pluginów). Bez PlaceholderAPI zwraca tekst bez zmian. */
    String apply(Player player, String text);
}
