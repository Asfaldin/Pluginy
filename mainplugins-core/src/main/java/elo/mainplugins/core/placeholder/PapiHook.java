package elo.mainplugins.core.placeholder;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * JEDYNE miejsce z klasami PlaceholderAPI - ładowane tylko, gdy PlaceholderAPI jest
 * włączony (patrz PlaceholderManager), więc bez niego core nie dostaje NoClassDefFoundError.
 */
final class PapiHook {

    private PapiHook() {}

    static void registerExpansion(Plugin core, PlaceholderRegistry<Plugin> registry) {
        new PlaceholderExpansion() {
            @Override
            public String getIdentifier() {
                return "mainplugins";
            }

            @Override
            public String getAuthor() {
                return "Mainplugins";
            }

            @Override
            public String getVersion() {
                return core.getPluginMeta().getVersion();
            }

            @Override
            public boolean persist() {
                return true;
            }

            @Override
            public String onRequest(OfflinePlayer player, String params) {
                return registry.resolve(player, params.toLowerCase(Locale.ROOT));
            }
        }.register();
    }

    static String setPlaceholders(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
