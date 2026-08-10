package elo.mainplugins.tools.pickaxe;

import elo.mainplugins.tools.skilltree.RarePerk;
import org.bukkit.Material;

import java.util.List;

/**
 * Pula rzadkich perków losowanych co RARE_ROLL_INTERVAL poziomów kilofa (3 z puli do
 * wyboru 1, bez powtórek na cały czas życia przedmiotu) - odpowiednik AxeRarePerks/
 * HoeRarePerks/SwordRarePerks. Zastępuje dawne RarePerks.java (ten sam zestaw perków,
 * tylko na wspólnym typie skilltree.RarePerk zamiast lokalnego rekordu).
 *
 * minTier (patrz RarePerk#minTier) rozłożony 1-3, żeby drewniany/kamienny kilof nie mógł
 * trafić najmocniejszych legendarnych perków od razu na starcie.
 */
public final class PickaxeRarePerks {

    private PickaxeRarePerks() {}

    public static final List<RarePerk> WSZYSTKIE = List.of(
            new RarePerk("RARE_KIL_EFFICIENCY", "Wydajność I", Material.NETHER_STAR, "Rzadka: nadaje kilofowi prawdziwą Wydajność I, niezależnie od bonusów % z kart.", 1),
            new RarePerk("RARE_KIL_MIDAS", "Dotyk Midasa", Material.GOLD_INGOT, "3% szansy na bonus $ przy DOWOLNYM kopanym bloku.", 1),
            new RarePerk("RARE_KIL_MAGNET", "Magnes Górnika", Material.MAGMA_CREAM, "Pobliskie plony są delikatnie przyciągane do Ciebie.", 1),
            new RarePerk("RARE_KIL_FORT4", "Fortuna IV", Material.NETHER_STAR, "Rzadka: Fortuna ponad zwykły limit gałęzi Precyzji.", 2),
            new RarePerk("RARE_KIL_SECOND_CHANCE", "Druga Szansa", Material.ECHO_SHARD, "5% szansy na podwójny plon (na kamieniu/bruku - na rudzie zamiast tego bonus $).", 2),
            new RarePerk("RARE_KIL_UNYIELDING_SPIRIT", "Nieugięty Duch", Material.EXPERIENCE_BOTTLE, "Szansa na spory wybuch orbów doświadczenia.", 2),
            new RarePerk("RARE_KIL_DEPTHS_BLESSING", "Błogosławieństwo Głębi", Material.GLOW_BERRIES, "Drobna regeneracja i sytość podczas ciągłego kopania.", 2),
            new RarePerk("RARE_KIL_CHAOS_CORE", "Rdzeń Chaosu", Material.FIRE_CHARGE, "Rzadka szansa na natychmiastowe skopanie do 3 sąsiednich bloków.", 3),
            new RarePerk("RARE_KIL_MINER_KEY", "Klucz Górnika", Material.TRIPWIRE_HOOK, "0.001% szansy przy KAŻDYM kopanym bloku na Klucz do Skrzynki (jeśli mainplugins-crates jest wgrany).", 3)
    );
}