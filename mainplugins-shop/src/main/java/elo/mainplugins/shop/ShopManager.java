package elo.mainplugins.shop;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.CustomItemKeys;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.*;

/**
 * Cała treść sklepu (kategorie, ceny, ikonki, ilości) żyje w sklep.yml, edytowalnym
 * na żywo przez /reloadsklep - ta klasa tylko RENDERUJE tę konfigurację i obsługuje
 * kliknięcia. Sam plik jest generowany zewnętrznie (cennik) i dostarczany jako
 * domyślny zasób w resources/sklep.yml - przy pierwszym uruchomieniu (brak pliku
 * w data folderze) jest kopiowany 1:1 przez saveResource(); jeśli plik już istnieje
 * (admin coś zmienił / serwer już działał), nic w nim nie ruszamy.
 *
 * buy-price/sell-price są CAŁKOWITE (cena za cały lot: amount przy kupnie, sell-amount
 * przy sprzedaży) - patrz nagłówek resources/sklep.yml. Odczyt w tej klasie leci przez
 * getInt(), więc ułamek wpisany ręcznie przez admina zostanie po cichu obcięty; stąd
 * ostrzezZaNieCalkowiteCeny() przy każdym wczytaniu pliku.
 */
public class ShopManager implements Listener {

    private final Plugin plugin;
    private final EconomyService economyManager;
    private File sklepFile;
    private FileConfiguration sklepConfig;

    private final Map<UUID, String> playerCategory = new HashMap<>();
    private final Map<UUID, Integer> playerPage = new HashMap<>();
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    public ShopManager(Plugin plugin, EconomyService economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        stworzLubWczytajPlikSklepu();
    }

    /**
     * Wczytuje sklep.yml z dysku od nowa, bez restartu serwera - pod komendę /reloadsklep.
     * Nie rusza otwartych aktualnie GUI graczy (te po prostu pokazują poprzedni stan
     * do czasu, aż gracz je zamknie i otworzy ponownie).
     */
    public void przeladujKonfiguracje() {
        sklepConfig = YamlConfiguration.loadConfiguration(sklepFile);
        ostrzezZaNieCalkowiteCeny();
    }

    private void stworzLubWczytajPlikSklepu() {
        sklepFile = new File(plugin.getDataFolder(), "sklep.yml");
        if (!sklepFile.exists()) {
            // Domyślny cennik żyje jako zasób pluginu (resources/sklep.yml) - kopiujemy
            // go 1:1 tylko przy pierwszym uruchomieniu. Jeśli plik już istnieje, nic tu
            // nie nadpisujemy - ewentualne ręczne zmiany admina zostają nietknięte.
            plugin.saveResource("sklep.yml", false);
        }
        sklepConfig = YamlConfiguration.loadConfiguration(sklepFile);
        ostrzezZaNieCalkowiteCeny();
    }

    /**
     * buy-price/sell-price mają być liczbami całkowitymi (cena za cały lot) - odczyt
     * w tej klasie leci przez getInt(), który po cichu obcina ułamki bez żadnego błędu
     * (np. 8.75 -> 8, więc gracz sprzedałby coś za grosze zamiast za pełną cenę). Ten
     * check wyłapuje taki wpis w logu od razu przy starcie/reloadzie, zamiast przez
     * skargę gracza, że sklep płaci mniej niż powinien.
     */
    private void ostrzezZaNieCalkowiteCeny() {
        ConfigurationSection catSection = sklepConfig.getConfigurationSection("categories");
        if (catSection == null) return;

        for (String catKey : catSection.getKeys(false)) {
            ConfigurationSection itemsSection = sklepConfig.getConfigurationSection("categories." + catKey + ".items");
            if (itemsSection == null) continue;

            for (String itemKey : itemsSection.getKeys(false)) {
                String path = "categories." + catKey + ".items." + itemKey + ".";
                ostrzezJesliNieCalkowita(path + "buy-price");
                ostrzezJesliNieCalkowita(path + "sell-price");
            }
        }
    }

