package elo.mainplugins.ranks;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.Rank;
import elo.mainplugins.core.api.RankService;
import elo.mainplugins.core.api.TytulService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Przechowuje rangę każdego gracza (ranks.yml, uuid -> "VIP"/"ADMIN" - GRACZ to
 * domyślna wartość, celowo NIEZAPISYWANA, żeby plik nie puchł wpisami "GRACZ" dla
 * każdego, kto kiedykolwiek dołączył). Admin = realny op Bukkita (za darmo dziedziczy
 * WSZYSTKIE istniejące węzły "default: op" w całym ekosystemie), VIP = kolorowy tag
 * na czacie i w tabliście, Gracz = brak specjalnych uprawnień (ochrona wysp/obszarów
 * już i tak pilnuje, że gość buduje wyłącznie na własnej wyspie - patrz mainplugins-
 * skyblock/IslandProtectionManager i mainplugins-spawn/ObszarProtectionManager).
 *
 * Op NIGDY nie jest automatycznie ODBIERANY przy dołączeniu do gry - tylko przy
 * jawnej zmianie rangi z ADMIN na coś innego przez /@setranga (patrz setRank). Dzięki
 * temu op nadany ręcznie spoza tego systemu (np. przez /op w konsoli, zanim ten
 * moduł istniał) nigdy nie zostaje po cichu zdjęty tylko dlatego, że gracz nie ma
 * wpisu w ranks.yml - jedyne, co się dzieje przy joinie, to "dogranie" opa graczom
 * zapisanym jako ADMIN, gdyby go z jakiegoś powodu nie mieli (patrz onJoin).
 */
public class RankManager implements RankService, Listener {

    private final Plugin plugin;
    private final File plikRang;
    private final FileConfiguration configRang;
    private final Map<UUID, Rank> cache = new HashMap<>();

    public RankManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikRang = new File(plugin.getDataFolder(), "ranks.yml");
        if (!plikRang.exists()) {
            plikRang.getParentFile().mkdirs();
            try { plikRang.createNewFile(); } catch (IOException ignored) {}
        }
        this.configRang = YamlConfiguration.loadConfiguration(plikRang);
        wczytaj();
    }

    private void wczytaj() {
        for (String key : configRang.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                Rank rank = Rank.valueOf(configRang.getString(key, "GRACZ"));
                if (rank != Rank.GRACZ) cache.put(uuid, rank);
            } catch (IllegalArgumentException ignored) {
                // uszkodzony/ręcznie zepsuty wpis - pomijamy zamiast wywalać cały load
            }
        }
    }

    private void zapisz() {
        try {
            configRang.save(plikRang);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać ranks.yml: " + e.getMessage());
        }
    }

    @Override
    public Rank getRank(UUID uuid) {
        return cache.getOrDefault(uuid, Rank.GRACZ);
    }

    /**
     * Zmienia rangę gracza: aktualizuje ranks.yml, syncuje realny op (nadaje przy
     * wejściu na ADMIN, zabiera przy wyjściu z ADMIN) i - jeśli gracz jest online -
     * od razu odświeża jego tag w tabliście. Zwraca poprzednią rangę.
     */
    public Rank setRank(UUID uuid, Rank nowaRanga, Player online) {
        Rank stara = getRank(uuid);

        if (nowaRanga == Rank.GRACZ) {
            cache.remove(uuid);
            configRang.set(uuid.toString(), null);
        } else {
            cache.put(uuid, nowaRanga);
            configRang.set(uuid.toString(), nowaRanga.name());
        }
        zapisz();

        if (online != null) {
            if (nowaRanga == Rank.ADMIN) {
                online.setOp(true);
            } else if (stara == Rank.ADMIN) {
                online.setOp(false);
            }
            odswiezTabliste(online);
        }

        return stara;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Rank rank = getRank(player.getUniqueId());

        // Samo-naprawa: gracz zapisany jako ADMIN zawsze ma mieć realnego opa, nawet
        // jeśli serwer stracił ops.json albo ranks.yml został ręcznie edytowany.
        // Odwrotnie NIE robimy - patrz komentarz przy klasie.
        if (rank == Rank.ADMIN && !player.isOp()) {
            player.setOp(true);
        }

        odswiezTabliste(player);
    }

    /** Tag przed nickiem w tabliście i na czacie - GRACZ nie ma żadnego. */
    Component prefixDlaRangi(Rank rank) {
        return switch (rank) {
            case ADMIN -> Component.text("[ADMIN] ", NamedTextColor.RED, TextDecoration.BOLD);
            case VIP -> Component.text("[VIP] ", NamedTextColor.GOLD, TextDecoration.BOLD);
            case GRACZ -> Component.empty();
        };
    }

    private NamedTextColor kolorNicku(Rank rank) {
        return switch (rank) {
            case ADMIN -> NamedTextColor.RED;
            case VIP -> NamedTextColor.GOLD;
            case GRACZ -> NamedTextColor.WHITE;
        };
    }

    /**
     * player.playerListName(...) zamiast Team#prefix - działa niezależnie od tego,
     * że każdy gracz ma już WŁASNĄ, indywidualną tablicę wyników od mainplugins-hud
     * (Team z jednego scoreboardu nie byłby widoczny na scoreboardzie innego gracza,
     * a to per-gracza ustawienie widać u KAŻDEGO widza niezależnie od scoreboardu).
     */
    private void odswiezTabliste(Player player) {
        Rank rank = getRank(player.getUniqueId());
        Component nazwa = prefixDlaRangi(rank)
                .append(Component.text(player.getName(), kolorNicku(rank)));
        player.playerListName(nazwa);
    }

    /**
     * Dokleja tag rangi (i, jeśli gracz go ma, tytuł z questów - patrz tytulGracza) przed
     * domyślnym wyglądem wiadomości na czacie - Renderer zamiast ręcznego budowania całej
     * wiadomości, żeby nie psuć klikalności nicku (podpowiedź/sugestia komendy po kliknięciu)
     * ani żadnych innych domyślnych zachowań czatu Vanilla/Paper poza samym dołożeniem tagu
     * z przodu. Oba tagi MUSZĄ się złożyć w jednym event.renderer() - to zwykły setter, nie
     * łańcuch, więc gdyby mainplugins-quests zarejestrował drugi, niezależny listener na
     * AsyncChatEvent, jeden z tagów po prostu by zniknął (kolejność listenerów nie jest
     * gwarantowana między pluginami).
     */
    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Rank rank = getRank(event.getPlayer().getUniqueId());
        Component tytul = tytulGracza(event.getPlayer().getUniqueId());
        if (rank == Rank.GRACZ && tytul == null) return; // nic do dołożenia - zostaw domyślny renderer

        Component prefix = tytul != null ? tytul.append(prefixDlaRangi(rank)) : prefixDlaRangi(rank);
        NamedTextColor kolor = kolorNicku(rank);
        event.renderer((source, sourceDisplayName, message, viewer) ->
                prefix.append(Component.text(source.getName() + ": ", kolor))
                        .append(message));
    }

    /** Opcjonalny tytuł zdobyty w questach (mainplugins-quests) - null, jeśli plugin questów niewgrany albo gracz jeszcze nic nie zdobył. */
    private Component tytulGracza(UUID uuid) {
        TytulService serwis = CoreAPI.getTytulService();
        return serwis != null ? serwis.tytulGracza(uuid) : null;
    }
}
