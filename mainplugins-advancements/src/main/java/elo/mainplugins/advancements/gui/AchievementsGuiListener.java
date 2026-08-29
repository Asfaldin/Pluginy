package elo.mainplugins.advancements.gui;

import elo.mainplugins.advancements.AchievementManager;
import elo.mainplugins.advancements.model.AchievementDef;
import elo.mainplugins.core.util.MenuBridge;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Obsługa kliknięć w panelu osiągnięć. GUI jest tylko do odczytu - każdy klik jest
 * anulowany; jedyna akcja "zapisująca" to odbiór nagrody za zdobyte osiągnięcie.
 */
public final class AchievementsGuiListener implements Listener {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final AchievementManager manager;
    private final AchievementsGui gui;

    public AchievementsGuiListener(AchievementManager manager, AchievementsGui gui) {
        this.manager = manager;
        this.gui = gui;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AchievementsGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AchievementsGuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) {
            return; // klik we własny ekwipunek
        }

        int rozmiar = manager.config().gui().rozmiar();
        int base = rozmiar - 9;

        AchievementDef def = holder.sloty.get(slot);
        if (def != null) {
            obsluzOdbior(player, def, holder);
            return;
        }

        if (slot == base) {
            holder.strona--;
            gui.renderuj(player, holder);
        } else if (slot == base + 8) {
            holder.strona++;
            gui.renderuj(player, holder);
        } else if (slot == base + 1) {
            holder.kategoriaIndex--;
            holder.strona = 0;
            gui.renderuj(player, holder);
        } else if (slot == base + 7) {
            holder.kategoriaIndex++;
            holder.strona = 0;
            gui.renderuj(player, holder);
        } else if (slot == base + 4) {
            if (holder.zMenu()) {
                MenuBridge.returnToMainMenu(player);
            } else {
                player.closeInventory();
            }
        }
    }

    private void obsluzOdbior(Player player, AchievementDef def, AchievementsGuiHolder holder) {
        switch (manager.odbierz(player, def)) {
            case ODEBRANO -> {
                player.playSound(player.getLocation(), "entity.experience_orb.pickup", 1.0f, 1.0f);
                gui.renderuj(player, holder);
            }
            case JUZ_ODEBRANE -> player.sendMessage(SER.deserialize("&7Nagroda za to osiągnięcie została już odebrana."));
            case NIE_UKONCZONE -> player.sendMessage(SER.deserialize("&cNie masz jeszcze tego osiągnięcia."));
            case BRAK_NAGROD -> player.sendMessage(SER.deserialize("&7To osiągnięcie nie ma nagrody do odebrania."));
        }
    }
}
