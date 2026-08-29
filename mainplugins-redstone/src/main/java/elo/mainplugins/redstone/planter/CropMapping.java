package elo.mainplugins.redstone.planter;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;

import java.util.Map;

/**
 * v1: tylko podstawowe uprawy rzędowe na Farmland (pole zaorane). Nether wart / sadzonki /
 * dynia-arbuz (rosną OBOK, nie NAD blokiem - inny model sąsiedztwa) świadomie poza zakresem
 * v1, patrz plan §A.4 - łatwo dopisać kolejne pary tutaj, gdy zajdzie potrzeba.
 */
public final class CropMapping {

    private CropMapping() {}

    public static final Map<Material, Material> SEED_TO_CROP = Map.of(
            Material.WHEAT_SEEDS, Material.WHEAT,
            Material.CARROT, Material.CARROTS,
            Material.POTATO, Material.POTATOES,
            Material.BEETROOT_SEEDS, Material.BEETROOTS
    );

    /** Czy ten blok to jedna z upraw z {@link #SEED_TO_CROP} w pełni dojrzała (gotowa do zbioru) - używane przez Stację Zbierania. */
    public static boolean isMatureHarvestable(Block block) {
        if (!SEED_TO_CROP.containsValue(block.getType())) return false;
        return block.getBlockData() instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
    }
}
