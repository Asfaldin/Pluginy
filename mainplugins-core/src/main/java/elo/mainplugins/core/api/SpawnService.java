package elo.mainplugins.core.api;

import org.bukkit.Location;

/**
 * Główny, skonfigurowany przez admina punkt spawnu serwera (patrz /@setspawn).
 * Implementację dostarcza wyłącznie plugin MainpluginsSpawn, rejestrując ją
 * w Bukkit ServicesManager. Opcjonalny jak {@link IslandService} - inne pluginy
 * pobierają go przez {@link elo.mainplugins.core.CoreAPI#getSpawnService()}
 * i muszą mieć sensowny fallback (np. Bukkit.getWorlds().get(0).getSpawnLocation()),
 * gdyby MainpluginsSpawn nie był wgrany/włączony.
 */
public interface SpawnService {

    /** Ustawiony punkt spawnu, albo domyślny spawn pierwszego świata, jeśli nikt jeszcze nic nie ustawił. */
    Location getSpawn();
}
