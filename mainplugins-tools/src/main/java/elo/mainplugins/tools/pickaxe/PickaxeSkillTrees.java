package elo.mainplugins.tools.pickaxe;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Statyczna zawartość trzech gałęzi drzewka umiejętności kilofa. Same dane -
 * jak konkretny węzeł działa w praktyce (enchant, szansa na coś, itp.) decyduje
 * PickaxeSkillManager, przełączając się po SkillNode#id().
 */
public final class PickaxeSkillTrees {

    private PickaxeSkillTrees() {}

    public record SkillNode(String id, String displayName, Material icon, String opis) {}

    public enum Branch {
        WYDAJNOSC("Wydajność", NamedTextColor.GOLD, Material.DIAMOND_PICKAXE, List.of(
                new SkillNode("WYD_SPEED1", "Przyspieszenie I", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_SPEED2", "Przyspieszenie II", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_SPEED3", "Przyspieszenie III", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_IRON_GRIP", "Żelazna Pięść", Material.IRON_INGOT, "10% szansy na podwójny plon z kopanego bloku."),
                new SkillNode("WYD_MOMENTUM", "Impet Górnika", Material.SUGAR, "3 bloki z rzędu w 3s dają Pośpiech I na 4s."),
                new SkillNode("WYD_SPEED4", "Przyspieszenie IV", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_RESONANCE", "Rezonans Rudy", Material.RAW_IRON_BLOCK, "20% szansy, że sąsiedni blok rudy pęknie razem z kopanym."),
                new SkillNode("WYD_SPEED5", "Przyspieszenie V", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_IRON_GRIP2", "Stalowy Uścisk", Material.IRON_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z Żelazną Pięścią)."),
                new SkillNode("WYD_RESONANCE2", "Wzmocniony Rezonans", Material.DEEPSLATE, "Rezonans Rudy pęka teraz do 2 sąsiednich bloków zamiast 1."),
                new SkillNode("WYD_SPEED6", "Przyspieszenie VI", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_IRON_GRIP3", "Żelazna Pięść Mistrza", Material.NETHERITE_INGOT, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                new SkillNode("WYD_SPEED7", "Przyspieszenie VII", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_IRON_GRIP4", "Żelazna Pięść Legendy", Material.NETHERITE_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                new SkillNode("WYD_RESONANCE3", "Żyła Główna", Material.RAW_COPPER_BLOCK, "Rezonans Rudy: szansa rośnie do 30% (z 20%), pęka do 3 sąsiednich bloków."),
                new SkillNode("WYD_SPEED8", "Przyspieszenie VIII", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_MOMENTUM2", "Impet Górnika II", Material.SUGAR, "Impet Górnika: wystarczą już 2 bloki z rzędu zamiast 3."),
                new SkillNode("WYD_IRON_GRIP5", "Nieskończona Siła", Material.BEACON, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                new SkillNode("WYD_SPEED9", "Przyspieszenie IX", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_ADRENALINE", "Adrenalina Górnika", Material.BLAZE_POWDER, "Impet Górnika daje teraz Pośpiech II zamiast Pośpiechu I."),
                new SkillNode("WYD_SPEED10", "Przyspieszenie X", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_IRON_GRIP6", "Żelazna Pięść Tytana", Material.NETHERITE_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                new SkillNode("WYD_SPEED11", "Przyspieszenie XI", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_IRON_GRIP7", "Żelazna Pięść Olbrzyma", Material.NETHERITE_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                new SkillNode("WYD_RESONANCE4", "Głęboka Żyła", Material.RAW_IRON_BLOCK, "Rezonans Rudy: szansa rośnie do 35%, pęka do 4 sąsiednich bloków."),
                new SkillNode("WYD_SPEED12", "Przyspieszenie XII", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_IRON_GRIP8", "Żelazna Pięść Kolosa", Material.NETHERITE_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                new SkillNode("WYD_SPEED13", "Przyspieszenie XIII", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_MOMENTUM3", "Impet Górnika III", Material.SUGAR, "Impet Górnika: okno na utrzymanie passy rośnie z 3s do 5s."),
                new SkillNode("WYD_IRON_GRIP9", "Żelazna Pięść Ostateczna", Material.BEACON, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                new SkillNode("WYD_SPEED14", "Przyspieszenie XIV", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_RESONANCE5", "Rdzeń Żyły", Material.RAW_IRON_BLOCK, "Rezonans Rudy: szansa rośnie do 40%, pęka do 5 sąsiednich bloków."),
                new SkillNode("WYD_SPEED15", "Przyspieszenie XV", Material.FEATHER, "Nadaje kilofowi +3% prędkości kopania."),
                new SkillNode("WYD_MASTERY", "Mistrzostwo Górnika", Material.NETHER_STAR, "Kolejne +15% do szansy na dodatkowy plon (sumuje się z resztą).")
        )),
        PRECYZJA("Precyzja", NamedTextColor.AQUA, Material.SPYGLASS, List.of(
                new SkillNode("PREC_FORT1", "Fortuna I", Material.EMERALD, "Nadaje kilofowi Fortunę I."),
                new SkillNode("PREC_FORT2", "Fortuna II", Material.EMERALD, "Nadaje kilofowi Fortunę II."),
                new SkillNode("PREC_KEEN_EYE", "Bystre Oko", Material.ENDER_EYE, "5% szansy na bonusową wypłatę $ z kopanej rudy."),
                new SkillNode("PREC_FORT3", "Fortuna III", Material.EMERALD, "Nadaje kilofowi Fortunę III."),
                new SkillNode("PREC_JEWELER", "Ręka Jubilera", Material.DIAMOND, "8% szansy na bonus $ skalowany tierem kilofa."),
                new SkillNode("PREC_CRIT", "Precyzyjne Uderzenie", Material.GOLDEN_PICKAXE, "15% szansy na krytyczne kopnięcie = podwójny plon."),
                new SkillNode("PREC_HAWK_EYE", "Oko Sokoła", Material.SPYGLASS, "Rzadkie rudy (diament/szmaragd/starożytny gruz): +10% szansy na dodatkowy plon."),
                new SkillNode("PREC_CRIT2", "Podwójna Precyzja", Material.AMETHYST_SHARD, "Kolejne +15% do szansy na krytyczne kopnięcie (sumuje się z Precyzyjnym Uderzeniem)."),
                new SkillNode("PREC_HAWK_EYE2", "Sokole Oko II", Material.SPYGLASS, "Rzadkie rudy: kolejne +10% do szansy na dodatkowy plon."),
                new SkillNode("PREC_KEEN_EYE2", "Bystrzejsze Oko", Material.GOLD_INGOT, "Kolejne, niezależne 5% szansy na bonusową wypłatę $ z kopanej rudy."),
                new SkillNode("PREC_CRIT3", "Precyzja Absolutna", Material.AMETHYST_SHARD, "Kolejne +15% do szansy na krytyczne kopnięcie (sumuje się z resztą)."),
                new SkillNode("PREC_HAWK_EYE3", "Orle Oko", Material.ENDER_EYE, "Rzadkie rudy: kolejne +10% do szansy na dodatkowy plon."),
                new SkillNode("PREC_CRIT4", "Precyzja Mistrzowska", Material.GOLDEN_PICKAXE, "Kolejne +15% do szansy na krytyczne kopnięcie (sumuje się z resztą)."),
                new SkillNode("PREC_HAWK_EYE4", "Sokole Spojrzenie", Material.SPYGLASS, "Rzadkie rudy: kolejne +10% do szansy na dodatkowy plon."),
                new SkillNode("PREC_KEEN_EYE3", "Oko Godne Króla", Material.ENDER_EYE, "Kolejne, niezależne 5% szansy na bonusową wypłatę $ z kopanej rudy."),
                new SkillNode("PREC_JEWELER2", "Ręka Mistrza Jubilera", Material.DIAMOND, "Kolejne, niezależne 8% szansy na bonus $ skalowany tierem kilofa."),
                new SkillNode("PREC_HAWK_EYE5", "Orle Spojrzenie II", Material.SPYGLASS, "Rzadkie rudy: kolejne +10% do szansy na dodatkowy plon."),
                new SkillNode("PREC_CRIT5", "Precyzja Absolutna II", Material.AMETHYST_CLUSTER, "Kolejne +15% do szansy na krytyczne kopnięcie (sumuje się z resztą)."),
                new SkillNode("PREC_KEEN_EYE4", "Bezbłędne Oko", Material.GOLD_INGOT, "Kolejne, niezależne 5% szansy na bonusową wypłatę $ z kopanej rudy."),
                new SkillNode("PREC_PERFECTION", "Perfekcja Górnika", Material.NETHER_STAR, "Kolejne, największe pojedyncze +20% do szansy na dodatkowy plon (sumuje się z resztą)."),
                new SkillNode("PREC_CRIT6", "Precyzja Legendarna", Material.GOLDEN_PICKAXE, "Kolejne +15% do szansy na krytyczne kopnięcie (sumuje się z resztą)."),
                new SkillNode("PREC_HAWK_EYE6", "Wzrok Bezlitosny", Material.SPYGLASS, "Rzadkie rudy: kolejne +10% do szansy na dodatkowy plon."),
                new SkillNode("PREC_KEEN_EYE5", "Oko Nieomylne", Material.ENDER_EYE, "Kolejne, niezależne 5% szansy na bonusową wypłatę $ z kopanej rudy."),
                new SkillNode("PREC_JEWELER3", "Ręka Złotnika", Material.DIAMOND, "Kolejne, niezależne 8% szansy na bonus $ skalowany tierem kilofa."),
                new SkillNode("PREC_CRIT7", "Precyzja Nieskończona", Material.AMETHYST_CLUSTER, "Kolejne +15% do szansy na krytyczne kopnięcie (sumuje się z resztą)."),
                new SkillNode("PREC_HAWK_EYE7", "Wzrok Nieomylny", Material.SPYGLASS, "Rzadkie rudy: kolejne +10% do szansy na dodatkowy plon."),
                new SkillNode("PREC_KEEN_EYE6", "Oko Ostateczne", Material.ENDER_EYE, "Kolejne, niezależne 5% szansy na bonusową wypłatę $ z kopanej rudy."),
                new SkillNode("PREC_CRIT8", "Precyzja Tytana", Material.GOLDEN_PICKAXE, "Kolejne +15% do szansy na krytyczne kopnięcie (sumuje się z resztą)."),
                new SkillNode("PREC_JEWELER4", "Ręka Mistrza Złotnika", Material.DIAMOND, "Kolejne, niezależne 8% szansy na bonus $ skalowany tierem kilofa."),
                new SkillNode("PREC_HAWK_EYE8", "Wzrok Ostateczny", Material.SPYGLASS, "Rzadkie rudy: kolejne +10% do szansy na dodatkowy plon."),
                new SkillNode("PREC_KEEN_EYE7", "Oko Wszechwidzące", Material.ENDER_EYE, "Kolejne, niezależne 5% szansy na bonusową wypłatę $ z kopanej rudy."),
                new SkillNode("PREC_CRIT9", "Precyzja Absolutna III", Material.AMETHYST_CLUSTER, "Kolejne +15% do szansy na krytyczne kopnięcie (sumuje się z resztą)."),
                new SkillNode("PREC_MASTERY", "Mistrzostwo Precyzji", Material.NETHER_STAR, "Kolejne, największe pojedyncze +20% do szansy na dodatkowy plon (sumuje się z resztą).")
        )),
        MAGIA("Magia", NamedTextColor.LIGHT_PURPLE, Material.AMETHYST_SHARD, List.of(
                new SkillNode("MAG_SPARK", "Iskra Many", Material.GLOWSTONE_DUST, "Kopanie ma szansę dać dodatkowe orby doświadczenia."),
                new SkillNode("MAG_INSATIABLE", "Nienasycenie", Material.COOKED_BEEF, "Kopanie kilofem nie zużywa głodu."),
                new SkillNode("MAG_HASTE1", "Aura Pośpiechu I", Material.AMETHYST_SHARD, "Trzymanie kilofa daje stały efekt Pośpiechu I."),
                new SkillNode("MAG_GRAVITY_WARD", "Grawitacyjna Odporność", Material.ANVIL, "Odporność na obrażenia od spadającego piasku/żwiru."),
                new SkillNode("MAG_HASTE2", "Aura Pośpiechu II", Material.AMETHYST_CLUSTER, "Trzymanie kilofa daje stały efekt Pośpiechu II."),
                new SkillNode("MAG_PHILOSOPHERS_STONE", "Kamień Filozoficzny", Material.NETHERITE_SCRAP, "Kopanie kamienia/brukowca ma szansę zamienić plon w cenny surowiec."),
                new SkillNode("MAG_DEPTHS_GATE", "Wrota Głębi", Material.ENDER_CHEST, "10% szansy na zdublowanie całego plonu z kopanego bloku."),
                new SkillNode("MAG_SPARK2", "Iskra Many II", Material.GLOWSTONE, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                new SkillNode("MAG_HASTE3", "Aura Pośpiechu III", Material.BUDDING_AMETHYST, "Trzymanie kilofa daje stały efekt Pośpiechu III."),
                new SkillNode("MAG_PHILOSOPHERS_STONE2", "Wzmocniony Kamień Filozoficzny", Material.CHISELED_STONE_BRICKS, "Kamień Filozoficzny: szansa rośnie do 12% (z 6%)."),
                new SkillNode("MAG_SPARK3", "Iskra Many III", Material.GLOW_ITEM_FRAME, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                new SkillNode("MAG_DEPTHS_GATE2", "Wzmocnione Wrota Głębi", Material.ENDER_EYE, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                new SkillNode("MAG_SPARK4", "Iskra Many IV", Material.GLOWSTONE, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                new SkillNode("MAG_DEPTHS_GATE3", "Brama Otchłani", Material.ENDER_CHEST, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                new SkillNode("MAG_HASTE4", "Aura Pośpiechu IV", Material.BUDDING_AMETHYST, "Trzymanie kilofa daje stały efekt Pośpiechu IV."),
                new SkillNode("MAG_PHILOSOPHERS_STONE3", "Kamień Filozoficzny Mistrza", Material.NETHERITE_INGOT, "Kamień Filozoficzny: szansa rośnie do 16% (z 12%)."),
                new SkillNode("MAG_SPARK5", "Iskra Many V", Material.GLOW_ITEM_FRAME, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                new SkillNode("MAG_GRAVITY_WARD2", "Wzmocniona Odporność Grawitacyjna", Material.ANVIL, "Odporność rozszerza się na zwykłe obrażenia od upadku (nie tylko od spadającego bloku)."),
                new SkillNode("MAG_INSATIABLE2", "Wieczna Sytość", Material.COOKED_BEEF, "Kopanie kilofem powoli regeneruje głód."),
                new SkillNode("MAG_DEPTHS_GATE4", "Wieczne Wrota Głębi", Material.END_CRYSTAL, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                new SkillNode("MAG_SPARK6", "Iskra Many VI", Material.GLOWSTONE, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                new SkillNode("MAG_DEPTHS_GATE5", "Otchłanne Wrota", Material.ENDER_CHEST, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                new SkillNode("MAG_HASTE5", "Aura Pośpiechu V", Material.BUDDING_AMETHYST, "Trzymanie kilofa daje stały efekt Pośpiechu V."),
                new SkillNode("MAG_PHILOSOPHERS_STONE4", "Kamień Filozoficzny Arcymistrza", Material.NETHERITE_INGOT, "Kamień Filozoficzny: szansa rośnie do 20% (z 16%)."),
                new SkillNode("MAG_SPARK7", "Iskra Many VII", Material.GLOW_ITEM_FRAME, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                new SkillNode("MAG_DEPTHS_GATE6", "Bezkresne Wrota Głębi", Material.ENDER_EYE, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                new SkillNode("MAG_INSATIABLE3", "Nieskończona Sytość", Material.COOKED_BEEF, "Regeneracja głodu z kopania rośnie dwukrotnie."),
                new SkillNode("MAG_SPARK8", "Iskra Many VIII", Material.GLOWSTONE, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                new SkillNode("MAG_DEPTHS_GATE7", "Ostateczne Wrota Głębi", Material.END_CRYSTAL, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                new SkillNode("MAG_LUCKY_ORE", "Szczęśliwa Ruda", Material.RABBIT_FOOT, "Kopanie rzadkich rud ma dodatkową szansę na spory wybuch orbów doświadczenia."),
                new SkillNode("MAG_SPARK9", "Iskra Many IX", Material.GLOW_ITEM_FRAME, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                new SkillNode("MAG_PHILOSOPHERS_STONE5", "Kamień Filozoficzny Wieczności", Material.NETHERITE_INGOT, "Kamień Filozoficzny: szansa rośnie do 24% (z 20%)."),
                new SkillNode("MAG_MASTERY", "Mistrzostwo Magii", Material.NETHER_STAR, "Kolejne +10% do szansy na dodatkowy plon (sumuje się z resztą).")
        ));

        public final String displayName;
        public final NamedTextColor color;
        public final Material icon;
        public final List<SkillNode> nodes;

        Branch(String displayName, NamedTextColor color, Material icon, List<SkillNode> nodes) {
            this.displayName = displayName;
            this.color = color;
            this.icon = icon;
            this.nodes = nodes;
        }
    }
}