package elo.mainplugins.chatfilter;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Osobiste wyciszanie graczy - /mute (alias /wycisz) <nick>. Czysto po stronie słuchacza:
 * wyciszony gracz normalnie pisze na czacie, widzą go wszyscy poza osobą, która go
 * wyciszyła. To NIE jest serwerowy mute (dla wszystkich) - każdy decyduje tylko za siebie.
 *
 * ConcurrentHashMap/concurrent Set celowo - AsyncChatEvent leci na osobnym wątku
 * (nazwa nie kłamie), a komenda /mute wykonuje się na głównym wątku serwera. Zwykły
 * HashMap/HashSet byłby tu realnym wyścigiem (race condition) między tymi dwoma wątkami.
 */
public class MuteManager implements Listener {

    private final Plugin plugin;
    private final File plikWyciszen;
    private final FileConfiguration configWyciszen;
    private final Map<UUID, Set<UUID>> wyciszeni = new ConcurrentHashMap<>();

    public MuteManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikWyciszen = new File(plugin.getDataFolder(), "wyciszenia.yml");
        if (!plikWyciszen.exists()) {
            plikWyciszen.getParentFile().mkdirs();
            try { plikWyciszen.createNewFile(); } catch (IOException ignored) {}
        }
        this.configWyciszen = YamlConfiguration.loadConfiguration(plikWyciszen);
        wczytaj();
    }

    private void wczytaj() {
        for (String key : configWyciszen.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }

            Set<UUID> lista = ConcurrentHashMap.newKeySet();
            for (String s : configWyciszen.getStringList(key)) {
                try { lista.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
            }
            if (!lista.isEmpty()) wyciszeni.put(uuid, lista);
        }
    }

    private void zapisz() {
        for (String key : configWyciszen.getKeys(false)) configWyciszen.set(key, null);

        for (Map.Entry<UUID, Set<UUID>> wpis : wyciszeni.entrySet()) {
            if (wpis.getValue().isEmpty()) continue;
            List<String> lista = new ArrayList<>();
            for (UUID uuid : wpis.getValue()) lista.add(uuid.toString());
            configWyciszen.set(wpis.getKey().toString(), lista);
        }

        try {
            configWyciszen.save(plikWyciszen);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać wyciszenia.yml: " + e.getMessage());
        }
    }

    /** Przełącza wyciszenie (kto wycisza kogo). Zwraca true, jeśli PO tym wywołaniu "kogo" jest wyciszony. */
    public boolean przelacz(Player kto, OfflinePlayer kogo) {
        Set<UUID> lista = wyciszeni.computeIfAbsent(kto.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        boolean bylWyciszony = lista.remove(kogo.getUniqueId());
        if (!bylWyciszony) lista.add(kogo.getUniqueId());
        zapisz();
        return !bylWyciszony;
    }

    private boolean maWyciszonego(UUID sluchacz, UUID nadawca) {
        Set<UUID> lista = wyciszeni.get(sluchacz);
        return lista != null && lista.contains(nadawca);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        if (wyciszeni.isEmpty()) return; // szybkie wyjście - zdecydowana większość czasu nikt nikogo nie wyciszył

        UUID nadawca = event.getPlayer().getUniqueId();
        // viewers() to odpowiednik dawnego getRecipients(), ale List<Audience> zamiast
        // Set<Player> - odbiorcy niebędący graczem (np. konsola) nie mają UUID, więc nie
        // mogą mieć nikogo wyciszonego i zostają bez zmian.
        event.viewers().removeIf(odbiorca -> odbiorca instanceof Player gracz && maWyciszonego(gracz.getUniqueId(), nadawca));
    }
}
