package elo.mainplugins.crates.model;

import java.util.List;

/** Skrzynka z crates.yml: wygląd, klucze, które ją otwierają, i pula wygranych. */
public record CrateDef(String id, String name, List<String> lore, ItemRef item, List<String> keys, List<Prize> prizes) {

    public int totalWeight() {
        return prizes.stream().mapToInt(Prize::weight).sum();
    }
}
