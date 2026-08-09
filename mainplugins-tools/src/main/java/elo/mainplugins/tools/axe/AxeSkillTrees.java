package elo.mainplugins.tools.axe;

import elo.mainplugins.tools.skilltree.Rarity;
import elo.mainplugins.tools.skilltree.SkillBranch;
import elo.mainplugins.tools.skilltree.SkillCard;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Karty siekiery - model roguelike (co level oferta 4 kart do wyboru, patrz
 * ToolSkillManager). Zastępuje dawne 100 węzłów drzewka skonsolidowaną listą
 * ~20 stackowalnych/unikalnych kart - te same mechaniki co poprzednio (patrz
 * AxeSkillManager), tylko sterowane licznikiem zamiast bitmaski.
 *
 * Suma maxStacks (135) jest CELOWO większa niż MAX_LEVEL (100) - margines
 * niedoboru, żeby żaden gracz nie mógł zmaksować wszystkiego i wybór faktycznie
 * coś kosztował.
 */
public final class AxeSkillTrees {

    private AxeSkillTrees() {}

    public static final List<SkillBranch> BRANCHES = List.of(
            new SkillBranch("CIECIE", "Siła Cięcia", NamedTextColor.GOLD, Material.DIAMOND_AXE, List.of(
                    new SkillCard("CIECIE_SPEED", "Przyspieszenie Rąbania", Material.FEATHER,
                            "+3% prędkości rąbania za każdy poziom karty.", Rarity.COMMON, 20),
                    new SkillCard("CIECIE_OSTRZE", "Ostre Ostrze", Material.IRON_INGOT,
                            "+10% do szansy na podwójny plon za każdy poziom karty (wspólna pula z innymi kartami tego typu).", Rarity.COMMON, 14),
                    new SkillCard("CIECIE_TRZASK", "Trzask Konarów", Material.OAK_LOG,
                            "Sąsiednie bloki drewna pękają razem z rąbanym - szansa i liczba sąsiadów rosną z poziomem karty.", Rarity.RARE, 5),
                    new SkillCard("CIECIE_KOMBO", "Rytm Drwala", Material.SUGAR,
                            "Kłody z rzędu dają chwilowy Pośpiech - próg trafień i okno czasowe poprawiają się z poziomem karty.", Rarity.RARE, 3),
                    new SkillCard("CIECIE_WSCIEKLOSC", "Szał Drwala", Material.BLAZE_POWDER,
                            "Rytm Drwala daje teraz Pośpiech II zamiast Pośpiechu I.", Rarity.EPIC, 1),
                    new SkillCard("CIECIE_MISTRZOSTWO", "Mistrzostwo Siły", Material.NETHER_STAR,
                            "+15% do szansy na podwójny plon (największy pojedynczy bonus w tej gałęzi).", Rarity.EPIC, 1)
            )),
            new SkillBranch("LESNICTWO", "Leśnictwo", NamedTextColor.AQUA, Material.APPLE, List.of(
                    new SkillCard("LES_FORT", "Obfitość Lasu", Material.EMERALD,
                            "Nadaje siekierze prawdziwą Fortunę - poziom karty = poziom Fortuny (max III).", Rarity.RARE, 3),
                    new SkillCard("LES_ZLOTYKCIUK", "Zielony Kciuk", Material.OAK_SAPLING,
                            "+5% niezależnej szansy na dodatkową sadzonkę/jabłko za każdy poziom karty.", Rarity.COMMON, 14),
                    new SkillCard("LES_TRACZ", "Ręka Tracza", Material.DIAMOND,
                            "+8% niezależnej szansy na bonus $ skalowany tierem siekiery za każdy poziom karty.", Rarity.RARE, 4),
                    new SkillCard("LES_CIOS", "Precyzyjny Cios", Material.GOLDEN_AXE,
                            "+15% do szansy na podwójny plon za każdy poziom karty (wspólna pula z Ostrym Ostrzem).", Rarity.COMMON, 14),
                    new SkillCard("LES_OKO", "Oko Handlarza", Material.ENDER_EYE,
                            "+5% niezależnej szansy na bonusową wypłatę $ za każdy poziom karty.", Rarity.COMMON, 12),
                    new SkillCard("LES_MISTRZOSTWO", "Mistrzostwo Leśnictwa", Material.NETHER_STAR,
                            "+20% do szansy na podwójny plon (największy pojedynczy bonus w tej gałęzi).", Rarity.EPIC, 1)
            )),
            new SkillBranch("NATURA", "Natura", NamedTextColor.LIGHT_PURPLE, Material.OAK_SAPLING, List.of(
                    new SkillCard("NAT_DUCH", "Duch Puszczy", Material.GLOWSTONE_DUST,
                            "Rąbanie ma niezależną 25% szansę na dodatkowe orby xp za każdy poziom karty.", Rarity.COMMON, 14),
                    new SkillCard("NAT_GLOD", "Leśny Głód", Material.COOKED_BEEF,
                            "Poziom 1: rąbanie nie zużywa głodu. Poziom 2: powoli go regeneruje. Poziom 3: regeneracja podwojona.", Rarity.RARE, 3),
                    new SkillCard("NAT_WIATR", "Wiatr Lasu", Material.AMETHYST_SHARD,
                            "Trzymanie siekiery daje stały Pośpiech - poziom karty = poziom Pośpiechu.", Rarity.RARE, 5),
                    new SkillCard("NAT_LADOWANIE", "Miękkie Lądowanie", Material.ANVIL,
                            "Poziom 1: odporność na spadający piasek/żwir. Poziom 2: także na zwykły upadek.", Rarity.RARE, 2),
                    new SkillCard("NAT_DUCHDRZEWA", "Duch Drzewa", Material.NETHERITE_SCRAP,
                            "Rąbanie ma szansę zamienić plon w cenny surowiec - szansa rośnie z poziomem karty (6/12/16/20/24%).", Rarity.EPIC, 5),
                    new SkillCard("NAT_PIEN", "Podwójny Pień", Material.ENDER_CHEST,
                            "+10% do szansy na podwójny plon za każdy poziom karty (wspólna pula z Ostrym Ostrzem/Precyzyjnym Ciosem).", Rarity.COMMON, 12),
                    new SkillCard("NAT_SZCZESLIWE", "Szczęśliwe Drzewo", Material.RABBIT_FOOT,
                            "Rąbanie ma dodatkową szansę na spory wybuch orbów doświadczenia.", Rarity.EPIC, 1),
                    new SkillCard("NAT_MISTRZOSTWO", "Mistrzostwo Natury", Material.NETHER_STAR,
                            "+10% do szansy na podwójny plon (sumuje się z resztą).", Rarity.EPIC, 1)
            ))
    );
}