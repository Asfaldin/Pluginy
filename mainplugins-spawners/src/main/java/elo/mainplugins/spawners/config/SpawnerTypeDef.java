package elo.mainplugins.spawners.config;

import org.bukkit.entity.EntityType;

/**
 * Jeden typ customowego spawnera wczytany z spawnery-typy.yml (patrz SpawnerConfigLoader).
 * Zastępuje dawny enum SpawnerType - id jest teraz dowolnym stringiem z YAML zamiast
 * stałej enuma, więc dodanie 10. typu nie wymaga rekompilacji. Levelowanie wpływa tylko
 * na tempo/ilość spawnu (patrz SpawnerSettings) - same dropy to zwykłe, wanilijskie
 * dropy tego mobka po zabiciu, nic tu nie trzeba symulować ręcznie.
 */
public record SpawnerTypeDef(String id, EntityType entityType, String nazwaOdmieniona, String nazwaPojedyncza) {
}
