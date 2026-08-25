package elo.mainplugins.tools;

import elo.mainplugins.core.api.ToolsService;
import elo.mainplugins.tools.evolving.EvolvingToolManager;
import elo.mainplugins.tools.evolving.Kategoria;
import elo.mainplugins.tools.pickaxe.PickaxeSkillManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;

/**
 * {@link ToolsService} - fasada/router dla dwóch NIEZALEŻNYCH silników narzędzi:
 * {@link PickaxeSkillManager} (Kilof Niflheim, jedyny pozostały mieszkaniec starego
 * silnika kart - patrz jego javadoc, świadomie nietknięty) i {@link EvolvingToolManager}
 * (nowy, w pełni konfigurowalny z YAML silnik dla WSZYSTKICH pozostałych ewoluujących
 * narzędzi - kilof startowy, siekiera, motyka, miecz, łopata, patrz
 * ewoluujace-narzedzia.yml). Dawny system kart dla siekiery/motyki/miecza
 * (AxeSkillManager/HoeSkillManager/SwordSkillManager) został usunięty.
 *
 * Ochrona właściciela (onDrop/onDeath/onItemDamage/onEntityDamage poniżej) dotyczy
 * WYŁĄCZNIE starego tagu "tool_type"/"tool_owner" - czyli TYLKO Kilofa Niflheim
 * (i pozostałych, już niewydawanych typów PickaxeType). Narzędzia z nowego silnika
 * (EvolvingToolManager) świadomie NIE mają tego tagu - nie są przypisane do gracza,
 * można je swobodnie wyrzucić/sprzedać/wręczyć, patrz EvolvingToolManager#stworz.
 */
public class LevelableToolsManager implements Listener, ToolsService {

    private final NamespacedKey keyType;
    private final NamespacedKey keyOwner;

    private PickaxeSkillManager pickaxeSkillManager;
    private EvolvingToolManager evolvingToolManager;

    public LevelableToolsManager(Plugin plugin) {
        this.keyType = new NamespacedKey(plugin, "tool_type");
        this.keyOwner = new NamespacedKey(plugin, "tool_owner");
    }

    public void setPickaxeSkillManager(PickaxeSkillManager pickaxeSkillManager) {
        this.pickaxeSkillManager = pickaxeSkillManager;
    }

    public void setEvolvingToolManager(EvolvingToolManager evolvingToolManager) {
        this.evolvingToolManager = evolvingToolManager;
    }

