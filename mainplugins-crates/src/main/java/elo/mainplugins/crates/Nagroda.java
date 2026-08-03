package elo.mainplugins.crates;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/** Jedna pozycja z crate-rewards.yml. "waga" decyduje o szansie trafienia (im wyższa, tym częściej). */
record Nagroda(Material material, int amount, int waga, String nazwa, NamedTextColor kolor, boolean broadcast) {}