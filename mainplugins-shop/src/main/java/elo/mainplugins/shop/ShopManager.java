package elo.mainplugins.shop;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.CustomItemKeys;
import elo.mainplugins.shop.gui.ScreenLayout;
import elo.mainplugins.shop.gui.ShopGuiContent;
import elo.mainplugins.shop.gui.ShopGuiStyle;
import elo.mainplugins.shop.gui.ShopSlotEntry;
import elo.mainplugins.shop.gui.ShopSlotRole;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.MusicInstrument;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Cała treść sklepu (kategorie, ceny, ikonki, ilości) żyje w plikach categories/*.yml
 * (jeden plik na kategorię, nazwa pliku = klucz kategorii) - sklep.yml trzyma już tylko
 * globalne ustawienia. Ta klasa tylko RENDERUJE scaloną konfigurację (patrz
 * wczytajSklepZFolderow()) i obsługuje kliknięcia; scalanie jest odświeżane na żywo
 * przez /@reloadsklep. Pliki kategorii są generowane zewnętrznie (cennik) i dostarczane
 * jako domyślne zasoby w resources/categories/ - przy pierwszym uruchomieniu (brak
 * folderu w data folderze) są kopiowane 1:1 przez saveResource(); jeśli folder już
 * istnieje (admin coś zmienił / serwer już działał), nic w nim nie ruszamy.
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
    private final DynamicPriceManager ceny;

    private final Map<UUID, String> playerCategory = new HashMap<>();
    private final Map<UUID, Integer> playerPage = new HashMap<>();
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    /** false = po cenie kupna rosnąco (domyślne), true = po cenie skupu malejąco. */
    private final Map<UUID, Boolean> sortowaniePoSkupie = new HashMap<>();

    /** Gracze, którzy kliknęli lupę i mają wpisać frazę na czacie. */
    private final Set<UUID> czekaNaFraze = new HashSet<>();
    /** Ostatnie wyniki wyszukiwania per gracz — "katKey:itemKey" w kolejności GUI. */
    private final Map<UUID, List<String>> wynikiSzukania = new HashMap<>();

    private static final String TYTUL_WYNIKOW = "Wyniki: ";

    /**
     * Znacznik widoku kategorii — tytuł tego okna to teraz SAMA nazwa kategorii
     * (bez prefiksu "Sklep: "), więc dopasowanie po tekście tytułu w onInventoryClick()
     * kolidowałoby z dowolnym innym ekwipunkiem nazwanym tak samo jak któraś z
     * kategorii (np. skrzynia czy kowadło). Próba obejścia tego niewidocznym
     * znakiem Unicode w tytule zawiodła — Minecraft renderuje go jako widoczne
     * kropki. Zamiast kolejnej sztuczki z tekstem: identyfikacja przez
     * InventoryHolder, który w ogóle nie jest częścią tego, co widzi gracz.
     */
    private static final class KategoriaHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null; // sam marker do instanceof w onInventoryClick - nikt tego nie wywoluje
        }
    }

    /** Do jakiej pozycji sklepu (klucz "kategoria:itemKey") odnosi się otwarty ekran wyboru ilości. */
    private final Map<UUID, String> otwartyWyborIlosci = new HashMap<>();

    private static final String TYTUL_WYBOR_ILOSCI = "Ile sztuk?";

    /**
     * Układ/rozmiar GUI (menu główne, strona kategorii, wybór ilości, wyniki wyszukiwania) -
     * wczytany z sklep-gui.yml (patrz ShopGuiLoader), zastępuje dawne hardkodowane tablice
     * SLOTY_SIATKI/SLOTY_MENU_KATEGORII/SLOTY_WYBORU/ILOSCI_DO_WYBORU i pojedyncze sloty-guziki
     * rozrzucone po tej klasie. Podmieniany w całości przy /@reloadsklep (patrz przeladujKonfiguracje).
     */
    private ShopGuiContent guiContent;

    public ShopManager(Plugin plugin, EconomyService economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        // guiContent MUSI być wczytany przed stworzLubWczytajPlikSklepu() - wczytajSklepZFolderow()
        // (wołane stamtąd) potrzebuje guiContent.categoryOrder() do posortowania kategorii.
        this.guiContent = ShopGuiLoader.load(plugin);
        stworzLubWczytajPlikSklepu();
        this.ceny = new DynamicPriceManager(plugin, this);
    }

    /** Pierwszy slot o danej roli w danym ekranie, albo null gdy nie skonfigurowano (przycisk/pole po prostu się nie renderuje). */
    private Integer pierwszySlot(ScreenLayout ekran, ShopSlotRole rola) {
        for (ShopSlotEntry e : ekran.layout()) if (e.role() == rola) return e.slot();
        return null;
    }

    /** Domyślny szary panel na każdym slocie, potem nadpisany jawnymi wpisami FILLER (z własnym materiałem) z layoutu. */
    private void wypelnijTloSzare(Inventory gui, ScreenLayout ekran) {
        ItemStack szare = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, szare);
        for (ShopSlotEntry e : ekran.layout()) {
            if (e.role() == ShopSlotRole.FILLER && e.material() != null) {
                gui.setItem(e.slot(), pane(e.material()));
            }
        }
    }

    /** Żeby plugin mógł zamknąć manager cen dynamicznych przy wyłączaniu (patrz MainpluginsShop.onDisable()). */
    public DynamicPriceManager getCeny() { return ceny; }

    /** Sklejona konfiguracja sklepu (sklep.yml + wszystkie categories/*.yml) — do odczytu przez /@sklep. */
    public FileConfiguration getSklepConfig() { return sklepConfig; }

    /**
     * Nazwa wyświetlana danego itemu (z "display-name" w categories/*.yml), albo
     * sam klucz, gdy item jej nie ma / nie istnieje. Do użycia przez
     * DynamicPriceManager (patrz CenyService.najwiekszeOdchylenie/getZablokowaneNazwy),
     * które nie ma bezpośredniego dostępu do sklepConfig.
     */
    public String nazwaWyswietlana(String klucz) {
        LokalizacjaItemu lok = znajdzItem(klucz);
        if (lok == null) return klucz;
        return sklepConfig.getString(lok.path() + "display-name", klucz);
    }

    /**
     * Klucz, pod którym DynamicPriceManager trzyma mnożnik ceny danego itemu.
     * Custom-id ma pierwszeństwo, bo kilka pozycji dzieli ten sam Material
     * (9 spawnerów = jeden SPAWNER, 10 ryb = COD/SALMON/...).
     */
    private String kluczCeny(String material, String customId) {
        return customId != null ? customId : material;
    }

    /** Gdzie w configu siedzi dany item. */
    public record LokalizacjaItemu(String kategoria, String itemKey, String path) {}

    /**
     * Szuka itemu po nazwie materiału ALBO po custom-id.
     *
     * Custom-id sprawdzamy pierwsze, bo jest jednoznaczne — kilka pozycji dzieli
     * ten sam Material (9 spawnerów = SPAWNER, 10 ryb = COD/SALMON/TROPICAL_FISH),
     * więc szukanie po materiale trafiłoby w przypadkową z nich.
     */
    public LokalizacjaItemu znajdzItem(String szukane) {
        ConfigurationSection cats = sklepConfig.getConfigurationSection("categories");
        if (cats == null) return null;

        LokalizacjaItemu poMateriale = null;

        for (String catKey : cats.getKeys(false)) {
            ConfigurationSection items =
                    sklepConfig.getConfigurationSection("categories." + catKey + ".items");
            if (items == null) continue;

            for (String itemKey : items.getKeys(false)) {
                String path = "categories." + catKey + ".items." + itemKey + ".";
                String customId = sklepConfig.getString(path + "custom-id", null);
                String material = sklepConfig.getString(path + "material", "");

                if (szukane.equalsIgnoreCase(customId)) {
                    return new LokalizacjaItemu(catKey, itemKey, path);   // dokładne trafienie
                }
                // Zapamiętujemy, ale szukamy dalej — może gdzieś jest custom-id
                if (poMateriale == null && szukane.equalsIgnoreCase(material) && customId == null) {
                    poMateriale = new LokalizacjaItemu(catKey, itemKey, path);
                }
            }
        }
        return poMateriale;
    }

    /** Wszystkie identyfikatory do tab-completion. */
    public List<String> wszystkieIdentyfikatory() {
        List<String> wynik = new ArrayList<>();
        ConfigurationSection cats = sklepConfig.getConfigurationSection("categories");
        if (cats == null) return wynik;

        for (String catKey : cats.getKeys(false)) {
            ConfigurationSection items =
                    sklepConfig.getConfigurationSection("categories." + catKey + ".items");
            if (items == null) continue;
            for (String itemKey : items.getKeys(false)) {
                String path = "categories." + catKey + ".items." + itemKey + ".";
                String customId = sklepConfig.getString(path + "custom-id", null);
                wynik.add(customId != null ? customId : sklepConfig.getString(path + "material", ""));
            }
        }
        return wynik;
    }

    /**
     * Zapisuje nową cenę do właściwego pliku w categories/ i przeładowuje sklep.
     *
     * KLUCZOWE: sklepConfig w pamięci to SKLEJKA wszystkich plików z categories/.
     * Zapisanie go z powrotem stworzyłoby jeden wielki plik i zniszczyło podział
     * na kategorie. Dlatego zapis musi trafić do konkretnego pliku źródłowego.
     *
     * @param pole "buy-price" albo "sell-price"
     * @return komunikat błędu, albo null gdy się udało
     */
    public String zmienCeneWPliku(LokalizacjaItemu lok, String pole, int nowaWartosc) {
        File plikKategorii = new File(plugin.getDataFolder(), "categories/" + lok.kategoria() + ".yml");
        if (!plikKategorii.exists()) {
            return "Nie znaleziono pliku kategorii: " + lok.kategoria() + ".yml";
        }

        YamlConfiguration kat = YamlConfiguration.loadConfiguration(plikKategorii);
        // W pliku kategorii nie ma przedrostka "categories.<nazwa>." — nazwa pliku
        // JEST nazwą kategorii, więc ścieżka jest krótsza niż w sklejonym configu.
        String pathWPliku = "items." + lok.itemKey() + "." + pole;

        if (!kat.contains("items." + lok.itemKey())) {
            return "Item nie istnieje w pliku " + lok.kategoria() + ".yml";
        }

        kat.set(pathWPliku, nowaWartosc);
        try {
            kat.save(plikKategorii);
        } catch (IOException e) {
            return "Blad zapisu: " + e.getMessage();
        }

        przeladujKonfiguracje();   // ta metoda już istnieje - używa jej /@reloadsklep
        return null;
    }

    /**
     * Wczytuje sklep.yml z dysku od nowa, bez restartu serwera - pod komendę /@reloadsklep.
     * Nie rusza otwartych aktualnie GUI graczy (te po prostu pokazują poprzedni stan
     * do czasu, aż gracz je zamknie i otworzy ponownie).
     */
    public void przeladujKonfiguracje() {
        guiContent = ShopGuiLoader.load(plugin);
        sklepConfig = wczytajSklepZFolderow();
        ostrzezZaNieCalkowiteCeny();
    }

    /**
     * Wczytuje sklep.yml (ustawienia globalne, jeśli jakieś zostały) i dokleja
     * do niego zawartość każdego pliku z categories/ pod "categories.<nazwa>".
     * Brakujący folder categories/ nie jest błędem — po prostu nie ma kategorii.
     */
    private YamlConfiguration wczytajSklepZFolderow() {
        File plikGlowny = new File(plugin.getDataFolder(), "sklep.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(plikGlowny);

        File folderKategorii = new File(plugin.getDataFolder(), "categories");
        File[] pliki = folderKategorii.listFiles((dir, name) -> name.endsWith(".yml"));
        if (pliki == null) {
            plugin.getLogger().warning("Brak folderu categories/ — sklep będzie pusty.");
            return cfg;
        }

        // File.listFiles() NIE gwarantuje żadnej konkretnej kolejności (w praktyce leci
        // porządek systemu plików, czyli zwykle alfabetyczny, a nie ten sprzed rozbicia
        // na osobne pliki) - kolejność w menu głównym sklepu zależy 1:1 od kolejności
        // wstawiania do configu, więc bez tego sortu kategorie tasowałyby się losowo
        // między restartami/OS-ami. Sortujemy wg sklep-gui.yml (category-order); pliki
        // spoza tej listy (np. świeżo dodana kategoria, o której ktoś zapomniał tam
        // dopisać) lądują na końcu w kolejności z dysku, zamiast znikać.
        List<String> kolejnoscKategorii = guiContent.categoryOrder();
        Arrays.sort(pliki, Comparator.comparingInt(plik -> {
            String klucz = plik.getName().substring(0, plik.getName().length() - 4);
            int idx = kolejnoscKategorii.indexOf(klucz);
            return idx < 0 ? Integer.MAX_VALUE : idx;
        }));

        for (File plik : pliki) {
            String klucz = plik.getName().substring(0, plik.getName().length() - 4); // bez ".yml"
            YamlConfiguration kat = YamlConfiguration.loadConfiguration(plik);

            // getValues(true) daje płaską mapę ze wszystkimi zagnieżdżeniami —
            // createSection ją odtwarza jako pełną strukturę sekcji.
            cfg.createSection("categories." + klucz, kat.getValues(true));
        }

        plugin.getLogger().info("Wczytano " + pliki.length + " kategorii z categories/.");
        return cfg;
    }

    private void stworzLubWczytajPlikSklepu() {
        sklepFile = new File(plugin.getDataFolder(), "sklep.yml");
        if (!sklepFile.exists()) {
            // Domyślny cennik żyje jako zasób pluginu (resources/sklep.yml) - kopiujemy
            // go 1:1 tylko przy pierwszym uruchomieniu. Jeśli plik już istnieje, nic tu
            // nie nadpisujemy - ewentualne ręczne zmiany admina zostają nietknięte.
            plugin.saveResource("sklep.yml", false);
        }

        File folderKategorii = new File(plugin.getDataFolder(), "categories");
        if (!folderKategorii.exists()) {
            folderKategorii.mkdirs();
            // saveResource() kopiuje jeden plik na raz i nie umie wylistować całego
            // folderu z jara - stąd jawna lista (teraz sklep-gui.yml/category-order,
            // dawniej KOLEJNOSC_KATEGORII), tak samo jak przy innych domyślnych configach
            // w tym projekcie (patrz BrukSurowceManager/ToolSkillManager). Nową kategorię
            // trzeba dopisać w sklep-gui.yml I dodać jej plik do resources/categories/.
            for (String nazwaKategorii : guiContent.categoryOrder()) {
                plugin.saveResource("categories/" + nazwaKategorii + ".yml", false);
            }
        }

        sklepConfig = wczytajSklepZFolderow();
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

    /** Który lokalny indeks (w podanej liście slotów ITEM_SLOT) odpowiada danemu slotowi GUI, albo -1 (np. tło/nawigacja). */
    private int lokalnyIndexDlaSlotu(int guiSlot, List<ShopSlotEntry> itemSlots) {
        for (int idx = 0; idx < itemSlots.size(); idx++) {
            if (itemSlots.get(idx).slot() == guiSlot) return idx;
        }
        return -1;
    }

    /**
     * Fizyczne sloty GUI (w kolejności wypełniania) na stronę kategorii.
     *
     * Gdy cała kategoria mieści się na jednej stronie I ma mniej itemów niż jeden
     * rząd siatki (typowe dla "Kolekcji" - zawsze dokładnie 5 rotacyjnych pozycji),
     * centrujemy je w jednym, środkowym rzędzie zamiast upychać od lewej-górnej
     * krawędzi. W każdym innym wypadku (kategoria wielostronicowa) zwraca zwykłą,
     * pełną listę slotów bez zmian.
     *
     * WAŻNE: render (otworzKategorieStrona) i klik (onInventoryClick) MUSZĄ wołać
     * to identycznie - inaczej klik trafi w zupełnie inny item niż ten widoczny.
     */
    private List<Integer> slotyStrony(List<ShopSlotEntry> itemSlots, int liczbaItemowLacznie, boolean jednaStrona) {
        List<Integer> pelne = itemSlots.stream().map(ShopSlotEntry::slot).toList();
        if (!jednaStrona || liczbaItemowLacznie >= pelne.size()) return pelne;

        // Grupujemy sloty po rzędzie (rząd = numer_slotu / 9), zachowując kolejność z configu.
        LinkedHashMap<Integer, List<Integer>> rzedy = new LinkedHashMap<>();
        for (int slot : pelne) rzedy.computeIfAbsent(slot / 9, k -> new ArrayList<>()).add(slot);
        List<List<Integer>> listaRzedow = new ArrayList<>(rzedy.values());

        int najszerszyRzad = listaRzedow.stream().mapToInt(List::size).max().orElse(pelne.size());
        if (liczbaItemowLacznie > najszerszyRzad) return pelne; // nie mieści się w jednym rzędzie - nie kombinujemy

        List<Integer> wybranyRzad = listaRzedow.get((listaRzedow.size() - 1) / 2);
        int wciecie = Math.max(0, (wybranyRzad.size() - liczbaItemowLacznie) / 2);
        return new ArrayList<>(wybranyRzad.subList(wciecie, wciecie + liczbaItemowLacznie));
    }

    /** Puste, bezimienne szkło do wypełniania tła GUI. */
    private ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    // ==================================================================== GUI ====

    public void otworzSklep(Player player, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        playerCategory.remove(player.getUniqueId());
        playerPage.remove(player.getUniqueId());

        ScreenLayout ekran = guiContent.mainMenu();
        Inventory gui = Bukkit.createInventory(null, ekran.size(), Component.text("Sklep Serwerowy", guiContent.styl().tytulMainMenu(), TextDecoration.BOLD));
        wypelnijTloSzare(gui, ekran);

        Integer slotLupy = pierwszySlot(ekran, ShopSlotRole.SEARCH);
        if (slotLupy != null) {
            ItemStack lupa = new ItemStack(Material.OAK_SIGN);
            ItemMeta metaLupa = lupa.getItemMeta();
            metaLupa.displayName(Component.text(guiContent.styl().szukaj().tekst(), guiContent.styl().szukaj().kolor(), TextDecoration.BOLD));
            List<Component> loreLupa = new ArrayList<>();
            loreLupa.add(Component.text("Kliknij i wpisz nazwę na czacie",
                    NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            loreLupa.add(Component.text("np. bruk, diament, kaktus",
                    NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            metaLupa.lore(loreLupa);
            lupa.setItemMeta(metaLupa);
            gui.setItem(slotLupy, lupa);
        }

        // Kolejność ikon w menu = kolejność category-order w sklep-gui.yml, przypisana
        // 1:1 do kolejnych CATEGORY_SLOT (patrz ShopGuiContent) - bez dawnego auto-
        // centrowania niepełnego ostatniego rzędu, układ jest teraz w pełni jawny.
        ConfigurationSection catSection = sklepConfig.getConfigurationSection("categories");
        List<ShopSlotEntry> categorySlots = ekran.slotsWithRole(ShopSlotRole.CATEGORY_SLOT);
        List<String> kolejnosc = guiContent.categoryOrder();
        if (catSection != null) {
            for (int i = 0; i < categorySlots.size() && i < kolejnosc.size(); i++) {
                String catKey = kolejnosc.get(i);
                if (!catSection.contains(catKey)) continue; // w kolejności, ale bez wczytanego pliku - pomijamy

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

                gui.setItem(categorySlots.get(i).slot(), item);
            }
        }

        Integer slotWyjscia = pierwszySlot(ekran, ShopSlotRole.EXIT);
        if (slotWyjscia != null) {
            ItemStack wyjscie = new ItemStack(zMenu ? Material.NETHER_STAR : Material.BARRIER);
            ItemMeta mWyjscie = wyjscie.getItemMeta();
            ShopGuiStyle.StyledLabel etykietaWyjscia = zMenu ? guiContent.styl().wyjscieDoMenu() : guiContent.styl().wyjscieZamknij();
            mWyjscie.displayName(Component.text(etykietaWyjscia.tekst(), etykietaWyjscia.kolor(), TextDecoration.BOLD));
            wyjscie.setItemMeta(mWyjscie);
            gui.setItem(slotWyjscia, wyjscie);
        }

        player.openInventory(gui);
    }

    public void otworzKategorieStrona(Player player, String catKey, int page) {
        playerCategory.put(player.getUniqueId(), catKey);
        playerPage.put(player.getUniqueId(), page);

        ScreenLayout ekran = guiContent.categoryPage();
        List<ShopSlotEntry> itemSlots = ekran.slotsWithRole(ShopSlotRole.ITEM_SLOT);

        String catName = sklepConfig.getString("categories." + catKey + ".name", "Kategoria");
        ConfigurationSection itemsSection = sklepConfig.getConfigurationSection("categories." + catKey + ".items");
        // Kolejność w sklep.yml (klucze "0","1","2"...) = kolejność wypełniania siatki -
        // ta lista jest jedynym źródłem prawdy o tym, co ląduje w którym slocie GUI,
        // więc render (niżej) i rozpoznawanie kliknięcia (onInventoryClick) muszą liczyć
        // po niej identycznie.
        List<String> itemKeys = itemsSection != null ? new ArrayList<>(itemsSection.getKeys(false)) : new ArrayList<>();

        boolean poSkupie = sortowaniePoSkupie.getOrDefault(player.getUniqueId(), false);
        itemKeys = posortujItemy(catKey, itemKeys, poSkupie);

        int rozmiarStrony = Math.max(itemSlots.size(), 1);
        int totalPages = Math.max(1, (int) Math.ceil((double) itemKeys.size() / rozmiarStrony));
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        Component guiTitle = Component.text(catName, guiContent.styl().tytulKategoria(), TextDecoration.BOLD);

        Inventory gui = Bukkit.createInventory(new KategoriaHolder(), ekran.size(), guiTitle);
        wypelnijTloSzare(gui, ekran);

        int pageStart = page * rozmiarStrony;
        int pageEnd = Math.min(pageStart + rozmiarStrony, itemKeys.size());
        List<Integer> sloty = slotyStrony(itemSlots, itemKeys.size(), totalPages == 1);

        for (int i = pageStart; i < pageEnd; i++) {
            String path = "categories." + catKey + ".items." + itemKeys.get(i) + ".";
            String matName = sklepConfig.getString(path + "material", "STONE");
            int amount = sklepConfig.getInt(path + "amount", 1);
            // Lot sprzedaży bywa inny niż lot kupna — patrz sell-amount w sklep.yml.
            int sellAmount = sklepConfig.getInt(path + "sell-amount", amount);
            int buyPrice = sklepConfig.getInt(path + "buy-price", -1);
            int sellPrice = sklepConfig.getInt(path + "sell-price", -1);
            String customId = sklepConfig.getString(path + "custom-id", null);
            String customDisplayName = sklepConfig.getString(path + "display-name", null);
            List<String> customLore = sklepConfig.getStringList(path + "lore");

            Material material = Material.matchMaterial(matName);
            if (material == null) material = Material.STONE;

            // Ikona w siatce to zawsze pojedynczy blok - stack "amount" mylił graczy sugerując
            // natychmiastowe kupno całego lota. Realną ilość (lot / wybór 1-8-16-32-64) widać
            // dopiero w lore i po kliknięciu.
            ItemStack item = new ItemStack(material, 1);
            zastosujInstrument(item, path);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(customDisplayName != null
                    ? Component.text(customDisplayName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                    : Component.text(material.name(), NamedTextColor.YELLOW, TextDecoration.BOLD));

            List<Component> lore = new ArrayList<>();
            for (String linia : customLore) {
                lore.add(linia.equals(LORE_OFERTA_CZASOWA)
                        ? teczowyTekst(linia, true)
                        : Component.text(linia, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
            }
            if (!customLore.isEmpty()) lore.add(Component.empty());

            if (buyPrice >= 0) {
                // Cena za POJEDYNCZĄ sztukę (ten sam wzór co w otworzWyborIlosci/policzCene) -
                // sam lot (amount) już nie jest tym, co realnie ląduje w koszyku jednym klikiem.
                long cenaZaSztuke = policzCene(1, buyPrice, amount);
                lore.add(Component.text("Kupno: " + cenaZaSztuke + " $ za szt.",
                        NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            }
            if (sellPrice >= 0) {
                String kluczCenyDyn = kluczCeny(matName, customId);
                int cenaKupnaZaLot = buyPrice < 0 ? -1
                        : (int) Math.round((double) buyPrice / amount * sellAmount);
                int cenaDyn = ceny.policzCeneSkupu(kluczCenyDyn, sellPrice, cenaKupnaZaLot);

                Component liniaSkup = Component.text("Skup: " + cenaDyn + " $ za " + sellAmount + " szt.",
                        NamedTextColor.AQUA);
                int kierunek = ceny.kierunekZmiany(kluczCenyDyn);
                if (ceny.czyZablokowany(kluczCenyDyn)) {
                    // Fioletowa gwiazdka zamiast strzalki - inna barwa i inny znak,
                    // zeby na pierwszy rzut oka bylo widac, ze to nie zwykle wahanie.
                    liniaSkup = liniaSkup.append(Component.text("  ★ EVENT", NamedTextColor.LIGHT_PURPLE));
                } else if (kierunek > 0) {
                    liniaSkup = liniaSkup.append(Component.text("  ▲", NamedTextColor.GREEN));
                } else if (kierunek < 0) {
                    liniaSkup = liniaSkup.append(Component.text("  ▼", NamedTextColor.RED));
                }
                lore.add(liniaSkup.decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.empty());

            if (buyPrice >= 0) {
                // Bez konkretnej ilości - LPM teraz otwiera wybór ilości (patrz otworzWyborIlosci),
                // nie kupuje od razu całego lota, więc "kup 64 szt." tutaj byłoby mylące.
                lore.add(Component.text("LPM — kupno",
                        NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("Tego nie da się kupić",
                        NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
            if (sellPrice >= 0) {
                String opisPpm = KATEGORIA_POJEDYNCZE.equals(catKey)
                        ? "PPM — sprzedaj dowolną ilość"
                        : "PPM — sprzedaj cały stack (po " + sellAmount + " szt.)";
                lore.add(Component.text(opisPpm,
                        NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("Shift+PPM — sprzedaj cały ekwipunek",
                        NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("Tego nie da się sprzedać",
                        NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }

            meta.lore(lore);
            item.setItemMeta(meta);

            gui.setItem(sloty.get(i - pageStart), item);
        }

        // Pasek nawigacyjny - sloty czytane z sklep-gui.yml (category-page.layout)
        Integer slotPrev = pierwszySlot(ekran, ShopSlotRole.NAV_PREV);
        if (page > 0 && slotPrev != null) {
            ItemStack prev = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta metaPrev = prev.getItemMeta();
            metaPrev.displayName(Component.text(guiContent.styl().poprzedniaStrona().tekst(), guiContent.styl().poprzedniaStrona().kolor(), TextDecoration.BOLD));
            metaPrev.lore(List.of(Component.text("Strona " + page + " / " + totalPages, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            prev.setItemMeta(metaPrev);
            gui.setItem(slotPrev, prev);
        }

        Integer slotNext = pierwszySlot(ekran, ShopSlotRole.NAV_NEXT);
        if (page < totalPages - 1 && slotNext != null) {
            ItemStack next = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta metaNext = next.getItemMeta();
            metaNext.displayName(Component.text(guiContent.styl().nastepnaStrona().tekst(), guiContent.styl().nastepnaStrona().kolor(), TextDecoration.BOLD));
            metaNext.lore(List.of(Component.text("Strona " + (page + 2) + " / " + totalPages, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            next.setItemMeta(metaNext);
            gui.setItem(slotNext, next);
        }

        Integer slotSort = pierwszySlot(ekran, ShopSlotRole.SORT);
        if (slotSort != null) {
            ItemStack sortowanie = new ItemStack(poSkupie ? Material.GOLD_INGOT : Material.HOPPER);
            ItemMeta metaSort = sortowanie.getItemMeta();
            metaSort.displayName(Component.text(guiContent.styl().sortowanie().tekst(), guiContent.styl().sortowanie().kolor(), TextDecoration.BOLD));
            List<Component> loreSort = new ArrayList<>();
            loreSort.add(Component.text(
                    poSkupie ? "Teraz: od najwyższego skupu" : "Teraz: od najtańszego kupna",
                    NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            loreSort.add(Component.empty());
            loreSort.add(Component.text("LPM — od najtańszego kupna",
                    poSkupie ? NamedTextColor.GRAY : NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false));
            loreSort.add(Component.text("PPM — od najwyższego skupu",
                    poSkupie ? NamedTextColor.YELLOW : NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            metaSort.lore(loreSort);
            sortowanie.setItemMeta(metaSort);
            gui.setItem(slotSort, sortowanie);
        }

        Integer slotPowrot = pierwszySlot(ekran, ShopSlotRole.NAV_BACK);
        if (slotPowrot != null) {
            ItemStack powrotKategorie = new ItemStack(Material.COMPASS);
            ItemMeta metaPowrot = powrotKategorie.getItemMeta();
            metaPowrot.displayName(Component.text(guiContent.styl().powrotDoKategorii().tekst(), guiContent.styl().powrotDoKategorii().kolor(), TextDecoration.BOLD));
            powrotKategorie.setItemMeta(metaPowrot);
            gui.setItem(slotPowrot, powrotKategorie);
        }

        Integer slotWyjscia = pierwszySlot(ekran, ShopSlotRole.EXIT);
        if (slotWyjscia != null) {
            boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
            ItemStack zamknijSklep = new ItemStack(zMenu ? Material.NETHER_STAR : Material.BARRIER);
            ItemMeta metaZamknij = zamknijSklep.getItemMeta();
            ShopGuiStyle.StyledLabel etykietaZamkniecia = zMenu ? guiContent.styl().wyjscieDoMenu() : guiContent.styl().wyjscieZamknij();
            metaZamknij.displayName(Component.text(etykietaZamkniecia.tekst(), etykietaZamkniecia.kolor(), TextDecoration.BOLD));
            zamknijSklep.setItemMeta(metaZamknij);
            gui.setItem(slotWyjscia, zamknijSklep);
        }

        player.openInventory(gui);
    }

    /**
     * Ustawia kolejność itemKeys wg wybranego trybu. Zwraca nową listę —
     * oryginalna kolejność z pliku YAML zostaje nietknięta.
     *
     * Pozycje bez ceny w danym trybie (np. brak sell-price przy sortowaniu
     * po skupie) lądują na końcu, żeby nie zaśmiecały początku listy.
     */
    private List<String> posortujItemy(String catKey, List<String> itemKeys, boolean poSkupie) {
        List<String> kopia = new ArrayList<>(itemKeys);
        kopia.sort((a, b) -> {
            double ca = cenaDoSortowania(catKey, a, poSkupie);
            double cb = cenaDoSortowania(catKey, b, poSkupie);
            // Brak ceny (-1) zawsze na koniec, niezależnie od kierunku.
            if (ca < 0 && cb < 0) return 0;
            if (ca < 0) return 1;
            if (cb < 0) return -1;
            return poSkupie ? Double.compare(cb, ca) : Double.compare(ca, cb);
        });
        return kopia;
    }

    /** Cena za sztukę w danym trybie, albo -1 gdy pozycja nie ma tej ceny. */
    private double cenaDoSortowania(String catKey, String itemKey, boolean poSkupie) {
        String path = "categories." + catKey + ".items." + itemKey + ".";
        if (poSkupie) {
            int cena = sklepConfig.getInt(path + "sell-price", -1);
            if (cena < 0) return -1;
            int lot = sklepConfig.getInt(path + "sell-amount",
                      sklepConfig.getInt(path + "amount", 1));
            return lot > 0 ? (double) cena / lot : -1;
        } else {
            int cena = sklepConfig.getInt(path + "buy-price", -1);
            if (cena < 0) return -1;
            int lot = sklepConfig.getInt(path + "amount", 1);
            return lot > 0 ? (double) cena / lot : -1;
        }
    }

    /** Ile kosztuje dokładnie {@code ilosc} sztuk, gdy lot {@code lot} kosztuje {@code cenaLotu}. Zaokrąglenie
     *  W GÓRĘ gwarantuje, że nigdy nie wyjdzie ułamek ani zero — nawet przy kupnie jednej sztuki taniego bloku. */
    private long policzCene(int ilosc, int cenaLotu, int lot) {
        if (lot <= 0) lot = 1;
        return Math.max(1L, (long) Math.ceil((double) ilosc * cenaLotu / lot));
    }

    /**
     * Ekran wyboru ilości przy kupnie (1/8/16/32/64 szt.) - otwierany zamiast
     * od razu kupować lot z sklep.yml. Cena liczy się wzorem policzCene(): kupno na
     * sztuki jest odrobinę droższe niż hurtem, co samo w sobie zachęca do brania stacków.
     *
     * @param ref klucz pozycji w configu, np. "bloki:3"
     */
    private void otworzWyborIlosci(Player player, String ref) {
        String[] czesci = ref.split(":");
        String path = "categories." + czesci[0] + ".items." + czesci[1] + ".";

        String matName = sklepConfig.getString(path + "material");
        Material material = matName != null ? Material.matchMaterial(matName) : null;
        if (material == null) return;

        int lot = sklepConfig.getInt(path + "amount", 1);
        int cenaLotu = sklepConfig.getInt(path + "buy-price", -1);
        if (cenaLotu < 0) {
            player.sendMessage(Component.text("Tego przedmiotu nie można kupić!", NamedTextColor.RED));
            return;
        }
        String nazwa = sklepConfig.getString(path + "display-name", material.name());

        ScreenLayout ekran = guiContent.buyPicker();
        Inventory gui = Bukkit.createInventory(null, ekran.size(),
                Component.text(TYTUL_WYBOR_ILOSCI + " " + nazwa, guiContent.styl().tytulWyborIlosci(), TextDecoration.BOLD));
        wypelnijTloSzare(gui, ekran);

        double saldo = economyManager.getKasa(player.getUniqueId());
        for (ShopSlotEntry wpis : ekran.slotsWithRole(ShopSlotRole.AMOUNT_SLOT)) {
            int ilosc = wpis.amount();
            long cena = policzCene(ilosc, cenaLotu, lot);
            boolean stac = saldo >= cena;

            // Podgląd pokazuje realny materiał, ale stos w GUI nie może przekroczyć 64.
            ItemStack opcja = new ItemStack(material, Math.min(ilosc, 64));
            ItemMeta meta = opcja.getItemMeta();
            meta.displayName(Component.text(ilosc + " szt.",
                    stac ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Cena: " + cena + " $", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            if (ilosc > 1) {
                lore.add(Component.text("(" + policzCene(1, cenaLotu, lot) + " $ za sztukę przy zakupie po 1)",
                        NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.empty());
            lore.add(Component.text(stac ? "LPM — kliknij, aby kupić" : "Za mało pieniędzy",
                    stac ? NamedTextColor.YELLOW : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Shift+LPM — kup maksimum (kasa + miejsce w ekwipunku)",
                    NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            opcja.setItemMeta(meta);
            gui.setItem(wpis.slot(), opcja);
        }

        Integer slotPowrot = pierwszySlot(ekran, ShopSlotRole.NAV_BACK);
        if (slotPowrot != null) {
            ItemStack powrot = new ItemStack(Material.ARROW);
            ItemMeta pm = powrot.getItemMeta();
            pm.displayName(Component.text(guiContent.styl().powrotZIlosci().tekst(), guiContent.styl().powrotZIlosci().kolor()).decoration(TextDecoration.ITALIC, false));
            powrot.setItemMeta(pm);
            gui.setItem(slotPowrot, powrot);
        }

        // UWAGA: put() MUSI iść PO openInventory(), nie przed. Jeśli gracz ma już otwarte
        // jakieś okno (kategorię albo poprzedni ekran wyboru ilości - patrz odświeżanie
        // w onInventoryClick), openInventory() samo w sobie najpierw ZAMYKA tamto okno,
        // co synchronicznie odpala onInventoryClose() i czyści wpis z mapy. Ustawiony
        // wcześniej wpis zostałby więc wyzerowany, zanim nowe okno w ogóle się otworzy.
        player.openInventory(gui);
        otwartyWyborIlosci.put(player.getUniqueId(), ref);
    }

    /** Wydaje towar i pobiera kasę za zakup z ekranu wyboru ilości. Zwraca false, gdy transakcja się nie powiodła. */
    private boolean kupIlosc(Player player, String ref, int ilosc) {
        String[] czesci = ref.split(":");
        String path = "categories." + czesci[0] + ".items." + czesci[1] + ".";

        String matName = sklepConfig.getString(path + "material");
        Material material = matName != null ? Material.matchMaterial(matName) : null;
        if (material == null) return false;

        int lot = sklepConfig.getInt(path + "amount", 1);
        int cenaLotu = sklepConfig.getInt(path + "buy-price", -1);
        if (cenaLotu < 0) return false;

        long cena = policzCene(ilosc, cenaLotu, lot);
        if (!economyManager.maWystarczajaco(player.getUniqueId(), cena)) {
            player.sendMessage(Component.text("Nie stać Cię! Potrzebujesz " + cena + " $.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return false;
        }

        // Miejsce sprawdzamy PRZED pobraniem kasy — inaczej gracz płaci za towar,
        // który wypadnie na ziemię albo zniknie.
        int wolneMiejsce = wolneMiejsceNa(player, material);
        if (wolneMiejsce < ilosc) {
            player.sendMessage(Component.text("Nie masz tyle miejsca w ekwipunku!", NamedTextColor.RED));
            return false;
        }

        economyManager.odejmijKase(player.getUniqueId(), cena);

        int zostalo = ilosc;
        int maxStack = material.getMaxStackSize();
        while (zostalo > 0) {
            int paczka = Math.min(zostalo, maxStack);
            player.getInventory().addItem(new ItemStack(material, paczka));
            zostalo -= paczka;
        }

        String nazwa = sklepConfig.getString(path + "display-name", material.name());
        player.sendMessage(Component.text("Kupiono " + ilosc + "x " + nazwa + " za " + cena + " $!",
                NamedTextColor.GREEN));
        return true;
    }

    /** Ile sztuk danego materiału zmieści się jeszcze w ekwipunku gracza (wolne sloty + luka w niepełnych stosach). */
    private int wolneMiejsceNa(Player player, Material material) {
        int wolne = 0;
        int maxStack = material.getMaxStackSize();
        for (ItemStack is : player.getInventory().getStorageContents()) {
            if (is == null || is.getType().isAir()) wolne += maxStack;
            else if (is.getType() == material) wolne += Math.max(0, maxStack - is.getAmount());
        }
        return wolne;
    }

    /**
     * Kupuje maksymalną ilość, na jaką gracza stać I która zmieści się w ekwipunku -
     * Shift+LPM na dowolnej opcji w ekranie wyboru ilości ("kup ile się da" zamiast
     * konkretnej liczby z przycisku).
     */
    private void kupMaksymalnaIlosc(Player player, String ref) {
        String[] czesci = ref.split(":");
        String path = "categories." + czesci[0] + ".items." + czesci[1] + ".";

        String matName = sklepConfig.getString(path + "material");
        Material material = matName != null ? Material.matchMaterial(matName) : null;
        if (material == null) return;

        int lot = sklepConfig.getInt(path + "amount", 1);
        int cenaLotu = sklepConfig.getInt(path + "buy-price", -1);
        if (cenaLotu < 0) {
            player.sendMessage(Component.text("Tego przedmiotu nie można kupić!", NamedTextColor.RED));
            return;
        }

        int wolneMiejsce = wolneMiejsceNa(player, material);
        if (wolneMiejsce <= 0) {
            player.sendMessage(Component.text("Nie masz miejsca w ekwipunku!", NamedTextColor.RED));
            return;
        }

        int ileStac;
        if (cenaLotu == 0) {
            ileStac = wolneMiejsce; // za darmo - ogranicza wyłącznie miejsce w ekwipunku
        } else {
            double saldo = economyManager.getKasa(player.getUniqueId());
            // Przybliżenie z floor, potem doszlifowane w pętli (max kilka kroków, bo ceil
            // w policzCene() może się różnić od przybliżenia o pojedyncze sztuki).
            long przyblizenie = (long) Math.floor(saldo * lot / cenaLotu);
            ileStac = (int) Math.max(0, Math.min(przyblizenie, wolneMiejsce));
            while (ileStac > 0 && policzCene(ileStac, cenaLotu, lot) > saldo) ileStac--;
            while (ileStac < wolneMiejsce && policzCene(ileStac + 1, cenaLotu, lot) <= saldo) ileStac++;
        }

        int ilosc = Math.min(wolneMiejsce, ileStac);
        if (ilosc <= 0) {
            player.sendMessage(Component.text("Nie stać Cię na ani jedną sztukę!", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        kupIlosc(player, ref, ilosc);
    }

    /** Kolejność kolorów dla teczowego tekstu - patrz teczowyTekst(). */
    private static final NamedTextColor[] TECZA = {
            NamedTextColor.RED, NamedTextColor.GOLD, NamedTextColor.YELLOW,
            NamedTextColor.GREEN, NamedTextColor.AQUA, NamedTextColor.LIGHT_PURPLE
    };

    /** Etykieta rotacyjnej oferty (patrz RotacjaManager) - jedyna linia lore renderowana na tęczowo. */
    private static final String LORE_OFERTA_CZASOWA = "OFERTA CZASOWA";

    /** Ten sam tekst, litera po literze w kolejnych kolorach tęczy - zamiast jednego stałego koloru linii. */
    private Component teczowyTekst(String tekst, boolean bold) {
        Component wynik = Component.empty();
        for (int i = 0; i < tekst.length(); i++) {
            Component litera = Component.text(String.valueOf(tekst.charAt(i)), TECZA[i % TECZA.length])
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, bold);
            wynik = wynik.append(litera);
        }
        return wynik;
    }

    /**
     * Wpina komponent instrumentu (róg kozi) z pola "instrument" w categories/*.yml,
     * jeśli takie pole jest ustawione - patrz kategoria "kolekcja" / RotacjaManager.
     * Bez tego wszystkie rogi kóz byłyby identycznym, domyślnym GOAT_HORN.
     */
    private void zastosujInstrument(ItemStack item, String configPath) {
        String instrument = sklepConfig.getString(configPath + "instrument", null);
        if (instrument == null) return;
        MusicInstrument muzInstrument = MusicInstrument.getByKey(NamespacedKey.minecraft(instrument));
        if (muzInstrument == null) {
            plugin.getLogger().warning("Shop: nieznany instrument '" + instrument + "' w " + configPath);
            return;
        }
        item.setData(DataComponentTypes.INSTRUMENT, muzInstrument);
    }

    /** Buduje kupiony item; jeśli wpis ma custom-id, doczepia PDC tag + display-name + lore (patrz kategorie "spawnery"/"kolekcja"). */
    private ItemStack stworzKupionyItem(Material material, int amount, String configPath) {
        String customId = sklepConfig.getString(configPath + "custom-id", null);
        String displayName = sklepConfig.getString(configPath + "display-name", null);
        List<String> lore = sklepConfig.getStringList(configPath + "lore");

        ItemStack item = stworzBazowyItem(material, amount, customId);
        zastosujInstrument(item, configPath);
        if (customId == null && displayName == null && lore.isEmpty()) return item;

        ItemMeta meta = item.getItemMeta();
        if (displayName != null) {
            meta.displayName(Component.text(displayName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        }
        if (!lore.isEmpty()) {
            List<Component> loreComponents = new ArrayList<>();
            for (String linia : lore) {
                loreComponents.add(linia.equals(LORE_OFERTA_CZASOWA)
                        ? teczowyTekst(linia, true)
                        : Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
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

    /**
     * Baza kupionego itemu - jeśli custom-id pasuje do zarejestrowanego custom itemu
     * (patrz CustomItemService, mainplugins-core), wydaje DOKŁADNIE ten custom item
     * (własny material/model/domyślna nazwa/lore z custom-items.yml) zamiast gołego
     * Materiału ze sklep.yml; sklep.yml nadal może nadpisać display-name/lore na wierzchu
     * (patrz stworzKupionyItem wyżej). Jeśli custom-id nie jest znane rejestrowi (np.
     * spawnery/generatory - stare, czysto PDC-tagowe użycie custom-id sprzed rejestru)
     * albo w ogóle go nie ma, zachowanie jest DOKŁADNIE takie jak wcześniej: goły Material.
     */
    private ItemStack stworzBazowyItem(Material material, int amount, String customId) {
        if (customId != null) {
            CustomItemService rejestr = CoreAPI.getCustomItemService();
            if (rejestr != null && rejestr.exists(customId)) {
                ItemStack custom = rejestr.create(customId, amount);
                if (custom != null) return custom;
            }
        }
        return new ItemStack(material, amount);
    }

    /**
     * Ile sztuk idzie w jednej transakcji skupu, ile sklep za taki lot płaci (bazowa
     * cena z cennika, przed korektą DynamicPriceManager) i z jakiej jest kategorii.
     *
     * cenaKupnaZaLot: cena kupna PRZELICZONA na ten sam lot co skup (nie zawsze
     * lot kupna i lot skupu to ta sama ilość — patrz sell-amount) - potrzebna
     * DynamicPriceManager do pilnowania, żeby skup nigdy nie przebił kupna.
     * -1, jeśli itemu nie da się kupić.
     */
    private record OfertaSkupu(int lot, int cenaZaLot, String kategoria, int cenaKupnaZaLot) {}

    /** Jedyna kategoria, w której sprzedaje się pojedyncze sztuki zamiast pełnych lotów. */
    private static final String KATEGORIA_POJEDYNCZE = "mineraly";

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
                if (lot <= 0) continue;

                // Cena kupna przeliczona na lot skupu - dla ochrony przed odwróceniem
                // marży w DynamicPriceManager (skup nigdy nie może przebić kupna).
                int cenaKupna = sklepConfig.getInt(path + "buy-price", -1);
                int lotKupna = sklepConfig.getInt(path + "amount", 1);
                int cenaKupnaZaLot = cenaKupna < 0 ? -1
                        : (int) Math.round((double) cenaKupna / lotKupna * lot);

                return new OfertaSkupu(lot, cena, catKey, cenaKupnaZaLot);
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

        if (posiadane == 0) {
            player.sendMessage(Component.text("Nie masz nic do sprzedania!", NamedTextColor.RED));
            return;
        }

        boolean pojedynczo = KATEGORIA_POJEDYNCZE.equals(oferta.kategoria());

        // Cena z cennika przechodzi przez DynamicPriceManager - mnożnik reagujący
        // na obrót tym itemem, z sufitem pilnującym, żeby skup nigdy nie przebił kupna.
        String kluczCeny = kluczCeny(material.name(), customId);
        int cenaZaLot = ceny.policzCeneSkupu(kluczCeny, oferta.cenaZaLot(), oferta.cenaKupnaZaLot());

        int doZabrania;
        long zarobek;

        if (pojedynczo) {
            // Rudy i Minerały: sprzedaje się WSZYSTKO co gracz ma (w danym trybie),
            // bez wymogu pełnego lotu. Cena zawsze w dół (floor): floor(a) + floor(b)
            // nigdy nie przekracza floor(a+b), więc rozbijanie sprzedaży na małe
            // partie nie daje żadnej przewagi - najwyżej gracz straci ułamek.
            doZabrania = posiadane;
            zarobek = (long) Math.floor((double) posiadane * cenaZaLot / oferta.lot());
        } else {
            // Reszta sklepu: wyłącznie pełne loty, jak dotąd.
            int loty = posiadane / oferta.lot();
            if (loty == 0) {
                player.sendMessage(Component.text(
                        "Sklep skupuje ten przedmiot po " + oferta.lot() + " szt. — masz " + posiadane + ".",
                        NamedTextColor.RED));
                return;
            }
            doZabrania = loty * oferta.lot();
            zarobek = (long) loty * cenaZaLot;
        }

        if (zarobek <= 0) {
            player.sendMessage(Component.text(
                    "Za tak małą ilość sklep nic by Ci nie zapłacił — sprzedaj więcej naraz.",
                    NamedTextColor.RED));
            return;
        }

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
        ceny.zarejestrujSprzedaz(kluczCeny, doZabrania);
        ceny.getStatystyki().zapiszTransakcje(kluczCeny, doZabrania, zarobek);

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

        // KLIKANIE W EKRANIE WYBORU ILOŚCI
        if (title.contains(TYTUL_WYBOR_ILOSCI)) {
            event.setCancelled(true);
            if (!klikniecieWGui) return;

            String ref = otwartyWyborIlosci.get(player.getUniqueId());
            if (ref == null) { player.closeInventory(); return; }

            ScreenLayout ekranWyboru = guiContent.buyPicker();
            Integer slotPowrotWyboru = pierwszySlot(ekranWyboru, ShopSlotRole.NAV_BACK);
            if (slotPowrotWyboru != null && event.getSlot() == slotPowrotWyboru) {
                otwartyWyborIlosci.remove(player.getUniqueId());
                otworzKategorieStrona(player, ref.split(":")[0],
                        playerPage.getOrDefault(player.getUniqueId(), 0));
                return;
            }
            for (ShopSlotEntry wpis : ekranWyboru.slotsWithRole(ShopSlotRole.AMOUNT_SLOT)) {
                if (event.getSlot() == wpis.slot()) {
                    // Shift+LPM na dowolnej opcji ignoruje konkretną liczbę z przycisku
                    // i kupuje maksimum, na jakie gracza stać i które zmieści się w ekwipunku.
                    if (event.isShiftClick() && event.isLeftClick()) {
                        kupMaksymalnaIlosc(player, ref);
                    } else {
                        kupIlosc(player, ref, wpis.amount());
                    }
                    otworzWyborIlosci(player, ref);            // odśwież ceny i saldo
                    return;
                }
            }
            return;
        }

        // KLIKANIE W GŁÓWNYM SKLEPIE
        if (title.contains("Sklep Serwerowy")) {
            event.setCancelled(true);
            if (!klikniecieWGui) return;

            ScreenLayout ekranGlowny = guiContent.mainMenu();

            // Przycisk wyjścia - sprawdzamy PO SLOCIE (roli EXIT), nie po materiale, żeby
            // nie kolidować z towarem sprzedawanym w sklepie o tym samym materiale
            // (np. Gwiazda Netheru w kategorii "Klejnoty i Rzadkości").
            Integer slotWyjsciaGlowny = pierwszySlot(ekranGlowny, ShopSlotRole.EXIT);
            if (slotWyjsciaGlowny != null && event.getSlot() == slotWyjsciaGlowny) {
                boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
                player.closeInventory();
                if (zMenu) player.performCommand("menu");
                return;
            }

            Integer slotSzukaj = pierwszySlot(ekranGlowny, ShopSlotRole.SEARCH);
            if (slotSzukaj != null && event.getSlot() == slotSzukaj) {
                czekaNaFraze.add(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(Component.text("Wpisz na czacie, czego szukasz.", NamedTextColor.AQUA));
                player.sendMessage(Component.text("Wpisz 'anuluj', żeby zrezygnować.", NamedTextColor.GRAY));
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
        if (event.getView().getTopInventory().getHolder() instanceof KategoriaHolder) {
            event.setCancelled(true);
            if (!klikniecieWGui) return;

            String catKey = playerCategory.get(player.getUniqueId());
            int currentPage = playerPage.getOrDefault(player.getUniqueId(), 0);
            if (catKey == null) return;

            ScreenLayout ekranKategorii = guiContent.categoryPage();
            List<ShopSlotEntry> itemSlotyKategorii = ekranKategorii.slotsWithRole(ShopSlotRole.ITEM_SLOT);

            // Logika dolnych przycisków - PO SLOCIE/ROLI, nie po materiale (patrz komentarz
            // przy analogicznym fragmencie dla głównego ekranu sklepu wyżej).
            int slotKlikniecia = event.getSlot();
            Integer slotSort = pierwszySlot(ekranKategorii, ShopSlotRole.SORT);
            Integer slotBack = pierwszySlot(ekranKategorii, ShopSlotRole.NAV_BACK);
            Integer slotExit = pierwszySlot(ekranKategorii, ShopSlotRole.EXIT);
            Integer slotPrev = pierwszySlot(ekranKategorii, ShopSlotRole.NAV_PREV);
            Integer slotNext = pierwszySlot(ekranKategorii, ShopSlotRole.NAV_NEXT);

            if (slotSort != null && slotKlikniecia == slotSort) {
                // Zmiana sortowania wraca na stronę 1 — przy innej kolejności
                // numer strony i tak przestaje cokolwiek znaczyć.
                sortowaniePoSkupie.put(player.getUniqueId(), event.isRightClick());
                otworzKategorieStrona(player, catKey, 0);
                return;
            } else if (slotBack != null && slotKlikniecia == slotBack) {
                boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
                otworzSklep(player, zMenu); // Wracamy do głównego widoku sklepu
                return;
            } else if (slotExit != null && slotKlikniecia == slotExit) {
                boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);
                player.closeInventory();
                if (zMenu) player.performCommand("menu");
                return;
            } else if (slotPrev != null && slotKlikniecia == slotPrev) {
                otworzKategorieStrona(player, catKey, currentPage - 1);
                return;
            } else if (slotNext != null && slotKlikniecia == slotNext) {
                otworzKategorieStrona(player, catKey, currentPage + 1);
                return;
            }

            ConfigurationSection itemsSection = sklepConfig.getConfigurationSection("categories." + catKey + ".items");
            if (itemsSection == null) return;

            // Ta sama arytmetyka co przy renderowaniu w otworzKategorieStrona() - kolejność
            // w sklep.yml + numer strony + slot w siatce (w tym ewentualne centrowanie,
            // patrz slotyStrony) muszą się zgadzać 1:1, inaczej klik trafi w zupełnie inny
            // item niż ten widoczny na ekranie. Stąd też to samo sortowanie (per gracz),
            // co przy renderze, musi zostać zastosowane i tutaj.
            boolean poSkupieKlik = sortowaniePoSkupie.getOrDefault(player.getUniqueId(), false);
            List<String> itemKeys = posortujItemy(catKey, new ArrayList<>(itemsSection.getKeys(false)), poSkupieKlik);
            int rozmiarStronyKlik = Math.max(itemSlotyKategorii.size(), 1);
            boolean jednaStronaKlik = itemKeys.size() <= rozmiarStronyKlik;
            List<Integer> slotyKlik = slotyStrony(itemSlotyKategorii, itemKeys.size(), jednaStronaKlik);

            int localIndex = slotyKlik.indexOf(slotKlikniecia);
            if (localIndex < 0) return; // Kliknięcie w tło/poza siatkę - nie ma tam żadnego itemu

            int absoluteIndex = currentPage * rozmiarStronyKlik + localIndex;
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
                // Pozycje z custom-id (spawnery, itemy specjalne) kupuje się po sztuce starą
                // ścieżką - mają własną logikę nadawania NBT, ekran wyboru ilości ich nie obsługuje.
                if (sklepConfig.getString(path + "custom-id", null) != null) {
                    if (economyManager.maWystarczajaco(player.getUniqueId(), buyPrice)) {
                        economyManager.odejmijKase(player.getUniqueId(), buyPrice);
                        player.getInventory().addItem(stworzKupionyItem(cfgMaterial, amount, path));
                        player.sendMessage(Component.text("Kupiono " + amount + "x za " + buyPrice + " $!", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Brak pieniędzy!", NamedTextColor.RED));
                    }
                    return;
                }
                otworzWyborIlosci(player, catKey + ":" + itemKeys.get(absoluteIndex));
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
            return;
        }

        // KLIKANIE W OKNIE WYNIKÓW WYSZUKIWANIA
        if (title.contains(TYTUL_WYNIKOW)) {
            event.setCancelled(true);
            if (!klikniecieWGui) return;

            ScreenLayout ekranWynikow = guiContent.searchResults();
            Integer slotPowrotWynikow = pierwszySlot(ekranWynikow, ShopSlotRole.NAV_BACK);
            if (slotPowrotWynikow != null && event.getSlot() == slotPowrotWynikow) {
                otworzSklep(player, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
                return;
            }

            List<String> wyniki = wynikiSzukania.get(player.getUniqueId());
            if (wyniki == null) return;

            int idx = lokalnyIndexDlaSlotu(event.getSlot(), ekranWynikow.slotsWithRole(ShopSlotRole.ITEM_SLOT));
            if (idx < 0 || idx >= wyniki.size()) return;

            String[] czesci = wyniki.get(idx).split(":");
            String catKey = czesci[0], itemKey = czesci[1];
            String path = "categories." + catKey + ".items." + itemKey + ".";

            String matName = sklepConfig.getString(path + "material", "STONE");
            Material material = Material.matchMaterial(matName);
            if (material == null) return;

            if (event.isLeftClick()) {
                if (sklepConfig.getInt(path + "buy-price", -1) < 0) {
                    player.sendMessage(Component.text("Tego przedmiotu nie można kupić!", NamedTextColor.RED));
                    return;
                }
                // Itemy z custom-id (spawnery, itemy specjalne) mają własną ścieżkę
                // kupna — tu ich nie obsługujemy, odsyłamy do kategorii.
                if (sklepConfig.getString(path + "custom-id", null) != null) {
                    player.sendMessage(Component.text("Ten przedmiot kup w jego kategorii.", NamedTextColor.YELLOW));
                    otworzKategorieStrona(player, catKey, 0);
                    return;
                }
                otworzWyborIlosci(player, catKey + ":" + itemKey);
            } else if (event.isRightClick()) {
                if (sklepConfig.getInt(path + "sell-price", -1) < 0) {
                    player.sendMessage(Component.text("Tego przedmiotu nie można sprzedać!", NamedTextColor.RED));
                    return;
                }
                // Jedna ścieżka sprzedaży co w kategorii (patrz wyżej) - custom-id musi iść
                // razem z materiałem, PPM = jeden stos, Shift+PPM = cały ekwipunek.
                String customIdWynik = sklepConfig.getString(path + "custom-id", null);
                sprzedajLoty(player, material, customIdWynik, event.isShiftClick() ? TrybSkupu.CALY_EKWIPUNEK : TrybSkupu.JEDEN_STOS);
            }
            return;
        }
    }

    @EventHandler
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!czekaNaFraze.remove(player.getUniqueId())) return;

        event.setCancelled(true);   // fraza nie trafia na czat publiczny
        String fraza = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();

        if (fraza.equalsIgnoreCase("anuluj")) {
            player.sendMessage(Component.text("Anulowano.", NamedTextColor.GRAY));
            return;
        }

        // Czat leci w wątku asynchronicznym, a GUI wolno otwierać TYLKO z głównego
        // wątku serwera — bez tego skoku Bukkit rzuci wyjątkiem.
        Bukkit.getScheduler().runTask(plugin, () -> otworzWyniki(player, fraza));
    }

    private void otworzWyniki(Player player, String fraza) {
        String szukane = fraza.toLowerCase();
        List<String> trafienia = new ArrayList<>();

        ConfigurationSection catSection = sklepConfig.getConfigurationSection("categories");
        if (catSection != null) {
            for (String catKey : catSection.getKeys(false)) {
                ConfigurationSection items =
                        sklepConfig.getConfigurationSection("categories." + catKey + ".items");
                if (items == null) continue;

                for (String itemKey : items.getKeys(false)) {
                    String path = "categories." + catKey + ".items." + itemKey + ".";
                    String mat = sklepConfig.getString(path + "material", "");
                    String nazwa = sklepConfig.getString(path + "display-name", mat);

                    // Szukamy i po polskiej nazwie, i po nazwie materiału.
                    if (nazwa.toLowerCase().contains(szukane) || mat.toLowerCase().contains(szukane)) {
                        trafienia.add(catKey + ":" + itemKey);
                    }
                }
            }
        }

        if (trafienia.isEmpty()) {
            player.sendMessage(Component.text("Nic nie znaleziono dla: " + fraza, NamedTextColor.RED));
            otworzSklep(player, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
            return;
        }

        ScreenLayout ekran = guiContent.searchResults();
        List<ShopSlotEntry> itemSloty = ekran.slotsWithRole(ShopSlotRole.ITEM_SLOT);

        // Więcej wyników niż mieści siatka - obcinamy i mówimy o tym graczowi.
        boolean obciete = trafienia.size() > itemSloty.size();
        if (obciete) trafienia = trafienia.subList(0, itemSloty.size());
        wynikiSzukania.put(player.getUniqueId(), trafienia);

        Inventory gui = Bukkit.createInventory(null, ekran.size(),
                Component.text(TYTUL_WYNIKOW + fraza, guiContent.styl().tytulWyniki(), TextDecoration.BOLD));
        wypelnijTloSzare(gui, ekran);

        for (int i = 0; i < trafienia.size(); i++) {
            String[] czesci = trafienia.get(i).split(":");
            gui.setItem(itemSloty.get(i).slot(), zbudujItemWyniku(czesci[0], czesci[1]));
        }

        Integer slotPowrot = pierwszySlot(ekran, ShopSlotRole.NAV_BACK);
        if (slotPowrot != null) {
            ItemStack powrot = new ItemStack(Material.COMPASS);
            ItemMeta metaPowrot = powrot.getItemMeta();
            metaPowrot.displayName(Component.text(guiContent.styl().powrotZWynikow().tekst(), guiContent.styl().powrotZWynikow().kolor(), TextDecoration.BOLD));
            powrot.setItemMeta(metaPowrot);
            gui.setItem(slotPowrot, powrot);
        }

        player.openInventory(gui);
        player.sendMessage(Component.text("Znaleziono: " + trafienia.size()
                + (obciete ? " (pokazano pierwsze " + itemSloty.size() + ")" : ""),
                NamedTextColor.AQUA));
    }

    /**
     * Item do okna wyników — te same reguły renderu co w otworzKategorieStrona()
     * (pojedyncza sztuka w ikonie, cena kupna ZA SZTUKĘ, nie za lot — patrz komentarz
     * tam), tylko z dopiskiem, z jakiej kategorii pochodzi. Patch pierwotnie budował
     * to inaczej (cały lot w ikonie + cena za cały lot w lore) - niespójne z resztą
     * sklepu, więc dociągnięte do tego samego wzoru.
     */
    private ItemStack zbudujItemWyniku(String catKey, String itemKey) {
        String path = "categories." + catKey + ".items." + itemKey + ".";
        String matName = sklepConfig.getString(path + "material", "STONE");
        Material material = Material.matchMaterial(matName);
        if (material == null) material = Material.STONE;

        int amount = sklepConfig.getInt(path + "amount", 1);
        int sellAmount = sklepConfig.getInt(path + "sell-amount", amount);
        int buyPrice = sklepConfig.getInt(path + "buy-price", -1);
        int sellPrice = sklepConfig.getInt(path + "sell-price", -1);
        String customId = sklepConfig.getString(path + "custom-id", null);
        String customDisplayName = sklepConfig.getString(path + "display-name", null);
        String katNazwa = sklepConfig.getString("categories." + catKey + ".name", catKey);

        ItemStack item = new ItemStack(material, 1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(customDisplayName != null
                ? Component.text(customDisplayName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                : Component.text(material.name(), NamedTextColor.YELLOW, TextDecoration.BOLD));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Kategoria: " + katNazwa, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());

        if (buyPrice >= 0) {
            long cenaZaSztuke = policzCene(1, buyPrice, amount);
            lore.add(Component.text("Kupno: " + cenaZaSztuke + " $ za szt.",
                    NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        }
        if (sellPrice >= 0) {
            String kluczCenyDyn = kluczCeny(matName, customId);
            int cenaKupnaZaLot = buyPrice < 0 ? -1
                    : (int) Math.round((double) buyPrice / amount * sellAmount);
            int cenaDyn = ceny.policzCeneSkupu(kluczCenyDyn, sellPrice, cenaKupnaZaLot);

            Component liniaSkup = Component.text("Skup: " + cenaDyn + " $ za " + sellAmount + " szt.",
                    NamedTextColor.AQUA);
            int kierunek = ceny.kierunekZmiany(kluczCenyDyn);
            if (ceny.czyZablokowany(kluczCenyDyn)) {
                liniaSkup = liniaSkup.append(Component.text("  ★ EVENT", NamedTextColor.LIGHT_PURPLE));
            } else if (kierunek > 0) {
                liniaSkup = liniaSkup.append(Component.text("  ▲", NamedTextColor.GREEN));
            } else if (kierunek < 0) {
                liniaSkup = liniaSkup.append(Component.text("  ▼", NamedTextColor.RED));
            }
            lore.add(liniaSkup.decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());

        if (buyPrice >= 0) {
            lore.add(Component.text("LPM — kupno",
                    NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Tego nie da się kupić",
                    NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        if (sellPrice >= 0) {
            String opisPpm = KATEGORIA_POJEDYNCZE.equals(catKey)
                    ? "PPM — sprzedaj dowolną ilość"
                    : "PPM — sprzedaj cały stack (po " + sellAmount + " szt.)";
            lore.add(Component.text(opisPpm,
                    NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Shift+PPM — sprzedaj cały ekwipunek",
                    NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Tego nie da się sprzedać",
                    NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            otwartyWyborIlosci.remove(player.getUniqueId());
            wynikiSzukania.remove(player.getUniqueId());
        }
        // czekaNaFraze NIE czyścimy przy zamknięciu okna — gracz właśnie po to zamknął
        // GUI, żeby móc coś wpisać na czacie.
    }

    public void wyczyscGracza(UUID uuid) {
        sortowaniePoSkupie.remove(uuid);
    }
}