package elo.mainplugins.quests.generator;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;

import java.util.List;

/**
 * Jeden wpis z generatory.yml, w pełni wczytany (patrz GeneratorLoader) - id to jednocześnie
 * custom-id (patrz CustomItemKeys#CUSTOM_ITEM_ID), ten sam mechanizm co custom-items.yml.
 */
public record GeneratorDefinition(
        String id,
        TrybGeneratora tryb,
        Material materialGeneratora,
        Material materialBazowe,
        WymaganeNarzedzie narzedzie,
        long odnowaTickow,
        Component nazwa,
        List<Component> lore,
        List<GeneratorDrop> bazaDropy,
        List<GeneratorDrop> bonusDropy
) {
}
