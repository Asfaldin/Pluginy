package elo.mainplugins.skyblock.config;

import org.bukkit.Material;

/**
 * Jeden typ customowego spawnera (mainplugins-spawners) - id MUSI się zgadzać z SpawnerType.name().
 * mnoznik = ile razy droższe są ulepszenia tego spawnera od ceny podstawowej (1.0 = tyle samo).
 */
public record SpawnerTyp(String id, String nazwaOdmieniona, Material ikona, double mnoznik) {
}
