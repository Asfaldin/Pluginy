package elo.mainplugins.tools.hoe;

import elo.mainplugins.tools.skilltree.Rarity;
import elo.mainplugins.tools.skilltree.SkillBranch;
import elo.mainplugins.tools.skilltree.SkillCard;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Karty motyki - model roguelike (co level oferta 4 kart do wyboru, patrz
 * ToolSkillManager). Zastępuje dawne 100 węzłów drzewka skonsolidowaną listą
 * ~20 stackowalnych/unikalnych kart - te same mechaniki co poprzednio (patrz
 * HoeSkillManager), tylko sterowane licznikiem zamiast bitmaski.
 *
 * Suma maxStacks (135) jest CELOWO większa niż MAX_LEVEL (100) - margines
 * niedoboru, żeby żaden gracz nie mógł zmaksować wszystkiego i wybór faktycznie
 * coś kosztował.
 */
public final class HoeSkillTrees {

    private HoeSkillTrees() {}

    public static final List<SkillBranch> BRANCHES = List.of(
            new SkillBranch("PLON", "Plenność", NamedTextColor.GOLD, Material.DIAMOND_HOE, List.of(
                    new SkillCard("PLON_SPEED", "Przyspieszenie Zbiorów", Material.FEATHER,
                            "+3% prędkości zbierania za każdy poziom karty.", Rarity.COMMON, 20),
                    new SkillCard("PLON_ZBIOR", "Obfity Zbiór", Material.WHEAT,
                            "+10% do szansy na podwójny plon za każdy poziom karty (wspólna pula z innymi kartami tego typu).", Rarity.COMMON, 14),
                    new SkillCard("PLON_ZNIWO", "Żniwo Pola", Material.HAY_BLOCK,
                            "Sąsiednie dojrzałe uprawy zbierają się razem z tą zbieraną - szansa i liczba sąsiadów rosną z poziomem karty.", Rarity.RARE, 5),
                    new SkillCard("PLON_RYTM", "Rytm Rolnika", Material.SUGAR,
                            "Uprawy z rzędu dają chwilowy Pośpiech - próg trafień i okno czasowe poprawiają się z poziomem karty.", Rarity.RARE, 3),
                    new SkillCard("PLON_SZAL", "Szał Żniwiarza", Material.BLAZE_POWDER,
                            "Rytm Rolnika daje teraz Pośpiech II zamiast Pośpiechu I.", Rarity.EPIC, 1),
                    new SkillCard("PLON_MISTRZOSTWO", "Mistrzostwo Plenności", Material.NETHER_STAR,
                            "+15% do szansy na podwójny plon (największy pojedynczy bonus w tej gałęzi).", Rarity.EPIC, 1)
            )),
            new SkillBranch("AGRO", "Agronomia", NamedTextColor.AQUA, Material.GOLDEN_HOE, List.of(
                    new SkillCard("AGRO_FORT", "Fortuna Plonu", Material.EMERALD,
                            "Nadaje motyce prawdziwą Fortunę - poziom karty = poziom Fortuny (max III).", Rarity.RARE, 3),
                    new SkillCard("AGRO_ZIARNO", "Bogate Ziarno", Material.WHEAT_SEEDS,
                            "+5% niezależnej szansy na dodatkowe nasiona za każdy poziom karty.", Rarity.COMMON, 14),
                    new SkillCard("AGRO_KUPIEC", "Ręka Kupca", Material.DIAMOND,
                            "+8% niezależnej szansy na bonus $ skalowany tierem motyki za każdy poziom karty.", Rarity.RARE, 4),
                    new SkillCard("AGRO_CELNY", "Celny Zbiór", Material.GOLDEN_HOE,
                            "+15% do szansy na podwójny plon za każdy poziom karty (wspólna pula z Obfitym Zbiorem).", Rarity.COMMON, 14),
                    new SkillCard("AGRO_OKO", "Oko Farmera", Material.ENDER_EYE,
                            "+5% niezależnej szansy na bonusową wypłatę $ za każdy poziom karty.", Rarity.COMMON, 12),
                    new SkillCard("AGRO_MISTRZOSTWO", "Mistrzostwo Agronomii", Material.NETHER_STAR,
                            "+20% do szansy na podwójny plon (największy pojedynczy bonus w tej gałęzi).", Rarity.EPIC, 1)
            )),
            new SkillBranch("NATURA", "Natura", NamedTextColor.LIGHT_PURPLE, Material.WHEAT_SEEDS, List.of(
                    new SkillCard("NAT_DUCH", "Duch Pola", Material.GLOWSTONE_DUST,
                            "Zbieranie ma niezależną 25% szansę na dodatkowe orby xp za każdy poziom karty.", Rarity.COMMON, 14),
                    new SkillCard("NAT_GLOD", "Głód Rolnika", Material.COOKED_BEEF,
                            "Poziom 1: praca motyką nie zużywa głodu. Poziom 2: powoli go regeneruje. Poziom 3: regeneracja podwojona.", Rarity.RARE, 3),
                    new SkillCard("NAT_WIATR", "Wiatr Pól", Material.AMETHYST_SHARD,
                            "Trzymanie motyki daje stały Pośpiech - poziom karty = poziom Pośpiechu.", Rarity.RARE, 5),
                    new SkillCard("NAT_LADOWANIE", "Miękkie Lądowanie", Material.ANVIL,
                            "Poziom 1: odporność na spadający piasek/żwir. Poziom 2: także na zwykły upadek.", Rarity.RARE, 2),
                    new SkillCard("NAT_URODZAJ", "Błogosławieństwo Urodzaju", Material.NETHERITE_SCRAP,
                            "Zbieranie plonów ma szansę zamienić plon w cenny surowiec - szansa rośnie z poziomem karty (6/12/16/20/24%).", Rarity.EPIC, 5),
                    new SkillCard("NAT_OBFITOSC", "Podwójne Żniwo", Material.ENDER_CHEST,
                            "+10% do szansy na podwójny plon za każdy poziom karty (wspólna pula z Obfitym Zbiorem/Celnym Zbiorem).", Rarity.COMMON, 12),
                    new SkillCard("NAT_SZCZESLIWY", "Szczęśliwy Plon", Material.RABBIT_FOOT,
                            "Zbieranie ma dodatkową szansę na spory wybuch orbów doświadczenia.", Rarity.EPIC, 1),
                    new SkillCard("NAT_MISTRZOSTWO", "Mistrzostwo Natury", Material.NETHER_STAR,
                            "+10% do szansy na podwójny plon (sumuje się z resztą).", Rarity.EPIC, 1)
            ))
    );
}