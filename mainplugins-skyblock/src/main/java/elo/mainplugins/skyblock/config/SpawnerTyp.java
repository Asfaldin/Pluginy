package elo.mainplugins.skyblock.config;

import org.bukkit.Material;

/** Jeden typ customowego spawnera (mainplugins-spawners) - id MUSI się zgadzać z SpawnerType.name(). */
public record SpawnerTyp(String id, String nazwaOdmieniona, Material ikona, int cenaWSklepie) {
}
