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
                new SkillNode("WYD_IRON_GRIP3", "Żelazna Pięść Mistrza", Material.NETHERITE_INGOT, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą).")
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
                new SkillNode("PREC_HAWK_EYE3", "Orle Oko", Material.ENDER_EYE, "Rzadkie rudy: kolejne +10% do szansy na dodatkowy plon.")
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
                new SkillNode("MAG_DEPTHS_GATE2", "Wzmocnione Wrota Głębi", Material.ENDER_EYE, "Kolejne +10% do szansy na zdublowanie całego plonu.")
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