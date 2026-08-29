package elo.mainplugins.redstone.chestlink;

import elo.mainplugins.redstone.item.RedstoneItemKind;
import elo.mainplugins.redstone.item.RedstoneItemManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Łącznik Skrzynek - PPM w skrzynkę wybiera ją jako źródło, PPM w DRUGĄ skrzynkę
 * kończy łącze (patrz ChestLink). Postawienie Stacji Zbiorczej obok skrzynki źródłowej
 * uruchamia golema kursującego po tym łączu (patrz GolemManager).
 */
public final class ChestLinkerListener implements Listener {

    private final RedstoneItemManager items;
    private final Map<UUID, Location> oczekujace = new HashMap<>();

    public ChestLinkerListener(RedstoneItemManager items) {
        this.items = items;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null || !jestSkrzynka(clicked)) return;

        Player player = event.getPlayer();
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (items.kindOf(inHand) != RedstoneItemKind.CHEST_LINKER) return;

        event.setCancelled(true);

        Location pierwsza = oczekujace.get(player.getUniqueId());
        if (pierwsza == null) {
            oczekujace.put(player.getUniqueId(), clicked.getLocation());
            player.sendActionBar(Component.text("Wybrano skrzynkę źródłową - kliknij drugą (docelową).", NamedTextColor.YELLOW));
            return;
        }

        if (pierwsza.equals(clicked.getLocation())) {
            player.sendActionBar(Component.text("To ta sama skrzynka - wybierz inną.", NamedTextColor.RED));
            return;
        }

        Block source = pierwsza.getBlock();
        oczekujace.remove(player.getUniqueId());
        if (!jestSkrzynka(source)) {
            player.sendActionBar(Component.text("Pierwsza skrzynka zniknęła - zacznij od nowa.", NamedTextColor.RED));
            return;
        }

        ChestLink.link(source, clicked);

        inHand.setAmount(inHand.getAmount() - 1);
        player.getInventory().setItemInMainHand(inHand.getAmount() > 0 ? inHand : null);

        player.sendActionBar(Component.text("Połączono skrzynki - postaw Stację Zbiorczą obok źródłowej.", NamedTextColor.GREEN));
    }

    private boolean jestSkrzynka(Block block) {
        return block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST;
    }
}
