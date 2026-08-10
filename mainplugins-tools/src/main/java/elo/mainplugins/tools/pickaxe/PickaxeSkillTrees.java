package elo.mainplugins.tools.pickaxe;

import elo.mainplugins.tools.skilltree.Rarity;
import elo.mainplugins.tools.skilltree.SkillBranch;
import elo.mainplugins.tools.skilltree.SkillCard;
import elo.mainplugins.tools.skilltree.Synergy;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * Karty kilofa - model roguelike (co level oferta 4 kart do wyboru, patrz
 * ToolSkillManager). Zastępuje dawne 100 węzłów drzewka (34/33/33 na gałąź, bitmaska
 * kup-po-kolei) skonsolidowaną listą stackowalnych/unikalnych kart - te same mechaniki
 * co poprzednio (patrz PickaxeSkillManager), tylko sterowane licznikiem (cardCountOf)
 * zamiast bitmaski. Np. dawne 15 osobnych węzłów "Przyspieszenie I..XV" (po +3% każdy)
 * to teraz jedna karta WYD_SPEED o maxStacks=20 (+3%/poziom karty).
 *
 * Suma maxStacks (134) jest CELOWO większa niż MAX_LEVEL (100) - margines niedoboru,
 * żeby żaden gracz nie mógł zmaksować wszystkiego i wybór faktycznie coś kosztował
 * (patrz komentarz klasowy ToolSkillManager).
 *
 * minTier (patrz SkillCard#minTier) jest rozłożony na WSZYSTKIE rzadkości, nie tylko
 * EPIC/LEGENDARY - np. Oko Sokoła (rzadkie rudy) wymaga Tier 2 (Żelazny), bo dopiero
 * żelazny kilof potrafi w ogóle wykopać diament/szmaragd w Vanillu; karty czysto
 * wydajnościowe (Przyspieszenie, Iskra Many) zostają dostępne od startu. Efekt: drewniany
 * kilof fizycznie nie może dostać w ofercie kart, które mają sens dopiero na wyższym
 * tierze - "diamentowe" perki po prostu jeszcze się nie pojawiają, zamiast być dostępne
 * i słabe.
 */
public final class PickaxeSkillTrees {

    private PickaxeSkillTrees() {}

    public static final List<SkillBranch> BRANCHES = List.of(
            new SkillBranch("WYDAJNOSC", "Wydajność", NamedTextColor.GOLD, Material.DIAMOND_PICKAXE, List.of(
                    new SkillCard("WYD_SPEED", "Przyspieszenie Kopania", Material.FEATHER,
                            "+3% prędkości kopania za każdy poziom karty.", Rarity.COMMON, 20, 0),
                    new SkillCard("WYD_IRONGRIP", "Żelazna Pięść", Material.IRON_INGOT,
                            "+10% do szansy na podwójny plon za każdy poziom karty (na kamieniu/bruku - na rudzie zamiast tego bonus $).", Rarity.COMMON, 11, 0),
                    new SkillCard("WYD_MOMENTUM", "Impet Górnika", Material.SUGAR,
                            "Bloki z rzędu dają chwilowy Pośpiech - próg trafień i okno czasowe poprawiają się z poziomem karty.", Rarity.RARE, 3, 1),
                    new SkillCard("WYD_ADRENALINE", "Adrenalina Górnika", Material.BLAZE_POWDER,
                            "Impet Górnika daje teraz Pośpiech II zamiast Pośpiechu I.", Rarity.EPIC, 1, 2),
                    new SkillCard("WYD_RESONANCE", "Rezonans Rudy", Material.RAW_IRON_BLOCK,
                            "Sąsiedni blok tej samej rudy ma szansę pęknąć razem z kopanym - szansa i liczba sąsiadów rosną z poziomem karty.", Rarity.RARE, 5, 1),
                    new SkillCard("WYD_MASTERY", "Mistrzostwo Górnika", Material.NETHER_STAR,
                            "+15% do szansy na podwójny plon (na kamieniu/bruku - na rudzie zamiast tego bonus $).", Rarity.EPIC, 1, 2)
            )),
            new SkillBranch("PRECYZJA", "Precyzja", NamedTextColor.AQUA, Material.SPYGLASS, List.of(
                    new SkillCard("PREC_FORT", "Fortuna", Material.EMERALD,
                            "Nadaje kilofowi prawdziwą Fortunę - poziom karty = poziom Fortuny (max III).", Rarity.RARE, 3, 1),
                    new SkillCard("PREC_KEEN_EYE", "Bystre Oko", Material.ENDER_EYE,
                            "+5% niezależnej szansy na bonusową wypłatę $ z kopanej rudy za każdy poziom karty.", Rarity.COMMON, 10, 0),
                    new SkillCard("PREC_JEWELER", "Ręka Jubilera", Material.DIAMOND,
                            "+8% niezależnej szansy na bonus $ skalowany tierem kilofa za każdy poziom karty.", Rarity.RARE, 6, 2),
                    new SkillCard("PREC_CRIT", "Precyzyjne Uderzenie", Material.GOLDEN_PICKAXE,
                            "+15% do szansy na krytyczne kopnięcie = podwójny plon za każdy poziom karty (na kamieniu/bruku - na rudzie zamiast tego bonus $).", Rarity.COMMON, 12, 0),
                    new SkillCard("PREC_HAWK_EYE", "Oko Sokoła", Material.SPYGLASS,
                            "Rzadkie rudy (diament/szmaragd/starożytny gruz): +10% szansy na bonus $ za każdy poziom karty. Wymaga Tieru 2 (Żelazny) - dopiero wtedy da się w ogóle wykopać taką rudę.", Rarity.COMMON, 11, 2),
                    new SkillCard("PREC_PERFECTION", "Perfekcja Górnika", Material.NETHER_STAR,
                            "+20% do szansy na podwójny plon (na kamieniu/bruku - na rudzie zamiast tego bonus $).", Rarity.EPIC, 1, 2),
                    new SkillCard("PREC_MASTERY", "Mistrzostwo Precyzji", Material.NETHER_STAR,
                            "+20% do szansy na podwójny plon (na kamieniu/bruku - na rudzie zamiast tego bonus $; sumuje się z Perfekcją Górnika).", Rarity.EPIC, 1, 2)
            )),
            new SkillBranch("MAGIA", "Magia", NamedTextColor.LIGHT_PURPLE, Material.AMETHYST_SHARD, List.of(
                    new SkillCard("MAG_SPARK", "Iskra Many", Material.GLOWSTONE_DUST,
                            "Kopanie ma niezależną 25% szansę na dodatkowe orby doświadczenia za każdy poziom karty.", Rarity.COMMON, 12, 0),
                    new SkillCard("MAG_INSATIABLE", "Nienasycenie", Material.COOKED_BEEF,
                            "Poziom 1: kopanie nie zużywa głodu. Poziom 2: powoli go regeneruje. Poziom 3: regeneracja podwojona.", Rarity.RARE, 3, 1),
                    new SkillCard("MAG_HASTE", "Aura Pośpiechu", Material.AMETHYST_SHARD,
                            "Trzymanie kilofa daje stały efekt Pośpiechu - poziom karty = poziom Pośpiechu.", Rarity.RARE, 5, 1),
                    new SkillCard("MAG_GRAVITY_WARD", "Grawitacyjna Odporność", Material.ANVIL,
                            "Poziom 1: odporność na spadający piasek/żwir. Poziom 2: także na zwykły upadek.", Rarity.RARE, 2, 1),
                    new SkillCard("MAG_PHILOSOPHERS_STONE", "Kamień Filozoficzny", Material.NETHERITE_SCRAP,
                            "Kopanie kamienia/brukowca ma szansę zamienić plon w cenny surowiec - szansa rośnie z poziomem karty (6/12/16/20/24%).", Rarity.EPIC, 5, 2),
                    new SkillCard("MAG_DEPTHS_GATE", "Wrota Głębi", Material.ENDER_CHEST,
                            "+10% do szansy na podwójny plon za każdy poziom karty (na kamieniu/bruku - na rudzie zamiast tego bonus $).", Rarity.COMMON, 10, 0),
                    new SkillCard("MAG_LUCKY_ORE", "Szczęśliwa Ruda", Material.RABBIT_FOOT,
                            "Kopanie rzadkich rud ma dodatkową szansę na spory wybuch orbów doświadczenia.", Rarity.EPIC, 1, 2),
                    new SkillCard("MAG_MASTERY", "Mistrzostwo Magii", Material.NETHER_STAR,
                            "+10% do szansy na podwójny plon (na kamieniu/bruku - na rudzie zamiast tego bonus $; sumuje się z Wrotami Głębi).", Rarity.EPIC, 1, 2),
                    new SkillCard("MAG_BRUK_SZCZESCIE", "Szczęście Kamieniarza", Material.RAW_COPPER,
                            "+1% do szansy na Bonus z Bruku (patrz przełącznik w hubie) za każdy poziom karty.", Rarity.COMMON, 6, 0),
                    new SkillCard("MAG_BRUK_INSTYNKT", "Głębinowy Instynkt", Material.NETHERITE_SCRAP,
                            "Nie zwiększa szansy na Bonus z Bruku, ale gdy już się uda - mocno przechyla losowanie w stronę cennych surowców (diament, szmaragd, netheryt...).", Rarity.RARE, 4, 2)
            ))
    );

    /**
     * Kombinacje - bonusy emergentne z jednoczesnego zainwestowania w kilka konkretnych
     * kart (patrz Synergy/ToolSkillManager#hasSynergy). Efekty wpięte w PickaxeSkillManager
     * (syncToolSpecificStats/applyMiningPerks) w tych samych miejscach co dziś hasRare(...).
     * Bez osobnego progu tierowego - wymagania kart (patrz Map poniżej) już same w sobie
     * gwarantują wysoki tier, bo te karty go wymagają.
     */
    public static final List<Synergy> SYNERGIE = List.of(
            new Synergy("SYN_SPEED", "Błyskawiczny Górnik", Material.SUGAR,
                    "Dodatkowe +5% prędkości kopania - osobno od zwykłego bonusu z kart Przyspieszenia.",
                    Rarity.RARE, NamedTextColor.AQUA,
                    Map.of("WYD_SPEED", 10, "MAG_HASTE", 3)),
            new Synergy("SYN_CHCIWOSC", "Chciwe Ręce", Material.EMERALD,
                    "Wszystkie bonusowe wypłaty $ z Bystrego Oka i Ręki Jubilera rosną o połowę (x1.5).",
                    Rarity.RARE, NamedTextColor.GREEN,
                    Map.of("PREC_KEEN_EYE", 5, "PREC_JEWELER", 3)),
            new Synergy("SYN_DUCH_KOPALNI", "Duch Kopalni", Material.GLOWSTONE,
                    "Iskra Many: szansa na dodatkowe orby xp rośnie z 25% do 40% za każdy poziom karty.",
                    Rarity.EPIC, NamedTextColor.LIGHT_PURPLE,
                    Map.of("WYD_IRONGRIP", 6, "MAG_SPARK", 6)),
            new Synergy("SYN_LOWCA_ZYL", "Łowca Żył", Material.DIAMOND_ORE,
                    "Rzadkie rudy (diament/szmaragd/starożytny gruz) zawsze wyzwalają Rezonans Rudy, niezależnie od zwykłej szansy.",
                    Rarity.EPIC, NamedTextColor.RED,
                    Map.of("PREC_HAWK_EYE", 6, "WYD_RESONANCE", 3)),
            new Synergy("SYN_GLEBIA", "Serce Głębi", Material.NETHERITE_SCRAP,
                    "Gwarantowany (100%) bonus $ z KAŻDEGO kopanego bloku rudy - nie tylko szansa (bez fizycznego duplikowania rudy, żeby nie zalewać rynku).",
                    Rarity.LEGENDARY, NamedTextColor.GOLD,
                    Map.of("WYD_RESONANCE", 5, "MAG_PHILOSOPHERS_STONE", 5, "MAG_DEPTHS_GATE", 5))
    );
}