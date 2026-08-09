package elo.mainplugins.tools.sword;

import elo.mainplugins.tools.skilltree.Rarity;
import elo.mainplugins.tools.skilltree.SkillBranch;
import elo.mainplugins.tools.skilltree.SkillCard;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Karty miecza - model roguelike (co level oferta 4 kart do wyboru, patrz
 * ToolSkillManager). Zastępuje dawne 100 węzłów drzewka skonsolidowaną listą
 * ~20 stackowalnych/unikalnych kart - te same mechaniki co poprzednio (patrz
 * SwordSkillManager), tylko sterowane licznikiem zamiast bitmaski. Część efektów
 * (bonusowy/zdublowany łup) działa dopiero przy ZABICIU przeciwnika, nie przy
 * samym trafieniu - patrz SwordSkillManager (onEntityDamage vs onEntityDeath).
 *
 * Suma maxStacks (135) jest CELOWO większa niż MAX_LEVEL (100) - margines
 * niedoboru, żeby żaden gracz nie mógł zmaksować wszystkiego i wybór faktycznie
 * coś kosztował.
 */
public final class SwordSkillTrees {

    private SwordSkillTrees() {}

    public static final List<SkillBranch> BRANCHES = List.of(
            new SkillBranch("SILA", "Siła Ataku", NamedTextColor.GOLD, Material.DIAMOND_SWORD, List.of(
                    new SkillCard("SILA_SPEED", "Szybkość Ataku", Material.FEATHER,
                            "+3% szybkości ataku za każdy poziom karty.", Rarity.COMMON, 20),
                    new SkillCard("SILA_CIOS", "Potężny Cios", Material.IRON_INGOT,
                            "+10% do szansy na dodatkową kopię łupu z pokonanego przeciwnika za każdy poziom karty.", Rarity.COMMON, 14),
                    new SkillCard("SILA_FALA", "Fala Ciosów", Material.IRON_SWORD,
                            "Trafienie ma szansę zadać obrażenia też pobliskim wrogom - szansa i liczba celów rosną z poziomem karty.", Rarity.RARE, 5),
                    new SkillCard("SILA_KOMBO", "Rytm Wojownika", Material.SUGAR,
                            "Trafienia z rzędu dają chwilową Siłę - próg trafień i okno czasowe poprawiają się z poziomem karty.", Rarity.RARE, 3),
                    new SkillCard("SILA_WSCIEKLOSC", "Szał Wojownika", Material.BLAZE_POWDER,
                            "Rytm Wojownika daje teraz Siłę II zamiast Siły I.", Rarity.EPIC, 1),
                    new SkillCard("SILA_MISTRZOSTWO", "Mistrzostwo Siły", Material.NETHER_STAR,
                            "+15% do szansy na dodatkową kopię łupu (największy pojedynczy bonus w tej gałęzi).", Rarity.EPIC, 1)
            )),
            new SkillBranch("PRECYZJA", "Precyzja Walki", NamedTextColor.AQUA, Material.GOLDEN_SWORD, List.of(
                    new SkillCard("PREC_LOOT", "Grabież", Material.EMERALD,
                            "Nadaje mieczowi prawdziwą Grabież - poziom karty = poziom Grabieży (max III).", Rarity.RARE, 3),
                    new SkillCard("PREC_LUP", "Krwawy Łup", Material.EMERALD,
                            "+5% niezależnej szansy na dodatkowy szmaragd z pokonanego przeciwnika za każdy poziom karty.", Rarity.COMMON, 14),
                    new SkillCard("PREC_NAJEMNIK", "Ręka Najemnika", Material.DIAMOND,
                            "+8% niezależnej szansy na bonus $ skalowany tierem miecza za każdy poziom karty.", Rarity.RARE, 4),
                    new SkillCard("PREC_CIOS", "Precyzyjny Cios", Material.GOLDEN_SWORD,
                            "+15% do szansy na dodatkową kopię łupu za każdy poziom karty (wspólna pula z Potężnym Ciosem).", Rarity.COMMON, 14),
                    new SkillCard("PREC_OKO", "Oko Łowcy", Material.ENDER_EYE,
                            "+5% niezależnej szansy na bonusową wypłatę $ przy trafieniu za każdy poziom karty.", Rarity.COMMON, 12),
                    new SkillCard("PREC_MISTRZOSTWO", "Mistrzostwo Precyzji", Material.NETHER_STAR,
                            "+20% do szansy na dodatkową kopię łupu (największy pojedynczy bonus w tej gałęzi).", Rarity.EPIC, 1)
            )),
            new SkillBranch("DUCH", "Duch Wojny", NamedTextColor.LIGHT_PURPLE, Material.WITHER_SKELETON_SKULL, List.of(
                    new SkillCard("DUCH_BITWA", "Duch Bitwy", Material.GLOWSTONE_DUST,
                            "Trafienie ma niezależną 25% szansę na dodatkowe orby xp za każdy poziom karty.", Rarity.COMMON, 14),
                    new SkillCard("DUCH_GLOD", "Głód Wojownika", Material.COOKED_BEEF,
                            "Poziom 1: walka nie zużywa głodu. Poziom 2: powoli go regeneruje. Poziom 3: regeneracja podwojona.", Rarity.RARE, 3),
                    new SkillCard("DUCH_BOJOWY", "Duch Bojowy", Material.AMETHYST_SHARD,
                            "Trzymanie miecza daje stałą Siłę - poziom karty = poziom Siły.", Rarity.RARE, 5),
                    new SkillCard("DUCH_LADOWANIE", "Miękkie Lądowanie", Material.ANVIL,
                            "Poziom 1: odporność na spadający piasek/żwir. Poziom 2: także na zwykły upadek.", Rarity.RARE, 2),
                    new SkillCard("DUCH_KREW", "Krew Bestii", Material.NETHERITE_SCRAP,
                            "Pokonanie przeciwnika ma szansę dorzucić cenny surowiec - szansa rośnie z poziomem karty (6/12/16/20/24%).", Rarity.EPIC, 5),
                    new SkillCard("DUCH_LUP", "Podwójny Łup", Material.ENDER_CHEST,
                            "+10% do szansy na dodatkową kopię łupu za każdy poziom karty (wspólna pula z Potężnym/Precyzyjnym Ciosem).", Rarity.COMMON, 12),
                    new SkillCard("DUCH_SZCZESLIWY", "Szczęśliwy Cios", Material.RABBIT_FOOT,
                            "Trafienie ma dodatkową szansę na spory wybuch orbów doświadczenia.", Rarity.EPIC, 1),
                    new SkillCard("DUCH_MISTRZOSTWO", "Mistrzostwo Ducha", Material.NETHER_STAR,
                            "+10% do szansy na dodatkową kopię łupu (sumuje się z resztą).", Rarity.EPIC, 1)
            ))
    );
}