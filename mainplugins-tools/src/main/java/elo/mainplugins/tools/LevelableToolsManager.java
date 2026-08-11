package elo.mainplugins.tools;

import elo.mainplugins.core.api.ToolsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.List;

public class LevelableToolsManager implements Listener, ToolsService {

    private final Plugin plugin;
    private final NamespacedKey keyType;
    private final NamespacedKey keyTier;
    private final NamespacedKey keyLevel;
    private final NamespacedKey keyExp;
    private final NamespacedKey keyOwner;
    private final NamespacedKey pkLevel; // klucz PDC "pk_level" - własność PickaxeSkillManager, kilof poziomuje się osobno (patrz poziomKilofa)

    private final int EXP_PER_LEVEL = 50;
    private final int MAX_LEVEL = 5;

    public LevelableToolsManager(Plugin plugin) {
        this.plugin = plugin;
        this.keyType = new NamespacedKey(plugin, "tool_type");
        this.keyTier = new NamespacedKey(plugin, "tool_tier");
        this.keyLevel = new NamespacedKey(plugin, "tool_level");
        this.keyExp = new NamespacedKey(plugin, "tool_exp");
        this.keyOwner = new NamespacedKey(plugin, "tool_owner");
        this.pkLevel = new NamespacedKey(plugin, "pk_level");
    }

