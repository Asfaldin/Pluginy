package elo.mainplugins.quests.model;

import org.bukkit.Material;

/**
 * Pojedynczy wymagany materiał + ilość w ramach {@link Requirement.ItemRequirement} - quest może
 * mieć ich kilka naraz (np. "16x dąb + 16x brzoza"). customId/displayName są null dla zwykłych
 * wanilijskich wymogów - ustawione, gdy quest musi rozróżnić konkretny custom-tagowany item
 * (np. gatunek ryby z mainplugins-fishing, trofeum lochu z mainplugins-dungeons) od innych
 * itemów dzielących ten sam wanilijski Material (patrz CustomItemKeys w mainplugins-core).
 */
public record MaterialRequirement(Material material, int amount, String customId, String displayName) {

    public static MaterialRequirement of(Material material, int amount) {
        return new MaterialRequirement(material, amount, null, null);
    }
}