    private void ostrzezJesliNieCalkowita(String path) {
        if (!sklepConfig.contains(path)) return;
        double wartosc = sklepConfig.getDouble(path);
        if (wartosc != Math.floor(wartosc)) {
            plugin.getLogger().warning("sklep.yml: '" + path + "' = " + wartosc
                    + " nie jest liczbą całkowitą - zostanie obcięta przy odczycie (getInt) do "
                    + (int) wartosc + "!");
        }
    }

    /**
     * Zwykła siatka: kategorie/itemy wypełniają sloty 0..GRID_ROZMIAR-1 po kolei,
     * od lewej górnej, rząd po rzędzie (5 rzędów po 9 = sloty 0-44). Dolny pasek
     * (45-53) jest zawsze zarezerwowany na przyciski nawigacji - patrz otworzSklep()/
     * otworzKategorieStrona().
     */
    private static final int GRID_ROZMIAR = 45;

    /**
     * Układ ekranu głównego: siatka 7x2 wyśrodkowana w oknie 6x9 (rzędy 3-4,
     * kolumny 2-8). Dokładnie 14 pozycji = tyle, ile kategorii w sklep.yml.
     */
    private static final int[] SLOTY_MENU_KATEGORII = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    /** Puste, bezimienne szkło do wypełniania tła GUI. */
    private ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    /** Jednolite szare tło na całym oknie ekranu głównego sklepu. */
    private void wypelnijTloSzare(Inventory gui) {
        ItemStack szare = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < gui.getSize(); i++) {
            gui.setItem(i, szare);
        }
    }

    // ==================================================================== GUI ====

    public void otworzSklep(Player player, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        playerCategory.remove(player.getUniqueId());
        playerPage.remove(player.getUniqueId());

        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Sklep Serwerowy", NamedTextColor.GOLD, TextDecoration.BOLD));
        wypelnijTloSzare(gui);

        ConfigurationSection catSection = sklepConfig.getConfigurationSection("categories");
        if (catSection != null) {
            int idx = 0;
            for (String catKey : catSection.getKeys(false)) {
                if (idx >= SLOTY_MENU_KATEGORII.length) break; // więcej kategorii niż miejsc w siatce - reszta się nie zmieści

                String catName = sklepConfig.getString("categories." + catKey + ".name", "Kategoria");
                String iconName = sklepConfig.getString("categories." + catKey + ".icon", "CHEST");

                Material mat = Material.matchMaterial(iconName);
                if (mat == null) mat = Material.CHEST;

                ItemStack item = new ItemStack(mat);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(Component.text(catName, NamedTextColor.YELLOW, TextDecoration.BOLD));
                meta.lore(List.of(
                        Component.text("Kliknij, aby otworzyć kategorię!", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                ));
                // Połysk na każdej ikonie kategorii - czysto kosmetyczne, "premium" wrażenie w GUI.
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                item.setItemMeta(meta);

                gui.setItem(SLOTY_MENU_KATEGORII[idx], item);
                idx++;
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
        // Kolejność w sklep.yml (klucze "0","1","2"...) = kolejność wypełniania siatki -
        // ta lista jest jedynym źródłem prawdy o tym, co ląduje w którym slocie GUI,
        // więc render (niżej) i rozpoznawanie kliknięcia (onInventoryClick) muszą liczyć
        // po niej identycznie.
        List<String> itemKeys = itemsSection != null ? new ArrayList<>(itemsSection.getKeys(false)) : List.of();

        int totalPages = Math.max(1, (int) Math.ceil((double) itemKeys.size() / GRID_ROZMIAR));
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        Component guiTitle = (totalPages > 1)
                ? Component.text("Sklep: " + catName + " (Str. " + (page + 1) + ")", NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                : Component.text("Sklep: " + catName, NamedTextColor.DARK_GREEN, TextDecoration.BOLD);

        Inventory gui = Bukkit.createInventory(null, 54, guiTitle);

        int pageStart = page * GRID_ROZMIAR;
        int pageEnd = Math.min(pageStart + GRID_ROZMIAR, itemKeys.size());

        for (int i = pageStart; i < pageEnd; i++) {
            String path = "categories." + catKey + ".items." + itemKeys.get(i) + ".";
            String matName = sklepConfig.getString(path + "material", "STONE");
            int amount = sklepConfig.getInt(path + "amount", 1);
            // Lot sprzedaży bywa inny niż lot kupna — patrz sell-amount w sklep.yml.
            int sellAmount = sklepConfig.getInt(path + "sell-amount", amount);
            int buyPrice = sklepConfig.getInt(path + "buy-price", -1);
            int sellPrice = sklepConfig.getInt(path + "sell-price", -1);
            String customDisplayName = sklepConfig.getString(path + "display-name", null);
            List<String> customLore = sklepConfig.getStringList(path + "lore");

            Material material = Material.matchMaterial(matName);
            if (material == null) material = Material.STONE;

            ItemStack item = new ItemStack(material, Math.min(Math.max(amount, 1), 64));
            ItemMeta meta = item.getItemMeta();
            meta.displayName(customDisplayName != null
                    ? Component.text(customDisplayName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                    : Component.text(material.name(), NamedTextColor.YELLOW, TextDecoration.BOLD));

            List<Component> lore = new ArrayList<>();
            for (String linia : customLore) {
                lore.add(Component.text(linia, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
            }
            if (!customLore.isEmpty()) lore.add(Component.empty());

            if (buyPrice >= 0) {
                lore.add(Component.text("Kupno: " + buyPrice + " $ za " + amount + " szt.",
                        NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            }
            if (sellPrice >= 0) {
                lore.add(Component.text("Skup: " + sellPrice + " $ za " + sellAmount + " szt.",
                        NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.empty());

            if (buyPrice >= 0) {
                lore.add(Component.text("LPM — kup " + amount + " szt.",
                        NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("Tego nie da się kupić",
                        NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
            if (sellPrice >= 0) {
                lore.add(Component.text("PPM — sprzedaj cały stack (po " + sellAmount + " szt.)",
                        NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Shift+PPM — sprzedaj cały ekwipunek",
                        NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("Tego nie da się sprzedać",
                        NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }

            meta.lore(lore);
            item.setItemMeta(meta);

            gui.setItem(i - pageStart, item);
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

    /** Buduje kupiony item; jeśli wpis ma custom-id, doczepia PDC tag + display-name + lore (patrz kategorie "spawnery"/"specjalne"). */
    private ItemStack stworzKupionyItem(Material material, int amount, String configPath) {
        ItemStack item = new ItemStack(material, amount);

        String customId = sklepConfig.getString(configPath + "custom-id", null);
        String displayName = sklepConfig.getString(configPath + "display-name", null);
        List<String> lore = sklepConfig.getStringList(configPath + "lore");
        if (customId == null && displayName == null && lore.isEmpty()) return item;

        ItemMeta meta = item.getItemMeta();
        if (displayName != null) {
            meta.displayName(Component.text(displayName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        }
        if (!lore.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (String linia : lore) {
                loreComponents.add(Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(loreComponents);
        }
        if (customId != null) {
            meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, customId);
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Ile sztuk idzie w jednej transakcji skupu i ile sklep za taki lot płaci. */
    private record OfertaSkupu(int lot, int cenaZaLot) {}

    /** Skąd sprzedajLoty() ma brać sprzedawane sztuki. */
    private enum TrybSkupu { REKA, JEDEN_STOS, CALY_EKWIPUNEK }

    /**
     * Szuka w sklep.yml pierwszego wpisu o danym materiale, który ma ustawione
     * sell-price - i którego custom-id DOKŁADNIE pasuje do podanego (null = wpis
     * bez custom-id, czyli zwykły wanilijski item). Zwraca null, jeśli itemu nie
     * da się sprzedać.
     *
     * Custom-id musi się zgadzać dokładnie, bo kilka różnych itemów potrafi dzielić
     * ten sam Material (5 spawnerów to jeden SPAWNER, 10 gatunków ryb z mainplugins-fishing
     * to góra 4 wanilijskie materiały ryb) - dopasowanie po samym materiale byłoby
     * niejednoznaczne. Stąd też wpisy z custom-id muszą mieć custom-id ustawione
     * jawnie w sklep.yml, żeby w ogóle wziąć udział w skupie.
     */
    private OfertaSkupu znajdzOferteSkupu(Material material, String customId) {
        ConfigurationSection catSection = sklepConfig.getConfigurationSection("categories");
        if (catSection == null) return null;

        for (String catKey : catSection.getKeys(false)) {
            ConfigurationSection itemsSection =
                    sklepConfig.getConfigurationSection("categories." + catKey + ".items");
            if (itemsSection == null) continue;

            for (String itemKey : itemsSection.getKeys(false)) {
                String path = "categories." + catKey + ".items." + itemKey + ".";
                String matName = sklepConfig.getString(path + "material");
                if (matName == null || Material.matchMaterial(matName) != material) continue;
                if (!Objects.equals(sklepConfig.getString(path + "custom-id", null), customId)) continue;

                int cena = sklepConfig.getInt(path + "sell-price", -1);
                if (cena < 0) continue;

                // sell-amount to lot skupu; gdy go nie ma, spada na amount (lot kupna)
                int lot = sklepConfig.getInt(path + "sell-amount",
                          sklepConfig.getInt(path + "amount", 1));
                if (lot > 0) return new OfertaSkupu(lot, cena);
            }
        }
        return null;
    }

    /** Wartość custom-id z PDC itemu, albo null, jeśli to zwykły wanilijski item. */
    private String pobierzCustomId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return null;
        return stack.getItemMeta().getPersistentDataContainer()
                .get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
    }

    /** Czy dany stos w ekwipunku dokładnie pasuje do oferty (materiał + ten sam custom-id, null-safe). */
    private boolean pasujeDoOferty(ItemStack is, Material material, String customId) {
        return is != null && is.getType() == material && Objects.equals(pobierzCustomId(is), customId);
    }

    public void handleSellCommand(Player player) {
        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (wRece.getType().isAir()) {
            player.sendMessage(Component.text("Nie trzymasz żadnego przedmiotu w ręce!", NamedTextColor.RED));
            return;
        }
        sprzedajLoty(player, wRece.getType(), pobierzCustomId(wRece), TrybSkupu.REKA);
    }

    public void handleSellAllCommand(Player player) {
        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (wRece.getType().isAir()) {
            player.sendMessage(Component.text("Przytrzymaj w ręce przedmiot, który chcesz masowo sprzedać!", NamedTextColor.RED));
            return;
        }
        sprzedajLoty(player, wRece.getType(), pobierzCustomId(wRece), TrybSkupu.CALY_EKWIPUNEK);
    }

    /**
     * Skupuje przedmiot wyłącznie pełnymi lotami. Reszta poniżej jednego lotu
     * zostaje graczowi w ekwipunku — nic nie przepada, ale też nic się nie zaokrągla.
     *
     * @param tryb REKA = tylko stack z ręki (/sell), JEDEN_STOS = pierwszy napotkany
     *             stos w ekwipunku (PPM w GUI), CALY_EKWIPUNEK = wszystkie sztuki
     *             w całym ekwipunku (/sellall, Shift+PPM w GUI)
     */
    private void sprzedajLoty(Player player, Material material, String customId, TrybSkupu tryb) {
        OfertaSkupu oferta = znajdzOferteSkupu(material, customId);
        if (oferta == null) {
            player.sendMessage(Component.text("Tego przedmiotu nie można sprzedać w sklepie!", NamedTextColor.RED));
            return;
        }

        PlayerInventory inv = player.getInventory();
        int posiadane = switch (tryb) {
            case CALY_EKWIPUNEK -> policzWEkwipunku(inv, material, customId);
            case JEDEN_STOS -> pierwszyStosIlosc(inv, material, customId);
            case REKA -> pasujeDoOferty(inv.getItemInMainHand(), material, customId) ? inv.getItemInMainHand().getAmount() : 0;
        };

        int loty = posiadane / oferta.lot();
        if (loty == 0) {
            player.sendMessage(Component.text(
                    "Sklep skupuje ten przedmiot po " + oferta.lot() + " szt. — masz " + posiadane + ".",
                    NamedTextColor.RED));
            return;
        }

        int doZabrania = loty * oferta.lot();
        long zarobek = (long) loty * oferta.cenaZaLot();

        if (tryb == TrybSkupu.REKA) {
            ItemStack wRece = inv.getItemInMainHand();
            wRece.setAmount(wRece.getAmount() - doZabrania);
            inv.setItemInMainHand(wRece.getAmount() > 0 ? wRece : null);
        } else {
            // Dla JEDEN_STOS "posiadane" to ilość w pierwszym napotkanym stosie, więc
            // doZabrania <= ten jeden stos - usunZEkwipunku i tak nie tknie kolejnych.
            usunZEkwipunku(inv, material, customId, doZabrania);
        }

        economyManager.dodajKase(player.getUniqueId(), zarobek);

        int reszta = posiadane - doZabrania;
        Component msg = Component.text("Sprzedano " + doZabrania + "x " + material.name()
                + " za " + zarobek + " $!", NamedTextColor.AQUA);
        if (reszta > 0) {
            msg = msg.append(Component.text(" (zostało " + reszta + " szt. — za mało na kolejny lot)",
                    NamedTextColor.GRAY));
        }
        player.sendMessage(msg);
    }

    private int policzWEkwipunku(PlayerInventory inv, Material material, String customId) {
        int suma = 0;
        for (ItemStack is : inv.getStorageContents()) {
            if (pasujeDoOferty(is, material, customId)) suma += is.getAmount();
        }
        return suma;
    }

    /** Ilość w PIERWSZYM napotkanym pasującym stosie - do sprzedaży "jednym klikiem" PPM w GUI. */
    private int pierwszyStosIlosc(PlayerInventory inv, Material material, String customId) {
        for (ItemStack is : inv.getStorageContents()) {
            if (pasujeDoOferty(is, material, customId)) return is.getAmount();
        }
        return 0;
    }

    /**
     * Zabiera dokładnie tyle sztuk, ile trzeba — nie używamy inv.remove(Material),
     * bo ono czyści cały ekwipunek z danego materiału, także resztę poniżej lotu
     * i itemy o innym custom-id (albo bez niego), które akurat dzielą ten sam materiał.
     */
    private void usunZEkwipunku(PlayerInventory inv, Material material, String customId, int ile) {
        ItemStack[] zawartosc = inv.getStorageContents();
        for (int i = 0; i < zawartosc.length && ile > 0; i++) {
            ItemStack is = zawartosc[i];
            if (!pasujeDoOferty(is, material, customId)) continue;

            int zabierz = Math.min(is.getAmount(), ile);
            is.setAmount(is.getAmount() - zabierz);
            ile -= zabierz;
            if (is.getAmount() <= 0) zawartosc[i] = null;
        }
        inv.setStorageContents(zawartosc);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().title().toString();
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null) return;

        // Sloty dolnego inwentarza (ekwipunek gracza) są numerowane od zera tak samo
        // jak sloty górnego GUI sklepu - bez tego sprawdzenia kliknięcie np. w slot 1
        // swojego ekwipunku było traktowane tak samo jak kliknięcie w slot 1 sklepu,
        // więc "kupowało"/otwierało kategorię wg tego, co akurat tam stało w konfiguracji.
        if (!event.getView().getTopInventory().equals(event.getClickedInventory())) return;

        // Kliknięcie w DOLNY ekwipunek gracza, a nie w samo GUI sklepu - event.getSlot()
        // poniżej liczy slot od 0 we WŁASNYM inwentarzu klikniętej strony (nie w widoku GUI),
        // więc bez tego sprawdzenia numer slotu z ekwipunku gracza mógł się pokryć z numerem
        // slotu jakiegoś przedmiotu w sklepie i wywołać kupno/sprzedaż zupełnie innego itemu
        // niż ten, w który gracz realnie kliknął.
        boolean klikniecieWGui = event.getClickedInventory() != null
                && event.getClickedInventory().equals(event.getView().getTopInventory());

        // KLIKANIE W GŁÓWNYM SKLEPIE
        if (title.contains("Sklep Serwerowy")) {
            event.setCancelled(true);
            if (!klikniecieWGui) return;

            // Przycisk wyjścia zawsze na slocie 49 - sprawdzamy PO SLOCIE, nie po materiale,
            // żeby nie kolidować z towarem sprzedawanym w sklepie o tym samym materiale
            // (np. Gwiazda Netheru w kategorii "Klejnoty i Rzadkości").
            if (event.getSlot() == 49) {
                boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
                player.closeInventory();
                if (zMenu) player.performCommand("menu");
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
            if (!klikniecieWGui) return;

            String catKey = playerCategory.get(player.getUniqueId());
            int currentPage = playerPage.getOrDefault(player.getUniqueId(), 0);
            if (catKey == null) return;

            // Logika dolnych przycisków - PO SLOCIE, nie po materiale (patrz komentarz
            // przy analogicznym fragmencie dla głównego ekranu sklepu wyżej).
            int slotKlikniecia = event.getSlot();
            if (slotKlikniecia == 48) {
                boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
                otworzSklep(player, zMenu); // Wracamy do głównego widoku sklepu
                return;
            } else if (slotKlikniecia == 50) {
                boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
                player.closeInventory();
                if (zMenu) player.performCommand("menu");
                return;
            } else if (slotKlikniecia == 45) {
                otworzKategorieStrona(player, catKey, currentPage - 1);
                return;
            } else if (slotKlikniecia == 53) {
                otworzKategorieStrona(player, catKey, currentPage + 1);
                return;
            }

            if (slotKlikniecia >= GRID_ROZMIAR) return; // Zabezpieczenie przed błędem z dolnym paskiem

            ConfigurationSection itemsSection = sklepConfig.getConfigurationSection("categories." + catKey + ".items");
            if (itemsSection == null) return;

            // Ta sama arytmetyka co przy renderowaniu w otworzKategorieStrona() - kolejność
            // w sklep.yml + numer strony + slot w siatce muszą się zgadzać 1:1, inaczej
            // klik trafi w zupełnie inny item niż ten widoczny na ekranie.
            List<String> itemKeys = new ArrayList<>(itemsSection.getKeys(false));
            int absoluteIndex = currentPage * GRID_ROZMIAR + slotKlikniecia;
            if (absoluteIndex < 0 || absoluteIndex >= itemKeys.size()) return;

            String path = "categories." + catKey + ".items." + itemKeys.get(absoluteIndex) + ".";
            String matName = sklepConfig.getString(path + "material");
            if (matName == null) return;
            Material cfgMaterial = Material.matchMaterial(matName);
            if (cfgMaterial == null) return;

            int amount = sklepConfig.getInt(path + "amount", 1);
            int buyPrice = sklepConfig.getInt(path + "buy-price", -1);

            if (event.isLeftClick()) {
                if (buyPrice < 0) {
                    player.sendMessage(Component.text("Tego przedmiotu nie można kupić!", NamedTextColor.RED));
                    return;
                }
                if (economyManager.maWystarczajaco(player.getUniqueId(), buyPrice)) {
                    economyManager.odejmijKase(player.getUniqueId(), buyPrice);
                    player.getInventory().addItem(stworzKupionyItem(cfgMaterial, amount, path));
                    player.sendMessage(Component.text("Kupiono " + amount + "x za " + buyPrice + " $!", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Brak pieniędzy!", NamedTextColor.RED));
                }
            } else if (event.isRightClick()) {
                if (sklepConfig.getInt(path + "sell-price", -1) < 0) {
                    player.sendMessage(Component.text("Tego przedmiotu nie można sprzedać!", NamedTextColor.RED));
                    return;
                }
                // Jedna ścieżka sprzedaży dla GUI i komend — inaczej reguła lotów
                // rozjedzie się między /sell a klikaniem w sklepie. PPM = jeden stos,
                // Shift+PPM = cały ekwipunek (jak /sellall). custom-id (jeśli wpis go ma,
                // np. gatunek ryby) musi iść razem z materiałem - patrz znajdzOferteSkupu.
                String customId = sklepConfig.getString(path + "custom-id", null);
                sprzedajLoty(player, cfgMaterial, customId, event.isShiftClick() ? TrybSkupu.CALY_EKWIPUNEK : TrybSkupu.JEDEN_STOS);
            }
        }
    }
}