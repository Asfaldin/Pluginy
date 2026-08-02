package elo.mainplugins;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ShopManager implements Listener {

    private final Plugin plugin;
    private final EconomyManager economyManager;
    private File sklepFile;
    private FileConfiguration sklepConfig;

    private final Map<UUID, String> playerCategory = new HashMap<>();
    private final Map<UUID, Integer> playerPage = new HashMap<>();
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    public ShopManager(Plugin plugin, EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        stworzLubWczytajPlikSklepu();
    }

    private void stworzLubWczytajPlikSklepu() {
        sklepFile = new File(plugin.getDataFolder(), "sklep.yml");
        if (!sklepFile.exists()) {
            sklepFile.getParentFile().mkdirs();
        }

        sklepConfig = YamlConfiguration.loadConfiguration(sklepFile);

        if (!sklepConfig.contains("categories")) {
            try {
                sklepConfig.set("categories.surowce.name", "Surowce");
                sklepConfig.set("categories.surowce.icon", "DIAMOND");
                sklepConfig.set("categories.surowce.slot", 10);
                sklepConfig.set("categories.surowce.items.0.material", "DIAMOND");
                sklepConfig.set("categories.surowce.items.0.slot", 0);
                sklepConfig.set("categories.surowce.items.0.amount", 1);
                sklepConfig.set("categories.surowce.items.0.buy-price", 500.0);
                sklepConfig.set("categories.surowce.items.0.sell-price", 100.0);
                sklepConfig.save(sklepFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void otworzSklep(Player player, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        playerCategory.remove(player.getUniqueId());
        playerPage.remove(player.getUniqueId());

        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Sklep Serwerowy", NamedTextColor.GOLD, TextDecoration.BOLD));
        wypelnijTlem(gui, 54);

        ConfigurationSection catSection = sklepConfig.getConfigurationSection("categories");
        if (catSection != null) {
            for (String catKey : catSection.getKeys(false)) {
                String catName = sklepConfig.getString("categories." + catKey + ".name", "Kategoria");
                String iconName = sklepConfig.getString("categories." + catKey + ".icon", "CHEST");
                int slot = sklepConfig.getInt("categories." + catKey + ".slot", 0);

                Material mat = Material.matchMaterial(iconName);
                if (mat == null) mat = Material.CHEST;

                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(Component.text(catName, NamedTextColor.YELLOW, TextDecoration.BOLD));
                meta.lore(List.of(
                        Component.text("Kliknij, aby otworzyć kategorię!", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                ));
                item.setItemMeta(meta);

                if (slot >= 0 && slot < 45) {
                    gui.setItem(slot, item);
                }
            }
        }

        // W głównym widoku przycisk całkowitego wyjścia jest na slocie 49
        ItemStack wyjscie = new ItemStack(zMenu ? Material.NETHER_STAR : Material.BARRIER);
        ItemMeta mWyjscie = wyjscie.getItemMeta();
        mWyjscie.displayName(Component.text(zMenu ? "« Wróć do Menu głównego" : "Zamknij Sklep", NamedTextColor.RED, TextDecoration.BOLD));
        wyjscie.setItemMeta(mWyjscie);
        gui.setItem(49, wyjscie);

        player.openInventory(gui);
    }

    public void otworzKategorieStrona(Player player, String catKey, int page) {
        playerCategory.put(player.getUniqueId(), catKey);
        playerPage.put(player.getUniqueId(), page);

        String catName = sklepConfig.getString("categories." + catKey + ".name", "Kategoria");
        ConfigurationSection itemsSection = sklepConfig.getConfigurationSection("categories." + catKey + ".items");

        int maxSlotFound = 0;
        if (itemsSection != null) {
            for (String itemKey : itemsSection.getKeys(false)) {
                int s = sklepConfig.getInt("categories." + catKey + ".items." + itemKey + ".slot", 0);
                if (s > maxSlotFound) maxSlotFound = s;
            }
        }
        int totalPages = Math.max(1, (int) Math.ceil((double) (maxSlotFound + 1) / 45.0));
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        Component guiTitle = (totalPages > 1)
                ? Component.text("Sklep: " + catName + " (Str. " + (page + 1) + ")", NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                : Component.text("Sklep: " + catName, NamedTextColor.DARK_GREEN, TextDecoration.BOLD);

        Inventory gui = Bukkit.createInventory(null, 54, guiTitle);
        wypelnijTlem(gui, 54);

        if (itemsSection != null) {
            int pageStartSlot = page * 45;
            int pageEndSlot = pageStartSlot + 44;

            for (String itemKey : itemsSection.getKeys(false)) {
                String path = "categories." + catKey + ".items." + itemKey + ".";
                int slot = sklepConfig.getInt(path + "slot", 0);

                if (slot >= pageStartSlot && slot <= pageEndSlot) {
                    String matName = sklepConfig.getString(path + "material", "STONE");
                    int amount = sklepConfig.getInt(path + "amount", 1);
                    double buyPrice = sklepConfig.getDouble(path + "buy-price", -1);
                    double sellPrice = sklepConfig.getDouble(path + "sell-price", -1);

                    Material material = Material.matchMaterial(matName);
                    if (material == null) material = Material.STONE;

                    ItemStack item = new ItemStack(material, Math.min(Math.max(amount, 1), 64));
                    ItemMeta meta = item.getItemMeta();
                    meta.displayName(Component.text(amount + "x " + material.name(), NamedTextColor.YELLOW, TextDecoration.BOLD));

                    List<Component> lore = new ArrayList<>();
                    if (buyPrice >= 0) lore.add(Component.text("Cena kupna: " + buyPrice + " $", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                    if (sellPrice >= 0) lore.add(Component.text("Cena sprzedaży: " + sellPrice + " $", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                    lore.add(Component.empty());
                    lore.add(Component.text("LPM - Kup paczkę (" + amount + " szt.)", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                    lore.add(Component.text("PPM - Sprzedaj paczkę (" + amount + " szt.)", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

                    meta.lore(lore);
                    item.setItemMeta(meta);

                    gui.setItem(slot - pageStartSlot, item);
                }
            }
        }

        // Pasek Nawigacyjny na Dole (Sloty 45-53)
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta metaPrev = prev.getItemMeta();
            metaPrev.displayName(Component.text("« Poprzednia Strona", NamedTextColor.YELLOW, TextDecoration.BOLD));
            prev.setItemMeta(metaPrev);
            gui.setItem(45, prev); // Strona do tyłu = Slot 45
        }

        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta metaNext = next.getItemMeta();
            metaNext.displayName(Component.text("Następna Strona »", NamedTextColor.YELLOW, TextDecoration.BOLD));
            next.setItemMeta(metaNext);
            gui.setItem(53, next); // Strona do przodu = Slot 53
        }

        // Przycisk "Powrót do Kategorii (do głównego okna Sklepu)" - Slot 48 (ZMIENIONY)
        ItemStack powrotKategorie = new ItemStack(Material.COMPASS);
        ItemMeta metaPowrot = powrotKategorie.getItemMeta();
        metaPowrot.displayName(Component.text("Cofnij do listy Kategorii", NamedTextColor.GOLD, TextDecoration.BOLD));
        powrotKategorie.setItemMeta(metaPowrot);
        gui.setItem(48, powrotKategorie);

        // Przycisk Całkowitego Wyjścia / Powrotu do /menu - Slot 50 (ZMIENIONY)
        boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
        ItemStack zamknijSklep = new ItemStack(zMenu ? Material.NETHER_STAR : Material.BARRIER);
        ItemMeta metaZamknij = zamknijSklep.getItemMeta();
        metaZamknij.displayName(Component.text(zMenu ? "« Wróć do Menu głównego" : "Zamknij Sklep", NamedTextColor.RED, TextDecoration.BOLD));
        zamknijSklep.setItemMeta(metaZamknij);
        gui.setItem(50, zamknijSklep);

        player.openInventory(gui);
    }

    private void wypelnijTlem(Inventory gui, int size) {
        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        for (int i = 0; i < size; i++) {
            gui.setItem(i, tlo);
        }
    }

    public void handleSellCommand(Player player) {
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.getType().isAir()) {
            player.sendMessage(Component.text("Nie trzymasz żadnego przedmiotu w ręce!", NamedTextColor.RED));
            return;
        }

        Material targetMat = itemInHand.getType();
        double sellPricePerUnit = znajdzCeneSprzedazy(targetMat);

        if (sellPricePerUnit < 0) {
            player.sendMessage(Component.text("Tego przedmiotu nie można sprzedać w sklepie!", NamedTextColor.RED));
            return;
        }

        int amount = itemInHand.getAmount();
        double totalEarned = sellPricePerUnit * amount;

        player.getInventory().setItemInMainHand(null);
        economyManager.dodajKase(player.getUniqueId(), totalEarned);
        player.sendMessage(Component.text("Sprzedano " + amount + "x " + targetMat.name() + " za " + totalEarned + " $!", NamedTextColor.AQUA));
    }

    public void handleSellAllCommand(Player player) {
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        if (itemInHand.getType().isAir()) {
            player.sendMessage(Component.text("Przytrzymaj w ręce przedmiot, który chcesz masowo sprzedać!", NamedTextColor.RED));
            return;
        }

        Material targetMat = itemInHand.getType();
        double sellPricePerUnit = znajdzCeneSprzedazy(targetMat);

        if (sellPricePerUnit < 0) {
            player.sendMessage(Component.text("Tego przedmiotu nie można sprzedać w sklepie!", NamedTextColor.RED));
            return;
        }

        int totalCount = 0;
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();

        for (ItemStack content : inv.getContents()) {
            if (content != null && content.getType() == targetMat) {
                totalCount += content.getAmount();
            }
        }

        if (totalCount == 0) {
            player.sendMessage(Component.text("Nie masz tego przedmiotu w ekwipunku!", NamedTextColor.RED));
            return;
        }

        inv.remove(targetMat);
        double totalEarned = sellPricePerUnit * totalCount;
        economyManager.dodajKase(player.getUniqueId(), totalEarned);
        player.sendMessage(Component.text("Sprzedano wszystkie (" + totalCount + "x) " + targetMat.name() + " za " + totalEarned + " $!", NamedTextColor.AQUA));
    }

    private double znajdzCeneSprzedazy(Material material) {
        ConfigurationSection catSection = sklepConfig.getConfigurationSection("categories");
        if (catSection == null) return -1.0;

        for (String catKey : catSection.getKeys(false)) {
            ConfigurationSection itemsSection = sklepConfig.getConfigurationSection("categories." + catKey + ".items");
            if (itemsSection != null) {
                for (String itemKey : itemsSection.getKeys(false)) {
                    String path = "categories." + catKey + ".items." + itemKey + ".";
                    String matName = sklepConfig.getString(path + "material");
                    if (matName != null && Material.matchMaterial(matName) == material) {
                        double price = sklepConfig.getDouble(path + "sell-price", -1.0);
                        int amount = sklepConfig.getInt(path + "amount", 1);
                        if (price >= 0 && amount > 0) return price / amount;
                    }
                }
            }
        }
        return -1.0;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().title().toString();
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null) return;

        // Zabezpieczenie tła
        if (clickedItem.getType() == Material.GRAY_STAINED_GLASS_PANE) {
            event.setCancelled(true);
            return;
        }

        // KLIKANIE W GŁÓWNYM SKLEPIE
        if (title.contains("Sklep Serwerowy")) {
            event.setCancelled(true);

            if (clickedItem.getType() == Material.BARRIER) {
                player.closeInventory();
                return;
            } else if (clickedItem.getType() == Material.NETHER_STAR) {
                player.closeInventory();
                player.performCommand("menu");
                return;
            }

            Material clickedMat = clickedItem.getType();
            ConfigurationSection catSection = sklepConfig.getConfigurationSection("categories");
            if (catSection == null) return;

            for (String catKey : catSection.getKeys(false)) {
                String iconName = sklepConfig.getString("categories." + catKey + ".icon", "CHEST");
                if (Material.matchMaterial(iconName) == clickedMat) {
                    otworzKategorieStrona(player, catKey, 0);
                    break;
                }
            }
            return;
        }

        // KLIKANIE W PODKATEGORIACH SKLEPU
        if (title.contains("Sklep: ")) {
            event.setCancelled(true);

            String catKey = playerCategory.get(player.getUniqueId());
            int currentPage = playerPage.getOrDefault(player.getUniqueId(), 0);
            if (catKey == null) return;

            // Logika dolnych przycisków
            if (clickedItem.getType() == Material.COMPASS) {
                boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
                otworzSklep(player, zMenu); // Wracamy do głównego widoku sklepu
                return;
            } else if (clickedItem.getType() == Material.NETHER_STAR) {
                player.closeInventory();
                player.performCommand("menu"); // Otwieramy główne /menu
                return;
            } else if (clickedItem.getType() == Material.BARRIER) {
                player.closeInventory(); // Całkowite wyjście ze sklepu
                return;
            }

            if (clickedItem.getType() == Material.SPECTRAL_ARROW) {
                if (event.getSlot() == 45) {
                    otworzKategorieStrona(player, catKey, currentPage - 1);
                } else if (event.getSlot() == 53) {
                    otworzKategorieStrona(player, catKey, currentPage + 1);
                }
                return;
            }

            int clickedSlotInGui = event.getSlot();
            if (clickedSlotInGui >= 45) return; // Zabezpieczenie przed błędem z dolnym paskiem

            int absoluteTargetSlot = (currentPage * 45) + clickedSlotInGui;
            ConfigurationSection itemsSection = sklepConfig.getConfigurationSection("categories." + catKey + ".items");
            if (itemsSection == null) return;

            for (String itemKey : itemsSection.getKeys(false)) {
                String path = "categories." + catKey + ".items." + itemKey + ".";
                if (sklepConfig.getInt(path + "slot", -1) == absoluteTargetSlot) {
                    String matName = sklepConfig.getString(path + "material");
                    if (matName == null) return;
                    Material cfgMaterial = Material.matchMaterial(matName);
                    if (cfgMaterial == null) return;

                    int amount = sklepConfig.getInt(path + "amount", 1);
                    double buyPrice = sklepConfig.getDouble(path + "buy-price", -1);
                    double sellPrice = sklepConfig.getDouble(path + "sell-price", -1);

                    if (event.isLeftClick()) {
                        if (buyPrice < 0) {
                            player.sendMessage(Component.text("Tego przedmiotu nie można kupić!", NamedTextColor.RED));
                            return;
                        }
                        if (economyManager.maWystarczajaco(player.getUniqueId(), buyPrice)) {
                            economyManager.odejmijKase(player.getUniqueId(), buyPrice);
                            player.getInventory().addItem(new ItemStack(cfgMaterial, amount));
                            player.sendMessage(Component.text("Kupiono " + amount + "x za " + buyPrice + " $!", NamedTextColor.GREEN));
                        } else {
                            player.sendMessage(Component.text("Brak pieniędzy!", NamedTextColor.RED));
                        }
                    } else if (event.isRightClick()) {
                        if (sellPrice < 0) {
                            player.sendMessage(Component.text("Tego przedmiotu nie można sprzedać!", NamedTextColor.RED));
                            return;
                        }
                        ItemStack targetStack = new ItemStack(cfgMaterial, amount);
                        if (player.getInventory().containsAtLeast(targetStack, amount)) {
                            player.getInventory().removeItem(targetStack);
                            economyManager.dodajKase(player.getUniqueId(), sellPrice);
                            player.sendMessage(Component.text("Sprzedano " + amount + "x za " + sellPrice + " $!", NamedTextColor.AQUA));
                        } else {
                            player.sendMessage(Component.text("Brak wystarczającej ilości sztuk w ekwipunku!", NamedTextColor.RED));
                        }
                    }
                    break;
                }
            }
        }
    }
}