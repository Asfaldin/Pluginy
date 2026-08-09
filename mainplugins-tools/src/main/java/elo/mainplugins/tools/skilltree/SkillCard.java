package elo.mainplugins.tools.skilltree;

import org.bukkit.Material;

/**
 * Karta ulepszenia - jednostka oferowana przy levelowaniu narzędzia (patrz
 * ToolSkillManager#rollCardOffer). Zastępuje dawny SkillNode (bitmaskowy,
 * kup-po-kolei) modelem stackowalnym: gracz może wybrać tę samą kartę wielokrotnie
 * (do maxStacks), a jej efekt skaluje się z aktualnym poziomem (licznikiem), nie
 * jest zero-jedynkowy. Karty LEGENDARY mają maxStacks=1 i żyją w osobnej puli
 * (RarePerks) - to jest reprezentacja pod UI/losowanie, sam efekt nadal czyta stary
 * mechanizm pkRare.
 */
public record SkillCard(String id, String displayName, Material icon, String opis, Rarity rarity, int maxStacks) {}