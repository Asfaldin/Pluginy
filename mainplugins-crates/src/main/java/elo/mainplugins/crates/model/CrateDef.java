package elo.mainplugins.crates.model;

import java.util.List;

/**
 * Skrzynka z crates.yml: wygląd, klucze, które ją otwierają, i pula wygranych.
 * hologram = linijki napisu nad postawioną skrzynką (puste = nazwa + podpowiedź z pliku językowego),
 * hologramEnabled = false wyłącza napis nad tą skrzynką.
 */
public record CrateDef(String id, String name, List<String> lore, ItemRef item, List<String> keys, List<Prize> prizes,
                       List<String> hologram, boolean hologramEnabled) {

    public int totalWeight() {
        return prizes.stream().mapToInt(Prize::weight).sum();
    }
}
