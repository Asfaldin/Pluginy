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
 *
 * minTier (0-4, patrz ToolSkillManager#tierForLevel) - od jakiego tieru narzędzia karta
 * w ogóle może pojawić się w ofercie (patrz ToolSkillManager#randomAvailableCardOfTier).
 * Domyślnie 0 (dostępna od startu) - konstruktor 6-argumentowy istnieje, żeby narzędzia
 * bez progów tierowych (na razie: siekiera/motyka/miecz) nie musiały nic zmieniać.
 */
public record SkillCard(String id, String displayName, Material icon, String opis, Rarity rarity, int maxStacks, int minTier) {
    public SkillCard(String id, String displayName, Material icon, String opis, Rarity rarity, int maxStacks) {
        this(id, displayName, icon, opis, rarity, maxStacks, 0);
    }
}