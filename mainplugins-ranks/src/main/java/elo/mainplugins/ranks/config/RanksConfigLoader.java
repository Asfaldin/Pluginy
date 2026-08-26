package elo.mainplugins.ranks.config;

import elo.mainplugins.core.api.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje ranks-config.yml (wygląd rang) do niemutowalnego {@link RanksConfig}. Ten sam
 * wzorzec co MenuGuiLoader/ChatFilterConfigLoader: plik kopiowany z zasobu TYLKO przy
 * pierwszym uruchomieniu, każda zła/brakująca wartość dostaje warning i pada na sensowny
 * domyślny odpowiednik dawnej hardkodowanej stałej, zamiast crashować cały serwer przy starcie.
 */
public final class RanksConfigLoader {

    private RanksConfigLoader() {}

    public static RanksConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "ranks-config.yml");
        if (!file.exists()) {
            plugin.saveResource("ranks-config.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        Map<Rank, RanksConfig.Wyglad> wyglad = new EnumMap<>(Rank.class);
        for (Rank rank : Rank.values()) {
            wyglad.put(rank, wczytajWyglad(cfg, rank, log));
        }

        log.info("ranks-config.yml: wczytano wyglad " + wyglad.size() + " rang.");
        return new RanksConfig(wyglad);
    }

    private static RanksConfig.Wyglad wczytajWyglad(YamlConfiguration cfg, Rank rank, Logger log) {
        String sciezka = "wygladu." + rank.name();
        RanksConfig.Wyglad domyslny = domyslnyWyglad(rank);

        String prefixRaw = cfg.getString(sciezka + ".prefix");
        Component prefix = prefixRaw != null ? LegacyComponentSerializer.legacyAmpersand().deserialize(prefixRaw) : domyslny.prefix();

        String kolorRaw = cfg.getString(sciezka + ".kolor-nicku");
        NamedTextColor kolor = domyslny.kolorNicku();
        if (kolorRaw != null) {
            NamedTextColor parsed = NamedTextColor.NAMES.value(kolorRaw.toLowerCase());
            if (parsed != null) {
                kolor = parsed;
            } else {
                log.warning("ranks-config.yml: '" + sciezka + ".kolor-nicku' ma zly kolor ('" + kolorRaw + "') - uzywam domyslnego.");
            }
        }

        return new RanksConfig.Wyglad(prefix, kolor);
    }

    /** Odpowiednik dawnych hardkodowanych stałych z RankManager - używane, gdy plik brak/pusty. */
    private static RanksConfig.Wyglad domyslnyWyglad(Rank rank) {
        return switch (rank) {
            case ADMIN -> new RanksConfig.Wyglad(Component.text("[ADMIN] ", NamedTextColor.RED, TextDecoration.BOLD), NamedTextColor.RED);
            case VIP -> new RanksConfig.Wyglad(Component.text("[VIP] ", NamedTextColor.GOLD, TextDecoration.BOLD), NamedTextColor.GOLD);
            case GRACZ -> new RanksConfig.Wyglad(Component.empty(), NamedTextColor.WHITE);
        };
    }
}