    /**
     * {@inheritDoc} Startowy kilof idzie teraz z nowego silnika - KILOF_ODKRYWCY (patrz
     * ewoluujace-narzedzia.yml), własna tekstura/model uzytkownika 2026-08-24, zastąpił
     * poprzedni placeholder KILOF_START (ten zostaje w configu jako katalog przykładów,
     * już nie wydawany graczom). Świadomie ZWYKŁA rzadkość (skromny zestaw, bez blasku) -
     * to tylko punkt startowy, siekiera-nagroda questowa (SIEKIERA_BERSERKERA) jest
     * świadomie mocniejsza/rzadsza. Niflheim zostaje wyłącznie legendarnym dropem (/@dajsniezny).
     */
    @Override
    public void dajEwoluujacyKilof(Player player) {
        player.getInventory().addItem(evolvingToolManager.stworz("KILOF_ODKRYWCY"));
        player.sendMessage(Component.text("Otrzymałeś swój pierwszy kilof - Odkrywcy!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /**
     * {@inheritDoc} Wołane przez QuestManager po ukończeniu questa "Drwal i Siewca" -
     * SIEKIERA_BERSERKERA (zastąpił placeholder SIEKIERA_START), świadomie RZADKA (mocniejsza
     * niż kilof startowy - patrz komentarz przy dajEwoluujacyKilof).
     */
    @Override
    public void dajEwoluujacaSiekiere(Player player) {
        player.getInventory().addItem(evolvingToolManager.stworz("SIEKIERA_BERSERKERA"));
        player.sendMessage(Component.text("Otrzymałeś swoją pierwszą, ewoluującą siekierę - Berserkera!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /** {@inheritDoc} Wołane przez QuestManager po ukończeniu questa "Rolniczy Krok". */
    @Override
    public void dajEwoluujacaMotyke(Player player) {
        player.getInventory().addItem(evolvingToolManager.stworz("MOTYKA_START"));
        player.sendMessage(Component.text("Otrzymałeś swoją pierwszą, ewoluującą motykę!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /** {@inheritDoc} Wołane przez QuestManager po ukończeniu questa "Pierwszy Loch". */
    @Override
    public void dajEwoluujacyMiecz(Player player) {
        player.getInventory().addItem(evolvingToolManager.stworz("MIECZ_START"));
        player.sendMessage(Component.text("Otrzymałeś swój pierwszy, ewoluujący miecz!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /** {@inheritDoc} */
    @Override
    public void dajEwoluujacaLopate(Player player) {
        player.getInventory().addItem(evolvingToolManager.stworz("LOPATA_START"));
        player.sendMessage(Component.text("Otrzymałeś swoją pierwszą, ewoluującą łopatę!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /** {@inheritDoc} Najwyższy poziom wśród WSZYSTKICH kilofów gracza - łączy nowy silnik (KILOF_START itd.) z Kilofem Niflheim (stary silnik, patrz komentarz klasy). */
    @Override
    public int poziomKilofa(Player player) {
        return Math.max(evolvingToolManager.najlepszyPoziom(player, Kategoria.PICKAXE), pickaxeSkillManager.poziomNajlepszegoKilofa(player));
    }

    /** {@inheritDoc} Delegacja do EvolvingToolManager - siekiera jest teraz w całości na nowym silniku. */
    @Override
    public int poziomSiekiery(Player player) {
        return evolvingToolManager.najlepszyPoziom(player, Kategoria.AXE);
    }

    /** {@inheritDoc} Delegacja do EvolvingToolManager - miecz jest teraz w całości na nowym silniku. */
    @Override
    public int poziomMiecza(Player player) {
        return evolvingToolManager.najlepszyPoziom(player, Kategoria.SWORD);
    }

    /** {@inheritDoc} Delegacja do PickaxeSkillManager - jedyne miejsce, które zna typ/milestone'y kilofa Niflheim. */
    @Override
    public boolean maWiecznaZime(ItemStack tool) {
        return pickaxeSkillManager.maWiecznaZime(tool);
    }

    /** {@inheritDoc} */
    @Override
    public ItemStack stworzEwoluujaceNarzedzie(String id) {
        return evolvingToolManager.stworz(id);
    }

    /** {@inheritDoc} */
    @Override
    public java.util.Set<String> ewoluujaceIds() {
        return evolvingToolManager.ids();
    }

    /** {@inheritDoc} Delegacja do PickaxeSkillManager - jedyne miejsce, które zna typ NIFLHEIM. */
    @Override
    public ItemStack stworzKilofNiflheim(Player player) {
        return pickaxeSkillManager.stworzKilof(player, elo.mainplugins.tools.pickaxe.PickaxeType.NIFLHEIM);
    }

    // ============================================ Ochrona Kilofa Niflheim ====
    // Wyłącznie stary tag "tool_type"/"tool_owner" (patrz komentarz klasy) - narzędzia
    // z nowego silnika (EvolvingToolManager) go nie mają i te listenery ich nie dotyczą.

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(keyType, PersistentDataType.STRING)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Nie możesz wyrzucić przypisanego narzędzia!", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Iterator<ItemStack> iter = event.getDrops().iterator();
        while (iter.hasNext()) {
            ItemStack item = iter.next();
            if (item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(keyType, PersistentDataType.STRING)) {
                event.getItemsToKeep().add(item);
                iter.remove();
            }
        }
    }

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (event.getItem().getItemMeta() == null) return;
        if (event.getItem().getItemMeta().getPersistentDataContainer().has(keyType, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    /** Kilof Niflheim liczy własny exp przez PickaxeSkillManager (ToolSkillManager#onBlockBreak) - tu wyłącznie pilnujemy właściciela. */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR || item.getItemMeta() == null) return;

        String type = item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
        if ("pickaxe".equals(type) && !sprawdzWlasciciela(player, item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.getType() == Material.AIR || item.getItemMeta() == null) return;

            String type = item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
            if ("pickaxe".equals(type) && !sprawdzWlasciciela(player, item)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean sprawdzWlasciciela(Player player, ItemStack item) {
        String owner = item.getItemMeta().getPersistentDataContainer().get(keyOwner, PersistentDataType.STRING);
        if (owner != null && !owner.equals(player.getUniqueId().toString())) {
            player.sendMessage(Component.text("To narzędzie jest złączone duszami z innym graczem!", NamedTextColor.RED, TextDecoration.BOLD));
            return false;
        }
        return true;
    }

    /** Typ narzędzia (np. "pickaxe") albo null - pod /@addlvl (WYŁĄCZNIE stary silnik/Niflheim, patrz MainpluginsTools). */
    public String getToolType(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getItemMeta() == null) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
    }
}
