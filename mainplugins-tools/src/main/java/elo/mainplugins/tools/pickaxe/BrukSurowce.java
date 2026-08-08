package elo.mainplugins.tools.pickaxe;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Surowce, które mogą pojawić się jako blok rudy w miejscu skopanego bruku (COBBLESTONE).
 * Każdy tier kilofa dodaje NOWE surowce do puli poprzedniego tieru (kumulatywnie) - patrz
 * pulaDlaTieru(). Same szanse % (per tier) żyją w resources/bruk-szanse.yml, nie tutaj -
 * czyta je stamtąd BrukSurowceManager.
 */
public final class BrukSurowce {

    private BrukSurowce() {}

    public record SurowiecDrop(String id, String nazwa, Material blok, Material item, int ilosc) {}

    private static final Map<Integer, List<SurowiecDrop>> NOWE_NA_TIERZE = Map.of(
            0, List.of(
                    new SurowiecDrop("WEGIEL", "Węgiel", Material.COAL_ORE, Material.COAL, 1),
                    new SurowiecDrop("MIEDZ", "Miedź", Material.COPPER_ORE, Material.RAW_COPPER, 1)
            ),
            1, List.of(
                    new SurowiecDrop("LAPIS", "Lapis Lazuli", Material.LAPIS_ORE, Material.LAPIS_LAZULI, 1),
                    new SurowiecDrop("ZELAZO", "Żelazo", Material.IRON_ORE, Material.RAW_IRON, 1)
            ),
            2, List.of(
                    new SurowiecDrop("DIAMENT", "Diament", Material.DIAMOND_ORE, Material.DIAMOND, 1),
                    new SurowiecDrop("REDSTONE", "Redstone", Material.REDSTONE_ORE, Material.REDSTONE, 1),
                    new SurowiecDrop("ZLOTO", "Złoto", Material.GOLD_ORE, Material.RAW_GOLD, 1)
            ),
            3, List.of(
                    new SurowiecDrop("EMERALD", "Szmaragd", Material.EMERALD_ORE, Material.EMERALD, 1),
                    new SurowiecDrop("KWARC", "Kwarc", Material.NETHER_QUARTZ_ORE, Material.QUARTZ, 1),
                    new SurowiecDrop("NETHERYT", "Netheryt", Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP, 1)
            ),
            4, List.of(
                    new SurowiecDrop("AMETYST", "Ametyst", Material.AMETHYST_BLOCK, Material.AMETHYST_SHARD, 1)
            )
    );

    /** Kumulatywna pula dla danego tieru - wszystko z tierów 0..tier włącznie. */
    public static List<SurowiecDrop> pulaDlaTieru(int tier) {
        List<SurowiecDrop> pula = new ArrayList<>();
        for (int i = 0; i <= tier && i <= 4; i++) {
            pula.addAll(NOWE_NA_TIERZE.getOrDefault(i, List.of()));
        }
        return pula;
    }
}
