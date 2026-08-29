package elo.mainplugins.advancements.listener;

import elo.mainplugins.advancements.AchievementManager;
import elo.mainplugins.advancements.CustomTriggerChecker;
import elo.mainplugins.advancements.model.AchievementDef;
import elo.mainplugins.advancements.model.TriggerSource;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Dwa wejścia do systemu:
 * <ul>
 *   <li>{@link PlayerAdvancementDoneEvent} - gracz właśnie dobił wbudowany advancement;
 *       jeśli któreś osiągnięcie jest z nim powiązane ({@link TriggerSource.Vanilla}),
 *       zaliczamy je i pokazujemy nasze powiadomienie (obok natywnego toastu Minecrafta).</li>
 *   <li>{@link PlayerJoinEvent} - po chwili od wejścia ciche wyrównanie stanu
 *       (patrz {@link CustomTriggerChecker#synchronizujNaWejsciu}).</li>
 * </ul>
 */
public final class AdvancementListeners implements Listener {

    private final Plugin plugin;
    private final AchievementManager manager;
    private final CustomTriggerChecker checker;

    public AdvancementListeners(Plugin plugin, AchievementManager manager, CustomTriggerChecker checker) {
        this.plugin = plugin;
        this.manager = manager;
        this.checker = checker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        NamespacedKey klucz = event.getAdvancement().getKey();
        if (klucz.getKey().startsWith("recipes/")) {
            return;
        }
        // Nasz własny datapack (namespace z configu) - to my go zaliczyliśmy, nie odsyłaj echa.
        if (klucz.getNamespace().equals(manager.config().datapack().namespace())) {
            return;
        }
        for (AchievementDef def : manager.config().osiagniecia()) {
            if (def.zrodlo() instanceof TriggerSource.Vanilla v
                    && v.key().equals(klucz)
                    && !manager.ukonczone(event.getPlayer().getUniqueId(), def.id())) {
                manager.oznaczUkonczone(event.getPlayer(), def);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            checker.synchronizujNaWejsciu(event.getPlayer());
            manager.synchronizujNatywne(event.getPlayer());
            manager.sprobujWydacOczekujace(event.getPlayer());
        }, 40L);
    }

    /** Zamknięcie dowolnego ekwipunku - dobra chwila, żeby spróbować dostarczyć zaległe nagrody (mogło zwolnić się miejsce). */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> manager.sprobujWydacOczekujace(player));
        }
    }
}