    /**
     * Awaryjne/administracyjne danie kompletu narzędzi pod komendę /narzedzia (np. gracz
     * zgubił postęp, testy). NIE jest już wołane automatycznie przy pierwszym wejściu -
     * wszystkie 4 ewolujące narzędzia (włącznie z kilofem) to teraz w głównej mierze
     * nagrody z Głównej Ścieżki (mainplugins-quests), a nie start dawany za darmo.
     */
    public void dajStartoweNarzedzia(Player player) {
        player.getInventory().addItem(stworzNarzedzie(player, "pickaxe", 0));
        player.getInventory().addItem(stworzNarzedzie(player, "axe", 0));
        player.getInventory().addItem(stworzNarzedzie(player, "sword", 0));
        player.getInventory().addItem(stworzNarzedzie(player, "hoe", 0));
        player.getInventory().addItem(stworzNarzedzie(player, "shovel", 0));
        player.sendMessage(Component.text("Otrzymałeś startowe narzędzia przypisane do Twojej duszy!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /** {@inheritDoc} Wołane przez QuestManager (mainplugins-quests) po ukończeniu questa "Witaj na Wyspie". */
    @Override
    public void dajEwoluujacyKilof(Player player) {
        player.getInventory().addItem(stworzNarzedzie(player, "pickaxe", 0));
        player.sendMessage(Component.text("Otrzymałeś swój pierwszy, ewoluujący kilof!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /** {@inheritDoc} Wołane przez QuestManager po ukończeniu questa "Drwal i Siewca". */
    @Override
    public void dajEwoluujacaSiekiere(Player player) {
        player.getInventory().addItem(stworzNarzedzie(player, "axe", 0));
        player.sendMessage(Component.text("Otrzymałeś swoją pierwszą, ewoluującą siekierę!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /** {@inheritDoc} Wołane przez QuestManager po ukończeniu questa "Rolniczy Krok". */
    @Override
    public void dajEwoluujacaMotyke(Player player) {
        player.getInventory().addItem(stworzNarzedzie(player, "hoe", 0));
        player.sendMessage(Component.text("Otrzymałeś swoją pierwszą, ewoluującą motykę!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /** {@inheritDoc} Wołane przez QuestManager po ukończeniu questa "Fundusz Obronny". */
    @Override
    public void dajEwoluujacyMiecz(Player player) {
        player.getInventory().addItem(stworzNarzedzie(player, "sword", 0));
        player.sendMessage(Component.text("Otrzymałeś swój pierwszy, ewoluujący miecz!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /**
     * {@inheritDoc} Piąte narzędzie, przywrócone do gry (patrz javadoc w ToolsService) -
     * działa DOKŁADNIE tak jak pozostałe cztery (ta sama progresja tierów/poziomów przez
     * stworzNarzedzie/dodajExp), bez żadnej specjalnej flagi silk touch.
     */
    @Override
    public void dajEwoluujacaLopate(Player player) {
        player.getInventory().addItem(stworzNarzedzie(player, "shovel", 0));
        player.sendMessage(Component.text("Otrzymałeś swoją pierwszą, ewoluującą łopatę!", NamedTextColor.GREEN, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
    }

    /**
     * {@inheritDoc} Kilof NIE trzyma poziomu pod keyLevel jak reszta narzędzi - ma
     * osobny system (PickaxeSkillManager, klucz PDC "pk_level"). Czytamy tu ten sam
     * klucz (ten sam plugin => ten sam NamespacedKey) zamiast dublować logikę.
     */
    @Override
    public int poziomKilofa(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) continue;
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            if ("pickaxe".equals(pdc.get(keyType, PersistentDataType.STRING))) {
                return pdc.getOrDefault(pkLevel, PersistentDataType.INTEGER, 1);
            }
        }
        return 0;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        if (item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(keyType, PersistentDataType.STRING)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Nie możesz wyrzucić przypisanego narzędzia! Wpisz /itemy, aby je schować.", NamedTextColor.RED));
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

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR || item.getItemMeta() == null) return;

        String type = item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
        if (type != null && !type.equals("sword")) {
            if (!sprawdzWlasciciela(player, item)) {
                event.setCancelled(true);
                return;
            }
            // Kilof/siekiera/motyka mają od teraz własny system progresji (drzewko
            // umiejętności, patrz PickaxeSkillManager/AxeSkillManager/HoeSkillManager) -
            // ochrona właściciela wyżej dalej obowiązuje, ale exp/tier liczy się tam, nie tutaj.
            if (!type.equals("pickaxe") && !type.equals("axe") && !type.equals("hoe")) {
                dodajExp(player, item);
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.getType() == Material.AIR || item.getItemMeta() == null) return;

            String type = item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
            // Miecz ma od teraz własny system progresji (drzewko umiejętności, patrz
            // SwordSkillManager) - ochrona właściciela dalej obowiązuje tutaj, ale exp
            // liczy się tam, nie tutaj.
            if ("sword".equals(type)) {
                if (!sprawdzWlasciciela(player, item)) {
                    event.setCancelled(true);
                }
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

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR || item.getItemMeta() == null) return;

        String type = item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
        // Kilof/siekiera/motyka/miecz mają własny hub (Shift+PPM) - to stare menu zostaje
        // tylko dla pozostałych narzędzi.
        if (type != null && !type.equals("pickaxe") && !type.equals("axe") && !type.equals("hoe") && !type.equals("sword")) {
            if (sprawdzWlasciciela(player, item)) {
                otworzMenuUlepszen(player, item);
            }
        }
    }

    /** Typ narzędzia (np. "pickaxe") albo null, jeśli item nie jest przypisanym narzędziem - pod /addlvl. */
    public String getToolType(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getItemMeta() == null) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyType, PersistentDataType.STRING);
    }

    /** [DEBUG] Dodaje `levels` poziomów trzymanemu narzędziu (poza kilofem/siekierą - patrz PickaxeSkillManager/AxeSkillManager). */
    public void debugAddLevels(Player player, ItemStack item, int levels) {
        for (int i = 0; i < levels * EXP_PER_LEVEL; i++) {
            dodajExp(player, item);
        }
    }

    private void dodajExp(Player player, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        int tier = meta.getPersistentDataContainer().getOrDefault(keyTier, PersistentDataType.INTEGER, 0);
        int level = meta.getPersistentDataContainer().getOrDefault(keyLevel, PersistentDataType.INTEGER, 1);
        int exp = meta.getPersistentDataContainer().getOrDefault(keyExp, PersistentDataType.INTEGER, 0);
        String type = meta.getPersistentDataContainer().get(keyType, PersistentDataType.STRING);

        if (tier >= 4 && level >= MAX_LEVEL) return;

        exp += 1;

        if (exp >= EXP_PER_LEVEL) {
            exp = 0;
            level++;
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 2.0f);

            if (level > MAX_LEVEL) {
                level = 1;
                tier++;
                player.sendMessage(Component.text("Twoje narzędzie ewoluowało na wyższy poziom!", NamedTextColor.GOLD, TextDecoration.BOLD));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            } else {
                player.sendMessage(Component.text("Poziom narzędzia wzrósł: " + level, NamedTextColor.GREEN));
            }
        }

        meta.getPersistentDataContainer().set(keyTier, PersistentDataType.INTEGER, tier);
        meta.getPersistentDataContainer().set(keyLevel, PersistentDataType.INTEGER, level);
        meta.getPersistentDataContainer().set(keyExp, PersistentDataType.INTEGER, exp);
        item.setItemMeta(meta);

        aktualizujWyglad(item, type, tier, level, exp);
    }

    private void aktualizujWyglad(ItemStack item, String type, int tier, int level, int exp) {
        item.setType(pobierzMaterial(type, tier));
        ItemMeta meta = item.getItemMeta();

        String nazwa = switch(type) {
            case "pickaxe" -> "Ewoluujący Kilof";
            case "axe" -> "Ewoluująca Siekiera";
            case "sword" -> "Ewoluujący Miecz";
            case "hoe" -> "Ewoluująca Motyka";
            case "shovel" -> "Ewoluująca Łopata";
            default -> "Narzędzie";
        };

        meta.displayName(Component.text(nazwa + " [LVL " + level + "]", NamedTextColor.AQUA, TextDecoration.BOLD));

        meta.lore(List.of(
                Component.text("Tier: " + getTierName(tier), NamedTextColor.YELLOW),
                Component.text("Postęp: " + exp + " / " + EXP_PER_LEVEL + " EXP", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Prywatne narzędzie", NamedTextColor.DARK_GRAY),
                Component.text("Shift + Prawy aby ulepszyć!", NamedTextColor.LIGHT_PURPLE)
        ));

        if (meta instanceof Damageable damageable) {
            short maxDurability = item.getType().getMaxDurability();
            int totalExpInTier = ((level - 1) * EXP_PER_LEVEL) + exp;
            int maxExpInTier = MAX_LEVEL * EXP_PER_LEVEL;

            double progress = (double) totalExpInTier / maxExpInTier;
            int damage = (int) (maxDurability - (maxDurability * progress));
            damageable.setDamage(Math.min(damage, maxDurability - 1));
        }

        item.setItemMeta(meta);
    }

    private ItemStack stworzNarzedzie(Player player, String type, int tier) {
        ItemStack item = new ItemStack(pobierzMaterial(type, tier));
        ItemMeta meta = item.getItemMeta();

        meta.getPersistentDataContainer().set(keyType, PersistentDataType.STRING, type);
        meta.getPersistentDataContainer().set(keyTier, PersistentDataType.INTEGER, tier);
        meta.getPersistentDataContainer().set(keyLevel, PersistentDataType.INTEGER, 1);
        meta.getPersistentDataContainer().set(keyExp, PersistentDataType.INTEGER, 0);
        meta.getPersistentDataContainer().set(keyOwner, PersistentDataType.STRING, player.getUniqueId().toString());

        item.setItemMeta(meta);
        aktualizujWyglad(item, type, tier, 1, 0);
        return item;
    }

    private Material pobierzMaterial(String type, int tier) {
        if (tier == 0) {
            return switch(type) { case "pickaxe" -> Material.WOODEN_PICKAXE; case "axe" -> Material.WOODEN_AXE; case "sword" -> Material.WOODEN_SWORD; case "hoe" -> Material.WOODEN_HOE; case "shovel" -> Material.WOODEN_SHOVEL; default -> Material.STICK; };
        } else if (tier == 1) {
            return switch(type) { case "pickaxe" -> Material.STONE_PICKAXE; case "axe" -> Material.STONE_AXE; case "sword" -> Material.STONE_SWORD; case "hoe" -> Material.STONE_HOE; case "shovel" -> Material.STONE_SHOVEL; default -> Material.STICK; };
        } else if (tier == 2) {
            return switch(type) { case "pickaxe" -> Material.IRON_PICKAXE; case "axe" -> Material.IRON_AXE; case "sword" -> Material.IRON_SWORD; case "hoe" -> Material.IRON_HOE; case "shovel" -> Material.IRON_SHOVEL; default -> Material.STICK; };
        } else if (tier == 3) {
            return switch(type) { case "pickaxe" -> Material.DIAMOND_PICKAXE; case "axe" -> Material.DIAMOND_AXE; case "sword" -> Material.DIAMOND_SWORD; case "hoe" -> Material.DIAMOND_HOE; case "shovel" -> Material.DIAMOND_SHOVEL; default -> Material.STICK; };
        } else {
            return switch(type) { case "pickaxe" -> Material.NETHERITE_PICKAXE; case "axe" -> Material.NETHERITE_AXE; case "sword" -> Material.NETHERITE_SWORD; case "hoe" -> Material.NETHERITE_HOE; case "shovel" -> Material.NETHERITE_SHOVEL; default -> Material.STICK; };
        }
    }

    private String getTierName(int tier) {
        return switch(tier) { case 0 -> "Drewno"; case 1 -> "Kamień"; case 2 -> "Żelazo"; case 3 -> "Diament"; default -> "Netherite"; };
    }

    private void otworzMenuUlepszen(Player player, ItemStack narzedzie) {
        Inventory gui = Bukkit.createInventory(null, 27, Component.text("Ulepszenia Narzędzia", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        ItemStack tlo = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta tloMeta = tlo.getItemMeta();
        tloMeta.displayName(Component.empty());
        tlo.setItemMeta(tloMeta);
        for (int i = 0; i < 27; i++) gui.setItem(i, tlo);
        gui.setItem(13, narzedzie.clone());
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().title().equals(Component.text("Ulepszenia Narzędzia", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD))) {
            event.setCancelled(true);
        }
    }
}