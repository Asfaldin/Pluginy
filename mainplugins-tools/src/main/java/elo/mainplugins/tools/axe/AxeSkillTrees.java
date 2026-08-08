package elo.mainplugins.tools.axe;

import elo.mainplugins.tools.skilltree.SkillBranch;
import elo.mainplugins.tools.skilltree.SkillNode;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

import java.util.List;

/**
 * Zawartość trzech gałęzi drzewka umiejętności siekiery - drugie narzędzie na silniku
 * ToolSkillManager (po kilofie, który ma osobny, ręcznie pisany system). Struktura
 * węzłów (34/33/33, te same "kształty" chainów co u kilofa) świadomie kopiuje sprawdzony
 * układ z PickaxeSkillTrees - tylko przeskórowany na tematykę rąbania drewna, żeby dało
 * się bezpiecznie wdrożyć bez kompilatora na tej maszynie (mniej nowych, niesprawdzonych
 * pomysłów na balans = mniej ryzyka).
 */
public final class AxeSkillTrees {

    private AxeSkillTrees() {}

    public static final List<SkillBranch> BRANCHES = List.of(
            new SkillBranch("CIECIE", "Siła Cięcia", NamedTextColor.GOLD, Material.DIAMOND_AXE, List.of(
                    new SkillNode("CIECIE_SPEED1", "Przyspieszenie Rąbania I", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_SPEED2", "Przyspieszenie Rąbania II", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_SPEED3", "Przyspieszenie Rąbania III", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_OSTRZE1", "Ostre Ostrze I", Material.IRON_INGOT, "10% szansy na podwójny plon z rąbanego drewna."),
                    new SkillNode("CIECIE_KOMBO1", "Rytm Drwala", Material.SUGAR, "3 kłody z rzędu w 3s dają Pośpiech I na 4s."),
                    new SkillNode("CIECIE_SPEED4", "Przyspieszenie Rąbania IV", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_TRZASK1", "Trzask Konarów I", Material.OAK_LOG, "20% szansy, że sąsiedni blok drewna pęknie razem z rąbanym."),
                    new SkillNode("CIECIE_SPEED5", "Przyspieszenie Rąbania V", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_OSTRZE2", "Ostre Ostrze II", Material.IRON_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                    new SkillNode("CIECIE_TRZASK2", "Trzask Konarów II", Material.DARK_OAK_LOG, "Trzask Konarów pęka teraz do 2 sąsiednich bloków zamiast 1."),
                    new SkillNode("CIECIE_SPEED6", "Przyspieszenie Rąbania VI", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_OSTRZE3", "Ostre Ostrze III", Material.NETHERITE_INGOT, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                    new SkillNode("CIECIE_SPEED7", "Przyspieszenie Rąbania VII", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_OSTRZE4", "Ostre Ostrze IV", Material.NETHERITE_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                    new SkillNode("CIECIE_TRZASK3", "Trzask Konarów III", Material.SPRUCE_LOG, "Trzask Konarów: szansa rośnie do 30% (z 20%), pęka do 3 sąsiednich bloków."),
                    new SkillNode("CIECIE_SPEED8", "Przyspieszenie Rąbania VIII", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_KOMBO2", "Rytm Drwala II", Material.SUGAR, "Rytm Drwala: wystarczą już 2 kłody z rzędu zamiast 3."),
                    new SkillNode("CIECIE_OSTRZE5", "Ostre Ostrze V", Material.BEACON, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                    new SkillNode("CIECIE_SPEED9", "Przyspieszenie Rąbania IX", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_WSCIEKLOSC", "Szał Drwala", Material.BLAZE_POWDER, "Rytm Drwala daje teraz Pośpiech II zamiast Pośpiechu I."),
                    new SkillNode("CIECIE_SPEED10", "Przyspieszenie Rąbania X", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_OSTRZE6", "Ostre Ostrze VI", Material.NETHERITE_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                    new SkillNode("CIECIE_SPEED11", "Przyspieszenie Rąbania XI", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_OSTRZE7", "Ostre Ostrze VII", Material.NETHERITE_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                    new SkillNode("CIECIE_TRZASK4", "Trzask Konarów IV", Material.BIRCH_LOG, "Trzask Konarów: szansa rośnie do 35%, pęka do 4 sąsiednich bloków."),
                    new SkillNode("CIECIE_SPEED12", "Przyspieszenie Rąbania XII", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_OSTRZE8", "Ostre Ostrze VIII", Material.NETHERITE_BLOCK, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                    new SkillNode("CIECIE_SPEED13", "Przyspieszenie Rąbania XIII", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_KOMBO3", "Rytm Drwala III", Material.SUGAR, "Rytm Drwala: okno na utrzymanie passy rośnie z 3s do 5s."),
                    new SkillNode("CIECIE_OSTRZE9", "Ostre Ostrze IX", Material.BEACON, "Kolejne +10% do szansy na podwójny plon (sumuje się z resztą)."),
                    new SkillNode("CIECIE_SPEED14", "Przyspieszenie Rąbania XIV", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_TRZASK5", "Trzask Konarów V", Material.JUNGLE_LOG, "Trzask Konarów: szansa rośnie do 40%, pęka do 5 sąsiednich bloków."),
                    new SkillNode("CIECIE_SPEED15", "Przyspieszenie Rąbania XV", Material.FEATHER, "Nadaje siekierze +3% prędkości rąbania."),
                    new SkillNode("CIECIE_MISTRZOSTWO", "Mistrzostwo Siły", Material.NETHER_STAR, "Kolejne +15% do szansy na dodatkowy plon (sumuje się z resztą).")
            )),
            new SkillBranch("LESNICTWO", "Leśnictwo", NamedTextColor.AQUA, Material.APPLE, List.of(
                    new SkillNode("LES_FORT1", "Obfitość Lasu I", Material.EMERALD, "Nadaje siekierze Fortunę I."),
                    new SkillNode("LES_FORT2", "Obfitość Lasu II", Material.EMERALD, "Nadaje siekierze Fortunę II."),
                    new SkillNode("LES_ZLOTY_KCIUK1", "Zielony Kciuk I", Material.OAK_SAPLING, "5% szansy na dodatkową sadzonkę lub jabłko przy rąbaniu."),
                    new SkillNode("LES_FORT3", "Obfitość Lasu III", Material.EMERALD, "Nadaje siekierze Fortunę III."),
                    new SkillNode("LES_TRACZ1", "Ręka Tracza I", Material.DIAMOND, "8% szansy na bonus $ skalowany tierem siekiery."),
                    new SkillNode("LES_CIOS1", "Precyzyjny Cios I", Material.GOLDEN_AXE, "15% szansy na krytyczne rąbnięcie = podwójny plon."),
                    new SkillNode("LES_OKO1", "Oko Handlarza I", Material.ENDER_EYE, "5% szansy na bonusową wypłatę $ z rąbanego drewna."),
                    new SkillNode("LES_CIOS2", "Precyzyjny Cios II", Material.AMETHYST_SHARD, "Kolejne +15% do szansy na krytyczne rąbnięcie (sumuje się z resztą)."),
                    new SkillNode("LES_ZLOTY_KCIUK2", "Zielony Kciuk II", Material.SPRUCE_SAPLING, "Kolejna, niezależna 5% szansa na dodatkową sadzonkę/jabłko."),
                    new SkillNode("LES_OKO2", "Oko Handlarza II", Material.GOLD_INGOT, "Kolejne, niezależne 5% szansy na bonusową wypłatę $."),
                    new SkillNode("LES_CIOS3", "Precyzyjny Cios III", Material.AMETHYST_SHARD, "Kolejne +15% do szansy na krytyczne rąbnięcie (sumuje się z resztą)."),
                    new SkillNode("LES_ZLOTY_KCIUK3", "Zielony Kciuk III", Material.BIRCH_SAPLING, "Kolejna, niezależna 5% szansa na dodatkową sadzonkę/jabłko."),
                    new SkillNode("LES_CIOS4", "Precyzyjny Cios IV", Material.GOLDEN_AXE, "Kolejne +15% do szansy na krytyczne rąbnięcie (sumuje się z resztą)."),
                    new SkillNode("LES_ZLOTY_KCIUK4", "Zielony Kciuk IV", Material.JUNGLE_SAPLING, "Kolejna, niezależna 5% szansa na dodatkową sadzonkę/jabłko."),
                    new SkillNode("LES_OKO3", "Oko Handlarza III", Material.ENDER_EYE, "Kolejne, niezależne 5% szansy na bonusową wypłatę $."),
                    new SkillNode("LES_TRACZ2", "Ręka Tracza II", Material.DIAMOND, "Kolejne, niezależne 8% szansy na bonus $ skalowany tierem siekiery."),
                    new SkillNode("LES_ZLOTY_KCIUK5", "Zielony Kciuk V", Material.ACACIA_SAPLING, "Kolejna, niezależna 5% szansa na dodatkową sadzonkę/jabłko."),
                    new SkillNode("LES_CIOS5", "Precyzyjny Cios V", Material.AMETHYST_CLUSTER, "Kolejne +15% do szansy na krytyczne rąbnięcie (sumuje się z resztą)."),
                    new SkillNode("LES_ZLOTY_KCIUK6", "Zielony Kciuk VI", Material.DARK_OAK_SAPLING, "Kolejna, niezależna 5% szansa na dodatkową sadzonkę/jabłko."),
                    new SkillNode("LES_CIOS6", "Precyzyjny Cios VI", Material.GOLDEN_AXE, "Kolejne +15% do szansy na krytyczne rąbnięcie (sumuje się z resztą)."),
                    new SkillNode("LES_OKO4", "Oko Handlarza IV", Material.ENDER_EYE, "Kolejne, niezależne 5% szansy na bonusową wypłatę $."),
                    new SkillNode("LES_TRACZ3", "Ręka Tracza III", Material.DIAMOND, "Kolejne, niezależne 8% szansy na bonus $ skalowany tierem siekiery."),
                    new SkillNode("LES_CIOS7", "Precyzyjny Cios VII", Material.AMETHYST_CLUSTER, "Kolejne +15% do szansy na krytyczne rąbnięcie (sumuje się z resztą)."),
                    new SkillNode("LES_ZLOTY_KCIUK7", "Zielony Kciuk VII", Material.MANGROVE_PROPAGULE, "Kolejna, niezależna 5% szansa na dodatkową sadzonkę/jabłko."),
                    new SkillNode("LES_OKO5", "Oko Handlarza V", Material.GOLD_INGOT, "Kolejne, niezależne 5% szansy na bonusową wypłatę $."),
                    new SkillNode("LES_CIOS8", "Precyzyjny Cios VIII", Material.GOLDEN_AXE, "Kolejne +15% do szansy na krytyczne rąbnięcie (sumuje się z resztą)."),
                    new SkillNode("LES_TRACZ4", "Ręka Tracza IV", Material.DIAMOND, "Kolejne, niezależne 8% szansy na bonus $ skalowany tierem siekiery."),
                    new SkillNode("LES_OKO6", "Oko Handlarza VI", Material.ENDER_EYE, "Kolejne, niezależne 5% szansy na bonusową wypłatę $."),
                    new SkillNode("LES_ZLOTY_KCIUK8", "Zielony Kciuk VIII", Material.CHERRY_SAPLING, "Kolejna, niezależna 5% szansa na dodatkową sadzonkę/jabłko."),
                    new SkillNode("LES_CIOS9", "Precyzyjny Cios IX", Material.AMETHYST_CLUSTER, "Kolejne +15% do szansy na krytyczne rąbnięcie (sumuje się z resztą)."),
                    new SkillNode("LES_OKO7", "Oko Handlarza VII", Material.ENDER_EYE, "Kolejne, niezależne 5% szansy na bonusową wypłatę $."),
                    new SkillNode("LES_ZLOTY_KCIUK9", "Zielony Kciuk IX", Material.AZALEA, "Kolejna, niezależna 5% szansa na dodatkową sadzonkę/jabłko."),
                    new SkillNode("LES_MISTRZOSTWO", "Mistrzostwo Leśnictwa", Material.NETHER_STAR, "Kolejne, największe pojedyncze +20% do szansy na dodatkowy plon (sumuje się z resztą).")
            )),
            new SkillBranch("NATURA", "Natura", NamedTextColor.LIGHT_PURPLE, Material.OAK_SAPLING, List.of(
                    new SkillNode("NAT_DUCH1", "Duch Puszczy I", Material.GLOWSTONE_DUST, "Rąbanie ma szansę dać dodatkowe orby doświadczenia."),
                    new SkillNode("NAT_GLOD1", "Leśny Głód", Material.COOKED_BEEF, "Rąbanie siekierą nie zużywa głodu."),
                    new SkillNode("NAT_WIATR1", "Wiatr Lasu I", Material.AMETHYST_SHARD, "Trzymanie siekiery daje stały efekt Pośpiechu I."),
                    new SkillNode("NAT_LADOWANIE1", "Miękkie Lądowanie I", Material.ANVIL, "Odporność na obrażenia od spadającego piasku/żwiru."),
                    new SkillNode("NAT_WIATR2", "Wiatr Lasu II", Material.AMETHYST_CLUSTER, "Trzymanie siekiery daje stały efekt Pośpiechu II."),
                    new SkillNode("NAT_DUCH_DRZEWA1", "Duch Drzewa I", Material.NETHERITE_SCRAP, "Rąbanie drewna ma szansę zamienić plon w cenny surowiec."),
                    new SkillNode("NAT_PIEN1", "Podwójny Pień I", Material.ENDER_CHEST, "10% szansy na zdublowanie całego plonu z rąbanego bloku."),
                    new SkillNode("NAT_DUCH2", "Duch Puszczy II", Material.GLOWSTONE, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                    new SkillNode("NAT_WIATR3", "Wiatr Lasu III", Material.BUDDING_AMETHYST, "Trzymanie siekiery daje stały efekt Pośpiechu III."),
                    new SkillNode("NAT_DUCH_DRZEWA2", "Duch Drzewa II", Material.CHISELED_STONE_BRICKS, "Duch Drzewa: szansa rośnie do 12% (z 6%)."),
                    new SkillNode("NAT_DUCH3", "Duch Puszczy III", Material.GLOW_ITEM_FRAME, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                    new SkillNode("NAT_PIEN2", "Podwójny Pień II", Material.ENDER_EYE, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                    new SkillNode("NAT_DUCH4", "Duch Puszczy IV", Material.GLOWSTONE, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                    new SkillNode("NAT_PIEN3", "Podwójny Pień III", Material.ENDER_CHEST, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                    new SkillNode("NAT_WIATR4", "Wiatr Lasu IV", Material.BUDDING_AMETHYST, "Trzymanie siekiery daje stały efekt Pośpiechu IV."),
                    new SkillNode("NAT_DUCH_DRZEWA3", "Duch Drzewa III", Material.NETHERITE_INGOT, "Duch Drzewa: szansa rośnie do 16% (z 12%)."),
                    new SkillNode("NAT_DUCH5", "Duch Puszczy V", Material.GLOW_ITEM_FRAME, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                    new SkillNode("NAT_LADOWANIE2", "Miękkie Lądowanie II", Material.ANVIL, "Odporność rozszerza się na zwykłe obrażenia od upadku (nie tylko od spadającego bloku)."),
                    new SkillNode("NAT_GLOD2", "Leśny Głód II", Material.COOKED_BEEF, "Rąbanie siekierą powoli regeneruje głód."),
                    new SkillNode("NAT_PIEN4", "Podwójny Pień IV", Material.END_CRYSTAL, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                    new SkillNode("NAT_DUCH6", "Duch Puszczy VI", Material.GLOWSTONE, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                    new SkillNode("NAT_PIEN5", "Podwójny Pień V", Material.ENDER_CHEST, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                    new SkillNode("NAT_WIATR5", "Wiatr Lasu V", Material.BUDDING_AMETHYST, "Trzymanie siekiery daje stały efekt Pośpiechu V."),
                    new SkillNode("NAT_DUCH_DRZEWA4", "Duch Drzewa IV", Material.NETHERITE_INGOT, "Duch Drzewa: szansa rośnie do 20% (z 16%)."),
                    new SkillNode("NAT_DUCH7", "Duch Puszczy VII", Material.GLOW_ITEM_FRAME, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                    new SkillNode("NAT_PIEN6", "Podwójny Pień VI", Material.ENDER_EYE, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                    new SkillNode("NAT_GLOD3", "Leśny Głód III", Material.COOKED_BEEF, "Regeneracja głodu z rąbania rośnie dwukrotnie."),
                    new SkillNode("NAT_DUCH8", "Duch Puszczy VIII", Material.GLOWSTONE, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                    new SkillNode("NAT_PIEN7", "Podwójny Pień VII", Material.END_CRYSTAL, "Kolejne +10% do szansy na zdublowanie całego plonu."),
                    new SkillNode("NAT_SZCZESLIWE", "Szczęśliwe Drzewo", Material.RABBIT_FOOT, "Rąbanie rzadkiego drewna ma dodatkową szansę na spory wybuch orbów doświadczenia."),
                    new SkillNode("NAT_DUCH9", "Duch Puszczy IX", Material.GLOW_ITEM_FRAME, "Kolejna, niezależna szansa na dodatkowe orby doświadczenia."),
                    new SkillNode("NAT_DUCH_DRZEWA5", "Duch Drzewa V", Material.NETHERITE_INGOT, "Duch Drzewa: szansa rośnie do 24% (z 20%)."),
                    new SkillNode("NAT_MISTRZOSTWO", "Mistrzostwo Natury", Material.NETHER_STAR, "Kolejne +10% do szansy na dodatkowy plon (sumuje się z resztą).")
            ))
    );
}