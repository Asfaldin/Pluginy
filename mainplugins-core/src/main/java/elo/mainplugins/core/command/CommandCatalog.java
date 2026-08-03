package elo.mainplugins.core.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.TreeMap;

/**
 * Buduje listę wszystkich komend zarejestrowanych przez pluginy z rodziny
 * Mainplugins, pogrupowaną per plugin (segment) - używane przez /komendy
 * (publiczne) i /adminhelp (dla operatora, z dodatkowymi informacjami).
 *
 * Segmentacja "za darmo": każdy z naszych 10 pluginów już z natury reprezentuje
 * osobną kategorię (Sklep, Wyspa, Targ...), więc nie trzeba niczego ręcznie
 * kategoryzować - wystarczy pogrupować komendy po pluginie, który je zarejestrował.
 * Dane komend (nazwa + opis) biorą się wprost z plugin.yml każdego pluginu
 * (PluginDescriptionFile#getCommands()), więc nowy plugin/nowa komenda pojawia
 * się tu automatycznie, bez ręcznego dopisywania.
 */
final class CommandCatalog {

    private CommandCatalog() {}

    static void wypisz(CommandSender sender) {
        // TreeMap - stałe, alfabetyczne uporządkowanie segmentów między wywołaniami
        Map<String, Plugin> segmenty = new TreeMap<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getName().startsWith("Mainplugins")) {
                segmenty.put(plugin.getName(), plugin);
            }
        }

        for (Plugin plugin : segmenty.values()) {
            Map<String, Map<String, Object>> komendy = plugin.getDescription().getCommands();
            if (komendy == null || komendy.isEmpty()) continue;

            String segment = plugin.getName().replaceFirst("^Mainplugins", "");
            sender.sendMessage(Component.text("▸ " + segment, NamedTextColor.GOLD, TextDecoration.BOLD));

            for (Map.Entry<String, Map<String, Object>> wpis : new TreeMap<>(komendy).entrySet()) {
                Object opis = wpis.getValue() != null ? wpis.getValue().get("description") : null;
                Component linia = Component.text("  /" + wpis.getKey(), NamedTextColor.YELLOW);
                if (opis != null) {
                    linia = linia.append(Component.text(" - " + opis, NamedTextColor.GRAY));
                }
                sender.sendMessage(linia);
            }
        }
    }
}