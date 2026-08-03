package elo.mainplugins.tools.special;

import elo.mainplugins.core.util.CustomItemKeys;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "Niszczyciel" - specjalny kilof ze sklepu (kategoria "Itemy Specjalne", custom-id
 * "NISZCZYCIEL"). Zawsze kopie z Haste X (niezależnie od twardości bloku), PPM
 * niszczy 3x3 bloków w płaszczyźnie klikniętej ściany, a każdy zniszczony blok
 * dropi zawsze samego siebie (jak Silk Touch, bez rud/fortune). Poza kopaniem
 * nic więcej nie robi.
 */
public class NiszczycielManager implements Listener {

    private static final String CUSTOM_ID = "NISZCZYCIEL";
    private static final int HASTE_AMPLIFIKATOR = 9; // Haste X
    private static final int HASTE_CZAS_TICKOW = 60; // dłużej niż odstęp odświeżania poniżej - bez migania
    private static final int ODSTEP_ODSWIEZANIA_TICKOW = 40;

    private final Set<UUID> aktywniHaste = new HashSet<>();

    public NiszczycielManager(Plugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, this::odswiezHaste, 0L, ODSTEP_ODSWIEZANIA_TICKOW);
    }

    /** Haste sprawdzany cyklicznie (a nie tylko na zmianę slotu) - łapie też przełożenie itemu przez ekwipunek, nie tylko scroll na hotbarze. */
    private void odswiezHaste() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean trzyma = jestNiszczycielem(player.getInventory().getItemInMainHand());
            if (trzyma) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, HASTE_CZAS_TICKOW, HASTE_AMPLIFIKATOR, false, false, false));
                aktywniHaste.add(player.getUniqueId());
            } else if (aktywniHaste.remove(player.getUniqueId())) {
                player.removePotionEffect(PotionEffectType.HASTE);
            }
        }
    }

    private boolean jestNiszczycielem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String id = item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
        return CUSTOM_ID.equals(id);
    }

    /**
     * Zawsze "właściwy" drop (sam blok, jak Silk Touch) dla każdego bloku złamanego
     * Niszczycielem - obsługuje zarówno normalne LPM (realny BlockBreakEvent od gry),
     * jak i syntetyczne zdarzenia z onPPM poniżej. HIGH priority - musi zdążyć
     * sprawdzić event.isCancelled() PO tym, jak ochrona wyspy ewentualnie go anuluje.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        if (!jestNiszczycielem(event.getPlayer().getInventory().getItemInMainHand())) return;

        Material typ = event.getBlock().getType();
        event.setDropItems(false);
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), new ItemStack(typ));
    }

    @EventHandler
    @SuppressWarnings("UnstableApiUsage") // konstruktor BlockBreakEvent(Block, Player) - patrz komentarz przy callEvent niżej
    public void onPPM(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!jestNiszczycielem(player.getInventory().getItemInMainHand())) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        BlockFace face = event.getBlockFace();

        event.setCancelled(true);

        for (int[] offset : offsety3x3(face)) {
            Block target = clicked.getRelative(offset[0], offset[1], offset[2]);
            if (target.getType().isAir()) continue;

            // Prawdziwy BlockBreakEvent (nie tylko wywołanie metody) - dzięki temu
            // ochrona wyspy (IslandProtectionManager) i powyższy onBlockBreak
            // (custom drop) odpalają się dokładnie tak, jakby to było normalne kopanie.
            BlockBreakEvent be = new BlockBreakEvent(target, player);
            Bukkit.getPluginManager().callEvent(be);
            if (be.isCancelled()) continue;

            target.setType(Material.AIR, true);
        }
    }

    /** 9 przesunięć (dx,dy,dz) tworzących płaszczyznę 3x3 prostopadłą do klikniętej ściany - klikany blok jest jednym z nich (a=0,b=0). */
    private List<int[]> offsety3x3(BlockFace face) {
        List<int[]> wynik = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                switch (face) {
                    case UP, DOWN -> wynik.add(new int[]{a, 0, b});
                    case NORTH, SOUTH -> wynik.add(new int[]{a, b, 0});
                    case EAST, WEST -> wynik.add(new int[]{0, a, b});
                    default -> wynik.add(new int[]{0, 0, 0});
                }
            }
        }
        return wynik;
    }
}