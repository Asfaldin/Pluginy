package elo.mainplugins.tools.pickaxe;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Surowce, które mogą wypaść bonusowo przy skopaniu bruku (COBBLESTONE). Każdy tier kilofa
 * dodaje NOWE surowce do puli poprzedniego tieru (kumulatywnie) - patrz pulaDlaTieru(). Same
 * szanse % (per tier) żyją w resources/bruk-szanse.yml, nie tutaj - czyta je stamtąd
 * BrukSurowceManager.
 *
 * Wybór KONKRETNEGO surowca po udanym triggerze jest WAŻONY (pole waga), nie równomierny -
 * inaczej Złom Netherytu (jedyne źródło poza kupnem za 5000$!) miałby taką samą szansę co
 * Węgiel, co przy tierze 3-4 dawałoby absurdalnie wysokie EV na blok (patrz dyskusja przy
 * dodawaniu kart MAG_BRUK_*). Pole cenny=true oznacza surowce, których waga rośnie pod wpływem
 * karty MAG_BRUK_INSTYNKT (patrz BrukSurowceManager#losujWazony) - świadomie tylko te
 * najbardziej wartościowe/niesprzedawalne w sklepie.
 */
public final class BrukSurowce {

    private BrukSurowce() {}

    public record SurowiecDrop(String id, String nazwa, Material item, int ilosc, int waga, boolean cenny) {}

    private static final Map<Integer, List<SurowiecDrop>> NOWE_NA_TIERZE = Map.of(
            0, List.of(
                    new SurowiecDrop("WEGIEL", "Węgiel", Material.COAL, 1, 100, false),
                    new SurowiecDrop("MIEDZ", "Miedź", Material.RAW_COPPER, 1, 90, false)
            ),
            1, List.of(
                    new SurowiecDrop("LAPIS", "Lapis Lazuli", Material.LAPIS_LAZULI, 1, 70, false),
                    new SurowiecDrop("ZELAZO", "Żelazo", Material.RAW_IRON, 1, 70, false)
            ),
            2, List.of(
                    new SurowiecDrop("DIAMENT", "Diament", Material.DIAMOND, 1, 4, true),
                    new SurowiecDrop("REDSTONE", "Redstone", Material.REDSTONE, 1, 60, false),
                    new SurowiecDrop("ZLOTO", "Złoto", Material.RAW_GOLD, 1, 30, false)
            ),
            3, List.of(
                    new SurowiecDrop("EMERALD", "Szmaragd", Material.EMERALD, 1, 4, true),
                    new SurowiecDrop("KWARC", "Kwarc", Material.QUARTZ, 1, 8, true),
                    new SurowiecDrop("NETHERYT", "Netheryt", Material.NETHERITE_SCRAP, 1, 1, true)
            ),
            4, List.of(
                    new SurowiecDrop("AMETYST", "Ametyst", Material.AMETHYST_SHARD, 1, 15, true)
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
