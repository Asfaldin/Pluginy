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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Cała treść sklepu (kategorie, ceny, ikonki, ilości) żyje w sklep.yml, edytowalnym
 * na żywo przez /reloadsklep - ta klasa tylko RENDERUJE tę konfigurację i obsługuje
 * kliknięcia. Domyślna zawartość poniżej (zbudujPelnySklep i kategorie*()) to jednorazowy
 * seed nowej instalacji / jednorazowa migracja z v1 (patrz sklep-v2-zaladowany) - jeśli
 * admin już coś w sklep.yml zmienił, ten kod nigdy więcej tego nie nadpisze.
 *
 * Filozofia cenowa (patrz kategorie*()): cena sprzedaży to procent ceny kupna, malejący
 * z rzadkością - odnawialne surowce (uprawy, drewno, kamień) mają wysoki zwrot (~70%),
 * bo to ma być główne, uczciwe źródło dochodu z farmienia. Rzadkie/late-game przedmioty
 * mają niski zwrot (~20-35%) i wysoką cenę kupna (wg tabeli WARTOSCI_BLOKOW z
 * IslandManager jako punktu odniesienia) - są kupowalne jako "money sink" dla bogatych
 * graczy, ale zawsze bardziej opłaca się je zdobyć niż odkupić.
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
    }

    private void stworzLubWczytajPlikSklepu() {
        sklepFile = new File(plugin.getDataFolder(), "sklep.yml");
        if (!sklepFile.exists()) {
            sklepFile.getParentFile().mkdirs();
        }
        sklepConfig = YamlConfiguration.loadConfiguration(sklepFile);

        // Spawnery/Itemy Specjalne - niezależny, per-wpis guard (jak od zawsze), żeby
        // dołożyć brakujące pozycje nawet na serwerze, gdzie kategoria już istnieje.
        dodajSpawneryJesliBrak();
        dodajSpecjalneJesliBrak();

        // v2: pełny sklep, 16 kategorii ułożonych w ramkę - JEDNORAZOWA migracja (nie
        // rusza niczego, co admin już ręcznie zmienił po tym, jak raz się wykona).
        if (!sklepConfig.getBoolean("sklep-v2-zaladowany", false)) {
            zbudujPelnySklep();
            sklepConfig.set("sklep-v2-zaladowany", true);
        }

        try {
            sklepConfig.save(sklepFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać sklep.yml: " + e.getMessage());
        }
    }

    // ============================================== v2: pełny sklep, gwiazda jak w /quest ====

    /**
     * 15 slotów ramion gwiazdy o tej samej geometrii co SLOTY_GWIAZDY w QuestManager
     * (mainplugins-quests) - promienie N/S/E/W/NE/NW/SE/SW rozchodzące się z centrum
     * (SLOT_CENTRUM_GWIAZDY, patrz niżej). Jedno ramię (N) ma tylko 1 punkt zamiast 2,
     * żeby razem z centrum wyszło dokładnie 16 = liczba kategorii (środek gwiazdy trzyma
     * realną kategorię, nie samą dekorację - stąd niepełna symetria w tym jednym miejscu).
     * Sloty celowo NIE sięgają dolnego paska (45-53), bo tam w otworzSklep()/
     * otworzKategorieStrona() stoją przyciski nawigacji. Kolejność w tablicy = kolejność
     * przypisania kategorii z zbudujPelnySklep() (N, S, E, W, NE, NW, SE, SW).
     */
    private static final int[] SLOTY_RAMKI = {
            13,      // N
            31, 40,  // S
            23, 24,  // E
            21, 20,  // W
            14, 6,   // NE
            12, 2,   // NW
            32, 42,  // SE
            30, 38   // SW
    };

    /** Środek gwiazdy - najbardziej efektowna kategoria (Klejnoty i Rzadkości) ląduje właśnie tu. */
    private static final int SLOT_CENTRUM_GWIAZDY = 22;

    /**
     * SLOT_CENTRUM_GWIAZDY + wszystkie SLOTY_RAMKI razem (16 pozycji) - używane jako:
     * (1) zestaw slotów do pomalowania żółtym akcentem w tle (wypelnijTloGwiazdy), tej
     * samej gwiazdy na obu ekranach (kategorie i lista itemów w kategorii - patrz
     * otworzKategorieStrona), (2) mapowanie "lokalny indeks itemu 0-15" -> "slot GUI"
     * przy renderowaniu itemów kategorii tym samym kształtem.
     */
    private static final int[] SLOTY_GWIAZDY_PELNA = buduSlotyGwiazdyPelna();

    private static int[] buduSlotyGwiazdyPelna() {
        int[] pelna = new int[SLOTY_RAMKI.length + 1];
        pelna[0] = SLOT_CENTRUM_GWIAZDY;
        System.arraycopy(SLOTY_RAMKI, 0, pelna, 1, SLOTY_RAMKI.length);
        return pelna;
    }

    private void zbudujPelnySklep() {
        // Placeholdery z v1 - zastąpione pełną treścią niżej pod nowymi kluczami.
        sklepConfig.set("categories.surowce", null);
        sklepConfig.set("categories.rolnictwo", null);

        // Klejnoty i Rzadkości - najbardziej efektowna kategoria, na SAMYM środku gwiazdy.
        dodajKategorieTowarowa("klejnoty", "Klejnoty i Rzadkości", "DIAMOND_BLOCK", SLOT_CENTRUM_GWIAZDY, kategoriaKlejnoty());

        int i = 0;
        dodajKategorieTowarowa("surowce_ziemi", "Surowce Ziemi", "GRASS_BLOCK", SLOTY_RAMKI[i++], kategoriaSurowceZiemi());
        dodajKategorieTowarowa("drewno", "Drewno", "OAK_LOG", SLOTY_RAMKI[i++], kategoriaDrewno());
        dodajKategorieTowarowa("rudy_sztabki", "Rudy i Sztabki", "RAW_GOLD", SLOTY_RAMKI[i++], kategoriaRudySztabki());
        dodajKategorieTowarowa("redstone_mechanizmy", "Redstone i Mechanizmy", "REDSTONE_BLOCK", SLOTY_RAMKI[i++], kategoriaRedstoneMechanizmy());
        dodajKategorieTowarowa("rolnictwo2", "Rolnictwo", "WHEAT", SLOTY_RAMKI[i++], kategoriaRolnictwo());
        dodajKategorieTowarowa("hodowla", "Hodowla", "WHITE_WOOL", SLOTY_RAMKI[i++], kategoriaHodowla());
        dodajKategorieTowarowa("lowca", "Łowca", "BONE", SLOTY_RAMKI[i++], kategoriaLowca());
        dodajKategorieTowarowa("rybactwo", "Rybactwo", "COD", SLOTY_RAMKI[i++], kategoriaRybactwo());
        dodajKategorieTowarowa("nether", "Nether", "MAGMA_BLOCK", SLOTY_RAMKI[i++], kategoriaNether());
        dodajKategorieTowarowa("koniec_swiata", "End", "ELYTRA", SLOTY_RAMKI[i++], kategoriaEnd());
        dodajKategorieTowarowa("barwniki", "Barwniki", "LIME_DYE", SLOTY_RAMKI[i++], kategoriaBarwniki());
        dodajKategorieTowarowa("bloki_budowlane", "Bloki Budowlane", "BRICKS", SLOTY_RAMKI[i++], kategoriaBlokiBudowlane());
        dodajKategorieTowarowa("alchemia", "Eliksiry i Alchemia", "BREWING_STAND", SLOTY_RAMKI[i++], kategoriaAlchemia());

        // Spawnery i Itemy Specjalne już istnieją (dodajSpawneryJesliBrak/dodajSpecjalneJesliBrak) -
        // tylko przenosimy je na ich miejsce w gwieździe.
        sklepConfig.set("categories.spawnery.slot", SLOTY_RAMKI[i++]);
        sklepConfig.set("categories.specjalne.slot", SLOTY_RAMKI[i]);
    }

    private record ItemDef(Material material, int amount, double buyPrice, double sellPrice) {
        private ItemDef(Material material, int amount, double buyPrice) {
            this(material, amount, buyPrice, -1); // buy-only (crafted/convenience - patrz komentarze przy kategoriach)
        }
    }

    /** Zapisuje kategorię "towarową" (bez custom-id) - jedna pozycja na item, slot = kolejność na liście. */
    private void dodajKategorieTowarowa(String key, String name, String icon, int catSlot, List<ItemDef> items) {
        sklepConfig.set("categories." + key + ".name", name);
        sklepConfig.set("categories." + key + ".icon", icon);
        sklepConfig.set("categories." + key + ".slot", catSlot);
        for (int i = 0; i < items.size(); i++) {
            ItemDef it = items.get(i);
            String path = "categories." + key + ".items." + i + ".";
            sklepConfig.set(path + "material", it.material().name());
            sklepConfig.set(path + "slot", i);
            sklepConfig.set(path + "amount", it.amount());
            sklepConfig.set(path + "buy-price", it.buyPrice());
            if (it.sellPrice() >= 0) sklepConfig.set(path + "sell-price", it.sellPrice());
        }
    }

    // ---- Kategorie: tier "odnawialne" (~70% zwrotu) - główne, uczciwe źródło dochodu ----

    private List<ItemDef> kategoriaSurowceZiemi() {
        return List.of(
                new ItemDef(Material.DIRT, 64, 1.0, 0.7),
                new ItemDef(Material.COBBLESTONE, 64, 1.0, 0.7),
                new ItemDef(Material.STONE, 64, 1.5, 1.0),
                new ItemDef(Material.GRAVEL, 64, 1.5, 1.0),
                new ItemDef(Material.SAND, 64, 1.5, 1.0),
                new ItemDef(Material.RED_SAND, 64, 2.0, 1.4),
                new ItemDef(Material.CLAY_BALL, 64, 3.0, 2.0),
                new ItemDef(Material.GRANITE, 64, 1.5, 1.0),
                new ItemDef(Material.DIORITE, 64, 1.5, 1.0),
                new ItemDef(Material.ANDESITE, 64, 1.5, 1.0),
                new ItemDef(Material.DEEPSLATE, 64, 2.5, 1.7),
                new ItemDef(Material.CALCITE, 64, 2.0, 1.4),
                new ItemDef(Material.TUFF, 64, 2.0, 1.4)
        );
    }

    private List<ItemDef> kategoriaDrewno() {
        return List.of(
                new ItemDef(Material.OAK_LOG, 64, 3.0, 2.1),
                new ItemDef(Material.SPRUCE_LOG, 64, 3.0, 2.1),
                new ItemDef(Material.BIRCH_LOG, 64, 3.0, 2.1),
                new ItemDef(Material.JUNGLE_LOG, 64, 4.0, 2.8),
                new ItemDef(Material.ACACIA_LOG, 64, 4.0, 2.8),
                new ItemDef(Material.DARK_OAK_LOG, 64, 4.0, 2.8),
                new ItemDef(Material.MANGROVE_LOG, 64, 4.0, 2.8),
                new ItemDef(Material.CHERRY_LOG, 64, 5.0, 3.5),
                new ItemDef(Material.CRIMSON_STEM, 64, 6.0, 4.2),
                new ItemDef(Material.WARPED_STEM, 64, 6.0, 4.2),
                new ItemDef(Material.BAMBOO, 64, 1.0, 0.7)
        );
    }

    private List<ItemDef> kategoriaRolnictwo() {
        return List.of(
                new ItemDef(Material.WHEAT, 64, 2.0, 1.4),
                new ItemDef(Material.WHEAT_SEEDS, 64, 1.0, 0.7),
                new ItemDef(Material.CARROT, 64, 2.0, 1.4),
                new ItemDef(Material.POTATO, 64, 2.0, 1.4),
                new ItemDef(Material.BEETROOT, 64, 2.0, 1.4),
                new ItemDef(Material.BEETROOT_SEEDS, 64, 1.0, 0.7),
                new ItemDef(Material.MELON_SLICE, 64, 2.0, 1.4),
                new ItemDef(Material.PUMPKIN, 64, 3.0, 2.1),
                new ItemDef(Material.SUGAR_CANE, 64, 2.0, 1.4),
                new ItemDef(Material.COCOA_BEANS, 64, 3.0, 2.1),
                new ItemDef(Material.NETHER_WART, 64, 4.0, 2.8),
                new ItemDef(Material.GOLDEN_CARROT, 64, 40.0, 12.0) // dawny demo-item v1, przeniesiony tu bez zmian ceny
        );
    }

    // ---- Kategorie: tier "pospolite" (~60% zwrotu) - łatwe do zdobycia, ale wymagają walki/hodowli ----

    private List<ItemDef> kategoriaHodowla() {
        return List.of(
                new ItemDef(Material.WHITE_WOOL, 64, 5.0, 3.0),
                new ItemDef(Material.EGG, 64, 3.0, 1.8),
                new ItemDef(Material.FEATHER, 64, 3.0, 1.8),
                new ItemDef(Material.LEATHER, 64, 6.0, 3.6),
                new ItemDef(Material.RABBIT_HIDE, 64, 4.0, 2.4),
                new ItemDef(Material.HONEY_BOTTLE, 16, 8.0, 4.8),
                new ItemDef(Material.MILK_BUCKET, 16, 10.0, 4.0),
                new ItemDef(Material.BEEF, 64, 5.0, 3.0),
                new ItemDef(Material.PORKCHOP, 64, 5.0, 3.0),
                new ItemDef(Material.CHICKEN, 64, 4.0, 2.4),
                new ItemDef(Material.MUTTON, 64, 4.0, 2.4),
                new ItemDef(Material.RABBIT, 64, 4.0, 2.4)
        );
    }

    private List<ItemDef> kategoriaLowca() {
        return List.of(
                new ItemDef(Material.ROTTEN_FLESH, 64, 1.0, 0.6),
                new ItemDef(Material.BONE, 64, 3.0, 1.8),
                new ItemDef(Material.STRING, 64, 3.0, 1.8),
                new ItemDef(Material.SPIDER_EYE, 64, 3.0, 1.8),
                new ItemDef(Material.GUNPOWDER, 64, 5.0, 3.0),
                new ItemDef(Material.SLIME_BALL, 64, 4.0, 2.4),
                new ItemDef(Material.PHANTOM_MEMBRANE, 64, 10.0, 6.0),
                new ItemDef(Material.MAGMA_CREAM, 64, 8.0, 4.8),
                new ItemDef(Material.BLAZE_ROD, 64, 20.0, 10.0), // tier "średnie" - patrz sekcja niżej, ale tematycznie łowieckie
                new ItemDef(Material.GHAST_TEAR, 16, 25.0, 8.75), // tier "rzadkie"
                new ItemDef(Material.WITHER_SKELETON_SKULL, 4, 400.0, 140.0) // tier "rzadkie"
        );
    }

    private List<ItemDef> kategoriaRybactwo() {
        return List.of(
                new ItemDef(Material.COD, 64, 3.0, 1.8),
                new ItemDef(Material.SALMON, 64, 4.0, 2.4),
                new ItemDef(Material.TROPICAL_FISH, 64, 6.0, 3.6),
                new ItemDef(Material.PUFFERFISH, 64, 5.0, 3.0),
                new ItemDef(Material.PRISMARINE_SHARD, 64, 6.0, 3.6),
                new ItemDef(Material.PRISMARINE_CRYSTALS, 64, 6.0, 3.6),
                new ItemDef(Material.NAUTILUS_SHELL, 8, 60.0, 21.0), // tier "rzadkie"
                new ItemDef(Material.HEART_OF_THE_SEA, 1, 1500.0, 300.0) // tier "endgame"
        );
    }

    // ---- Kategorie: tier "średnie" (~50% zwrotu) - rudy/metale, główny trzon progresu ----

    private List<ItemDef> kategoriaRudySztabki() {
        return List.of(
                new ItemDef(Material.COAL, 64, 6.0, 3.6), // pospolite (60%)
                new ItemDef(Material.RAW_IRON, 64, 12.0, 7.2), // pospolite (60%)
                new ItemDef(Material.IRON_INGOT, 64, 22.0, 11.0),
                new ItemDef(Material.RAW_COPPER, 64, 5.0, 3.0), // pospolite (60%)
                new ItemDef(Material.COPPER_INGOT, 64, 9.0, 4.5),
                new ItemDef(Material.RAW_GOLD, 64, 30.0, 18.0), // pospolite (60%)
                new ItemDef(Material.GOLD_INGOT, 64, 56.0, 28.0),
                new ItemDef(Material.REDSTONE, 64, 13.0, 6.5),
                new ItemDef(Material.LAPIS_LAZULI, 64, 18.0, 9.0),
                new ItemDef(Material.DIAMOND, 4, 1000.0, 350.0), // tier "rzadkie" (35%)
                new ItemDef(Material.EMERALD, 4, 750.0, 260.0), // tier "rzadkie" (35%)
                new ItemDef(Material.NETHERITE_SCRAP, 4, 600.0, 120.0), // tier "endgame" (20%)
                new ItemDef(Material.NETHERITE_INGOT, 1, 2800.0, 550.0) // tier "endgame" (20%)
        );
    }

    private List<ItemDef> kategoriaNether() {
        return List.of(
                new ItemDef(Material.NETHERRACK, 64, 1.0, 0.7), // odnawialne (70%)
                new ItemDef(Material.SOUL_SAND, 64, 3.0, 2.1), // odnawialne (70%)
                new ItemDef(Material.SOUL_SOIL, 64, 3.0, 2.1), // odnawialne (70%)
                new ItemDef(Material.BLAZE_POWDER, 64, 10.0, 6.0), // pospolite (60%)
                new ItemDef(Material.GLOWSTONE_DUST, 64, 6.0, 3.6), // pospolite (60%)
                new ItemDef(Material.QUARTZ, 64, 10.0, 5.0),
                new ItemDef(Material.OBSIDIAN, 64, 15.0, 9.0),
                new ItemDef(Material.CRYING_OBSIDIAN, 16, 25.0, 8.75), // tier "rzadkie" (35%)
                new ItemDef(Material.ANCIENT_DEBRIS, 4, 350.0, 70.0) // tier "endgame" (20%)
        );
    }

    // ---- Kategoria: End - mix "odnawialne" (rośliny Endu) i "rzadkie"/"endgame" (reszta) ----

    private List<ItemDef> kategoriaEnd() {
        return List.of(
                new ItemDef(Material.END_STONE, 64, 5.0, 3.0), // pospolite (60%)
                new ItemDef(Material.CHORUS_FRUIT, 64, 4.0, 2.8), // odnawialne, farma w Endzie (70%)
                new ItemDef(Material.POPPED_CHORUS_FRUIT, 64, 6.0, 3.0),
                new ItemDef(Material.PURPUR_BLOCK, 64, 8.0, 4.0),
                new ItemDef(Material.END_ROD, 32, 10.0, 5.0),
                new ItemDef(Material.ENDER_PEARL, 16, 20.0, 7.0), // tier "rzadkie" (35%)
                new ItemDef(Material.ENDER_EYE, 8, 60.0, 18.0), // tier "rzadkie" (30%)
                new ItemDef(Material.DRAGON_BREATH, 16, 80.0, 20.0), // tier "rzadkie" (25%)
                new ItemDef(Material.SHULKER_SHELL, 4, 300.0, 75.0), // tier "endgame" (25%)
                new ItemDef(Material.ELYTRA, 1, 8000.0, 1600.0) // tier "endgame" (20%)
        );
    }

    // ---- Kategoria: Barwniki - tanie, jednolita cena, wszystkie 16 kolorów ----

    private List<ItemDef> kategoriaBarwniki() {
        Material[] kolory = {
                Material.WHITE_DYE, Material.ORANGE_DYE, Material.MAGENTA_DYE, Material.LIGHT_BLUE_DYE,
                Material.YELLOW_DYE, Material.LIME_DYE, Material.PINK_DYE, Material.GRAY_DYE,
                Material.LIGHT_GRAY_DYE, Material.CYAN_DYE, Material.PURPLE_DYE, Material.BLUE_DYE,
                Material.BROWN_DYE, Material.GREEN_DYE, Material.RED_DYE, Material.BLACK_DYE
        };
        List<ItemDef> lista = new ArrayList<>();
        for (Material m : kolory) lista.add(new ItemDef(m, 64, 3.0, 2.0));
        return lista;
    }

    // ---- Kategoria: Bloki Budowlane - crafted/dekoracyjne, celowo BEZ sprzedaży (patrz komentarz) ----

    /**
     * Wszystko tu jest crafted z surowców sprzedawanych gdzie indziej (piasek->szkło,
     * glina->terakota, kwarc->blok) - celowo BEZ sell-price, żeby nie dało się kupić
     * surowca, skraftować i odsprzedać z zyskiem jako "darmowe" pieniądze. To czysto
     * wygodowa kategoria (budowa wyspy bez ręcznego craftingu), nie źródło dochodu.
     */
    private List<ItemDef> kategoriaBlokiBudowlane() {
        return List.of(
                new ItemDef(Material.BRICK, 64, 4.0),
                new ItemDef(Material.NETHER_BRICK, 64, 4.0),
                new ItemDef(Material.SANDSTONE, 64, 2.0),
                new ItemDef(Material.RED_SANDSTONE, 64, 2.0),
                new ItemDef(Material.TERRACOTTA, 64, 3.0),
                new ItemDef(Material.WHITE_CONCRETE, 64, 5.0),
                new ItemDef(Material.WHITE_CONCRETE_POWDER, 64, 4.0),
                new ItemDef(Material.GLASS, 64, 2.0),
                new ItemDef(Material.GLASS_PANE, 64, 1.0),
                new ItemDef(Material.PRISMARINE, 64, 8.0),
                new ItemDef(Material.SEA_LANTERN, 16, 20.0),
                new ItemDef(Material.QUARTZ_BLOCK, 16, 90.0)
        );
    }

    // ---- Kategoria: Klejnoty i Rzadkości - skompresowane bloki + unikaty, tier "endgame" (~20-25%) ----

    private List<ItemDef> kategoriaKlejnoty() {
        return List.of(
                new ItemDef(Material.GOLD_BLOCK, 1, 500.0, 250.0), // 9x gold ingot, ~50%
                new ItemDef(Material.DIAMOND_BLOCK, 1, 2250.0, 790.0), // 9x diamond, ~35%
                new ItemDef(Material.EMERALD_BLOCK, 1, 1690.0, 585.0), // 9x emerald, ~35%
                new ItemDef(Material.NETHERITE_BLOCK, 1, 25000.0, 5000.0), // 9x netherite ingot, ~20%
                new ItemDef(Material.BEACON, 1, 15000.0, 3000.0),
                new ItemDef(Material.NETHER_STAR, 1, 20000.0, 4000.0),
                new ItemDef(Material.TOTEM_OF_UNDYING, 1, 12000.0, 2400.0),
                new ItemDef(Material.ENCHANTED_GOLDEN_APPLE, 1, 8000.0, 1600.0)
        );
    }

    // ---- Kategoria: Eliksiry i Alchemia - składniki, celowo BEZ sprzedaży (patrz Bloki Budowlane) ----

    private List<ItemDef> kategoriaAlchemia() {
        return List.of(
                new ItemDef(Material.GLASS_BOTTLE, 16, 2.0),
                new ItemDef(Material.SUGAR, 64, 2.0, 1.4), // odnawialne (70%) - proste, z trzciny cukrowej
                new ItemDef(Material.FERMENTED_SPIDER_EYE, 16, 10.0),
                new ItemDef(Material.GLISTERING_MELON_SLICE, 16, 8.0),
                new ItemDef(Material.RABBIT_FOOT, 16, 20.0, 7.0), // tier "rzadkie" (35%) - rzadki drop
                new ItemDef(Material.TURTLE_SCUTE, 8, 40.0, 14.0), // tier "rzadkie" (35%)
                new ItemDef(Material.BREWING_STAND, 1, 80.0),
                new ItemDef(Material.CAULDRON, 1, 40.0)
        );
    }

    // ---- Redstone i Mechanizmy - crafted, celowo BEZ sprzedaży (patrz Bloki Budowlane) ----

    private List<ItemDef> kategoriaRedstoneMechanizmy() {
        return List.of(
                new ItemDef(Material.PISTON, 16, 20.0),
                new ItemDef(Material.STICKY_PISTON, 16, 30.0),
                new ItemDef(Material.HOPPER, 8, 60.0),
                new ItemDef(Material.DROPPER, 16, 15.0),
                new ItemDef(Material.DISPENSER, 16, 25.0),
                new ItemDef(Material.OBSERVER, 16, 25.0),
                new ItemDef(Material.REDSTONE_LAMP, 16, 20.0),
                new ItemDef(Material.REPEATER, 16, 10.0),
                new ItemDef(Material.COMPARATOR, 16, 25.0),
                new ItemDef(Material.TNT, 16, 40.0),
                new ItemDef(Material.REDSTONE_BLOCK, 16, 110.0, 54.0) // 9x redstone, ~50%
        );
    }

    // ============================================== v1: Spawnery / Specjalne (bez zmian logiki) ====

    /** Spawnery: 5 itemów o tym samym Material.SPAWNER, rozróżnianych po custom-id (patrz mainplugins-spawners). */
    private void dodajSpawneryJesliBrak() {
        if (sklepConfig.contains("categories.spawnery")) return;
        sklepConfig.set("categories.spawnery.name", "Spawnery");
        sklepConfig.set("categories.spawnery.icon", "SPAWNER");
        ustawSpawnerWSklepie(0, "PIGLIN", "Spawner: Piglinów", 5000.0);
        ustawSpawnerWSklepie(1, "SHEEP", "Spawner: Owiec", 3000.0);
        ustawSpawnerWSklepie(2, "RABBIT", "Spawner: Królików", 4000.0);
        ustawSpawnerWSklepie(3, "BREEZE", "Spawner: Breeze'ów", 8000.0);
        ustawSpawnerWSklepie(4, "GLOW_SQUID", "Spawner: Świetlistych Kałamarnic", 6000.0);
    }

    /** Itemy Specjalne: unikalne przedmioty z realną, customową logiką w innych modułach. */
    private void dodajSpecjalneJesliBrak() {
        if (!sklepConfig.contains("categories.specjalne")) {
            sklepConfig.set("categories.specjalne.name", "Itemy Specjalne");
            sklepConfig.set("categories.specjalne.icon", "NETHERITE_PICKAXE");
            ustawSpecjalnyItemWSklepie(0, "NETHERITE_PICKAXE", "NISZCZYCIEL", "Niszczyciel", 50000.0, List.of(
                    "Kilof z ultra szybkim kopaniem (Haste X)",
                    "PPM: niszczy 3x3 bloków naraz",
                    "Zawsze dropi właściwy blok (jak Silk Touch)",
                    "Służy tylko do kopania - nic więcej"
            ));
        }
        // Osobny, NIEZALEŻNY guard - na serwerach, gdzie categories.specjalne już istnieje
        // (i blok wyżej się nie wykona), ten wpis i tak samoczynnie dogra się przy starcie.
        if (!sklepConfig.contains("categories.specjalne.items.1")) {
            ustawSpecjalnyItemWSklepie(1, "SNIFFER_EGG", "SNIFFER_JAJKO", "Sniffer Farmera", 40000.0, List.of(
                    "PPM na własnej wyspie stawia stacjonarnego",
                    "Snifferaa, który automatycznie zbiera i",
                    "sadzi dojrzałe uprawy w pobliżu",
                    "Zebrane plony trafiają do najbliższej skrzyni",
                    "(albo na ziemię, jeśli żadnej nie ma w zasięgu)",
                    "Maksymalnie 1 Sniffer na wyspę"
            ));
        }
    }

    private void ustawSpecjalnyItemWSklepie(int slot, String material, String customId, String displayName, double buyPrice, List<String> lore) {
        String path = "categories.specjalne.items." + slot + ".";
        sklepConfig.set(path + "material", material);
        sklepConfig.set(path + "custom-id", customId);
        sklepConfig.set(path + "display-name", displayName);
        sklepConfig.set(path + "lore", lore);
        sklepConfig.set(path + "slot", slot);
        sklepConfig.set(path + "amount", 1);
        sklepConfig.set(path + "buy-price", buyPrice);
        // Celowo bez sell-price - patrz uwaga przy ustawSpawnerWSklepie.
    }

    private void ustawSpawnerWSklepie(int slot, String customId, String displayName, double buyPrice) {
        String path = "categories.spawnery.items." + slot + ".";
        sklepConfig.set(path + "material", "SPAWNER");
        sklepConfig.set(path + "custom-id", customId);
        sklepConfig.set(path + "display-name", displayName);
        sklepConfig.set(path + "slot", slot);
        sklepConfig.set(path + "amount", 1);
        sklepConfig.set(path + "buy-price", buyPrice);
        // Celowo bez sell-price - patrz uwaga przy odczycie w znajdzCeneSprzedazy/onInventoryClick,
        // sprzedaż dopasowuje wyłącznie po Material, więc 5 itemów z tym samym SPAWNER byłoby niejednoznaczne.
    }

    // ==================================================================== GUI ====

    public void otworzSklep(Player player, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        playerCategory.remove(player.getUniqueId());
        playerPage.remove(player.getUniqueId());

        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Sklep Serwerowy", NamedTextColor.GOLD, TextDecoration.BOLD));
        wypelnijTloGwiazdy(gui);

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
                // Połysk na każdej ikonie kategorii - czysto kosmetyczne, "premium" wrażenie w GUI.
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
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
        int totalPages = Math.max(1, (int) Math.ceil((double) (maxSlotFound + 1) / SLOTY_GWIAZDY_PELNA.length));
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;

        Component guiTitle = (totalPages > 1)
                ? Component.text("Sklep: " + catName + " (Str. " + (page + 1) + ")", NamedTextColor.DARK_GREEN, TextDecoration.BOLD)
                : Component.text("Sklep: " + catName, NamedTextColor.DARK_GREEN, TextDecoration.BOLD);

        Inventory gui = Bukkit.createInventory(null, 54, guiTitle);
        wypelnijTloGwiazdy(gui);

        if (itemsSection != null) {
            int pageStartSlot = page * SLOTY_GWIAZDY_PELNA.length;
            int pageEndSlot = pageStartSlot + SLOTY_GWIAZDY_PELNA.length - 1;

            for (String itemKey : itemsSection.getKeys(false)) {
                String path = "categories." + catKey + ".items." + itemKey + ".";
                int slot = sklepConfig.getInt(path + "slot", 0);

                if (slot >= pageStartSlot && slot <= pageEndSlot) {
                    String matName = sklepConfig.getString(path + "material", "STONE");
                    int amount = sklepConfig.getInt(path + "amount", 1);
                    double buyPrice = sklepConfig.getDouble(path + "buy-price", -1);
                    double sellPrice = sklepConfig.getDouble(path + "sell-price", -1);
                    String customDisplayName = sklepConfig.getString(path + "display-name", null);
                    List<String> customLore = sklepConfig.getStringList(path + "lore");

                    Material material = Material.matchMaterial(matName);
                    if (material == null) material = Material.STONE;

                    ItemStack item = new ItemStack(material, Math.min(Math.max(amount, 1), 64));
                    ItemMeta meta = item.getItemMeta();
                    meta.displayName(customDisplayName != null
                            ? Component.text(customDisplayName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                            : Component.text(amount + "x " + material.name(), NamedTextColor.YELLOW, TextDecoration.BOLD));

                    List<Component> lore = new ArrayList<>();
                    for (String linia : customLore) {
                        lore.add(Component.text(linia, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
                    }
                    if (!customLore.isEmpty()) lore.add(Component.empty());
                    if (buyPrice >= 0) lore.add(Component.text("Cena kupna: " + buyPrice + " $", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                    if (sellPrice >= 0) lore.add(Component.text("Cena sprzedaży: " + sellPrice + " $", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
                    lore.add(Component.empty());
                    lore.add(Component.text("LPM - Kup paczkę (" + amount + " szt.)", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                    lore.add(Component.text("PPM - Sprzedaj paczkę (" + amount + " szt.)", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

                    meta.lore(lore);
                    item.setItemMeta(meta);

                    gui.setItem(SLOTY_GWIAZDY_PELNA[slot - pageStartSlot], item);
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

    /** Odwrotność SLOTY_GWIAZDY_PELNA[localIndex] - który lokalny indeks (0-15) odpowiada danemu slotowi GUI, albo -1. */
    private int indeksWGwiezdzie(int guiSlot) {
        for (int idx = 0; idx < SLOTY_GWIAZDY_PELNA.length; idx++) {
            if (SLOTY_GWIAZDY_PELNA[idx] == guiSlot) return idx;
        }
        return -1;
    }

    /**
     * Tło gwiazdy - wspólne dla obu ekranów (kategorie w otworzSklep, itemy w
     * otworzKategorieStrona): szare szkło wszędzie, żółty akcent na SLOTY_GWIAZDY_PELNA
     * (te same 16 pozycji, którymi rysowane są realne ikony) - dzięki temu kształt
     * gwiazdy jest czytelny nawet tam, gdzie akurat nie ma kategorii/itemu (np. mniej
     * niż 16 itemów w danej kategorii).
     */
    private void wypelnijTloGwiazdy(Inventory gui) {
        ItemStack szare = pane(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) gui.setItem(i, szare);

        ItemStack akcent = pane(Material.YELLOW_STAINED_GLASS_PANE);
        for (int slot : SLOTY_GWIAZDY_PELNA) gui.setItem(slot, akcent);
    }

    private ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
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

        // Sloty dolnego inwentarza (ekwipunek gracza) są numerowane od zera tak samo
        // jak sloty górnego GUI sklepu - bez tego sprawdzenia kliknięcie np. w slot 1
        // swojego ekwipunku było traktowane tak samo jak kliknięcie w slot 1 sklepu,
        // więc "kupowało"/otwierało kategorię wg tego, co akurat tam stało w konfiguracji.
        if (!event.getView().getTopInventory().equals(event.getClickedInventory())) return;

        // Zabezpieczenie tła
        if (clickedItem.getType() == Material.GRAY_STAINED_GLASS_PANE || clickedItem.getType() == Material.YELLOW_STAINED_GLASS_PANE) {
            event.setCancelled(true);
            return;
        }

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

            if (slotKlikniecia >= 45) return; // Zabezpieczenie przed błędem z dolnym paskiem

            int localIndex = indeksWGwiezdzie(slotKlikniecia);
            if (localIndex == -1) return; // kliknięcie w tło gwiazdy poza jej 16 pozycjami

            int absoluteTargetSlot = (currentPage * SLOTY_GWIAZDY_PELNA.length) + localIndex;
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
                            player.getInventory().addItem(stworzKupionyItem(cfgMaterial, amount, path));
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