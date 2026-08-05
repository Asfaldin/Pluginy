package elo.mainplugins.quests;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.ToolsService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * /quest w całości - menu kategorii w kształcie gwiazdy, wszystkie kategorie (włącznie
 * z Główną Ścieżką na środku) to ten sam mechanizm "przynieś przedmiot, oddaj, dostań
 * nagrodę". Zero zależności od zewnętrznych pluginów (bez NPC/Citizens) - to świadomy
 * powrót do prostszego modelu, patrz komentarz w pom.xml tego modułu.
 *
 * Środek gwiazdy = Główna Ścieżka (KATEGORIA_GLOWNA_SCIEZKA) - jedyna kategoria, w
 * której zadania odblokowują się PO KOLEI (patrz ustalStan) i renderują się jako
 * wężyk (SLOTY_WEZYK), a nie siatka. Ramiona dookoła = zwykłe zadania poboczne w
 * dowolnej kolejności (siatka, slotySrodkowe), tak samo jak "Questy Specjalne" -
 * to po prostu kolejne ramię z trudniejszą/rzadszą treścią, bez specjalnej logiki.
 */
public class QuestManager implements Listener {

    private record Quest(int id, String tytul, List<String> opis, Material wymaganyMaterial, int wymaganaIlosc, ItemStack nagroda, String nazwaNagrody) {}

    private enum StanQuestu { ZABLOKOWANY, DOSTEPNY, UKONCZONY }

    private final Plugin plugin;
    private final File plikPostepow;
    private final FileConfiguration configPostepow;

    private final Map<UUID, Map<String, Set<Integer>>> postepyGraczy = new HashMap<>();
    private final Map<String, List<Quest>> questyKategorii = new LinkedHashMap<>();

    // Zmienna zapamiętująca czy gracz wszedł z poziomu /menu
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    // Definiujemy 35 slotów na środku (7 kolumn x 5 rzędów) - kategorie zwykłe (dowolna kolejność).
    private final int[] slotySrodkowe = {
            1, 2, 3, 4, 5, 6, 7,
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    // Te same 35 pozycji, ale w kolejności "wężyka" - dla Głównej Ścieżki, żeby zadanie
    // 1->2->3... wizualnie ciągnęło się zygzakiem, a nie skakało po siatce.
    private static final int[] SLOTY_WEZYK = {
            1, 2, 3, 4, 5, 6, 7,
            16, 15, 14, 13, 12, 11, 10,
            19, 20, 21, 22, 23, 24, 25,
            34, 33, 32, 31, 30, 29, 28,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final int SLOT_CENTRUM_GWIAZDY = 22; // Główna Ścieżka - środek gwiazdy
    private static final int SLOT_POWROT = 45; // wolny róg, poza wszystkimi ramionami gwiazdy
    private static final String KATEGORIA_GLOWNA_SCIEZKA = "Główna Ścieżka";

    // 8 ramion od SLOT_CENTRUM_GWIAZDY na zewnątrz (N,S,E,W,NE,NW,SE,SW) - różnej długości,
    // ograniczone geometrią 54-slotowego (6x9) GUI: pion mieści tylko 2 kroki w każdą stronę
    // od centralnego rzędu bez wchodzenia na SLOT_POWROT, poziom/przekątne w dół mieszczą 3-4.
    // Kolejność w tablicy = kolejność przypisywania kategorii z KATEGORIE_GWIAZDY niżej.
    private static final int[] SLOTY_GWIAZDY = {
            13, 4,           // N
            31, 40, 49,      // S
            23, 24, 25, 26,  // E
            21, 20, 19, 18,  // W
            14, 6,           // NE
            12, 2,           // NW
            32, 42, 52,      // SE
            30, 38, 46       // SW
    };

    public QuestManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikPostepow = new File(plugin.getDataFolder(), "quests.yml");
        if (!plikPostepow.exists()) {
            plikPostepow.getParentFile().mkdirs();
            try { plikPostepow.createNewFile(); } catch (IOException ignored) {}
        }
        this.configPostepow = YamlConfiguration.loadConfiguration(plikPostepow);
        zaladujQuesty();
        wczytajPostep();
    }

    private void zaladujQuesty() {
        // GŁÓWNA ŚCIEŻKA - centrum gwiazdy, 40 zadań PO KOLEI (patrz ustalStan), od
        // pierwszego dnia na wyspie aż po pokonanie Enderdragona. Quest 1 to jedyny
        // z całego sklepu/questów, którego nagroda NIE jest zwykłym ItemStackiem z
        // tej listy - "nagroda" tutaj to tylko placeholder do wyświetlenia w GUI,
        // realne wręczenie (prawdziwy, działający ewoluujący kilof z mainplugins-tools
        // przez ToolsService) dzieje się w wreczNagrode(). Treść/balans do dograni
        // w przyszłości - to pierwsza, kompletna wersja całej ścieżki.
        questyKategorii.put(KATEGORIA_GLOWNA_SCIEZKA, List.of(
                new Quest(1, "Witaj na Wyspie", List.of("Zasadź swoją pierwszą sadzonkę - to Twój nowy dom."),
                        Material.OAK_SAPLING, 1, new ItemStack(Material.WOODEN_PICKAXE, 1), "1x Ewoluujący Kilof"),
                new Quest(2, "Pierwsze Cięcie", List.of("Zbierz drewno, by postawić bazę na wyspie."),
                        Material.OAK_LOG, 16, new ItemStack(Material.CHEST, 1), "1x Skrzynia"),
                new Quest(3, "Fundamenty", List.of("Nakop kamienia pod pierwsze budowle."),
                        Material.COBBLESTONE, 32, new ItemStack(Material.FURNACE, 1), "1x Piec"),
                new Quest(4, "Rolnik Wyspy", List.of("Załóż pole i zbierz pierwsze plony."),
                        Material.WHEAT, 16, new ItemStack(Material.BREAD, 8), "8x Chleb"),
                new Quest(5, "Do Kopalni", List.of("Wykop pierwszą rudę żelaza."),
                        Material.RAW_IRON, 16, new ItemStack(Material.IRON_PICKAXE, 1), "1x Żelazny Kilof"),
                new Quest(6, "Skarbiec", List.of("Zbierz złoto na dalszą rozbudowę."),
                        Material.GOLD_INGOT, 10, new ItemStack(Material.GOLDEN_APPLE, 2), "2x Złote Jabłko"),
                new Quest(7, "Odkrywca", List.of("Skrafcuj kompas i wyrusz na zwiedzanie."),
                        Material.COMPASS, 1, new ItemStack(Material.ENDER_PEARL, 4), "4x Perła Endermana"),
                new Quest(8, "Pierwszy Pancerz", List.of("Wykop diamenty na swój pierwszy porządny sprzęt."),
                        Material.DIAMOND, 8, new ItemStack(Material.DIAMOND_CHESTPLATE, 1), "1x Diamentowy Napierśnik"),
                new Quest(9, "Hodowca", List.of("Zasiej pole pod przyszłą fermę zwierząt."),
                        Material.WHEAT_SEEDS, 16, new ItemStack(Material.EGG, 4), "4x Jajko"),
                new Quest(10, "Wędkarz", List.of("Złów pierwsze ryby w okolicznych wodach.")   ,
                        Material.COD, 16, new ItemStack(Material.FISHING_ROD, 1), "1x Wędka"),
                new Quest(11, "Kowal", List.of("Przetop żelazo pod pierwsze kowadło."),
                        Material.IRON_INGOT, 8, new ItemStack(Material.ANVIL, 1), "1x Kowadło"),
                new Quest(12, "Alchemik", List.of("Zbierz szklane butelki pod stół alchemika."),
                        Material.GLASS_BOTTLE, 8, new ItemStack(Material.BREWING_STAND, 1), "1x Stół Alchemika"),
                new Quest(13, "Zbieracz Szmaragdów", List.of("Zgromadź szmaragdy - cenną walutę wyspy."),
                        Material.EMERALD, 16, new ItemStack(Material.EMERALD_BLOCK, 1), "1x Blok Szmaragdu"),
                new Quest(14, "Redstone Mistrz", List.of("Wykop redstone pod pierwsze mechanizmy."),
                        Material.REDSTONE, 32, new ItemStack(Material.PISTON, 4), "4x Tłok"),
                new Quest(15, "Budowniczy", List.of("Wypal cegły na porządniejsze budowle."),
                        Material.BRICK, 32, new ItemStack(Material.BRICKS, 16), "16x Cegły"),
                new Quest(16, "Rozbudowa Wyspy", List.of("Zgromadź diamenty na powiększenie wyspy."),
                        Material.DIAMOND, 16, new ItemStack(Material.DIAMOND_BLOCK, 1), "1x Blok Diamentu"),
                new Quest(17, "Górnik Głębin", List.of("Wykop miedź w głębszych warstwach wyspy."),
                        Material.RAW_COPPER, 32, new ItemStack(Material.COPPER_INGOT, 8), "8x Sztabka Miedzi"),
                new Quest(18, "Owczarz", List.of("Zbierz wełnę ze swojej hodowli owiec."),
                        Material.WHITE_WOOL, 32, new ItemStack(Material.SHEARS, 1), "1x Nożyce"),
                new Quest(19, "Piekarz", List.of("Upiecz coś więcej niż zwykły chleb.")   ,
                        Material.WHEAT, 32, new ItemStack(Material.PUMPKIN_PIE, 4), "4x Placek Dyniowy"),
                new Quest(20, "Kopacz Lapisu", List.of("Zbierz lapis lazuli pod zaklęcia."),
                        Material.LAPIS_LAZULI, 32, new ItemStack(Material.ENCHANTING_TABLE, 1), "1x Stół Zaklęć"),
                new Quest(21, "Poszukiwacz Złota", List.of("Zgromadź spory zapas złota."),
                        Material.GOLD_INGOT, 24, new ItemStack(Material.GOLDEN_APPLE, 4), "4x Złote Jabłko"),
                new Quest(22, "Łowca Pająków", List.of("Zbierz sznurek po nocnych polowaniach."),
                        Material.STRING, 32, new ItemStack(Material.BOW, 1), "1x Łuk"),
                new Quest(23, "Kolekcjoner Kości", List.of("Zbierz kości ze szkieletów."),
                        Material.BONE, 32, new ItemStack(Material.BONE_MEAL, 16), "16x Mączka Kostna"),
                new Quest(24, "Prochowy Handlarz", List.of("Zbierz proch strzelniczy z creeperów."),
                        Material.GUNPOWDER, 32, new ItemStack(Material.TNT, 4), "4x TNT"),
                new Quest(25, "Brama do Netheru", List.of("Zbierz netherrack na budowę portalu."),
                        Material.NETHERRACK, 16, new ItemStack(Material.OBSIDIAN, 4), "4x Obsydian"),
                new Quest(26, "Łowca Blaze'ów", List.of("Zapoluj na blaze w Netherowej twierdzy."),
                        Material.BLAZE_ROD, 8, new ItemStack(Material.BLAZE_POWDER, 16), "16x Proch Blaze'a"),
                new Quest(27, "Duszowy Piach", List.of("Zbierz duszowy piasek z Netheru."),
                        Material.SOUL_SAND, 32, new ItemStack(Material.SOUL_LANTERN, 1), "1x Duszowa Latarnia"),
                new Quest(28, "Kwarcowy Górnik", List.of("Wydobądź kwarc netherowy."),
                        Material.QUARTZ, 32, new ItemStack(Material.QUARTZ_BLOCK, 8), "8x Blok Kwarcu"),
                new Quest(29, "Pogromca Ghastów", List.of("Zapoluj na ghasty i zbierz ich łzy."),
                        Material.GHAST_TEAR, 4, new ItemStack(Material.FIRE_CHARGE, 8), "8x Ognista Kula"),
                new Quest(30, "Netherytowy Traker", List.of("Znajdź złom netherytu w głębi Netheru."),
                        Material.NETHERITE_SCRAP, 4, new ItemStack(Material.GOLD_INGOT, 4), "4x Sztabka Złota"),
                new Quest(31, "Kowal Netherytu", List.of("Wykuj pierwszą sztabkę netherytu."),
                        Material.NETHERITE_INGOT, 1, new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1), "1x Szablon Kowalski"),
                new Quest(32, "Wojownik Netheru", List.of("Pokonaj wither skeletony i zbierz ich czaszki."),
                        Material.WITHER_SKELETON_SKULL, 4, new ItemStack(Material.TOTEM_OF_UNDYING, 1), "1x Totem Nieśmiertelności"),
                new Quest(33, "Brama do Endu", List.of("Zgromadź perły endermana na oczy endera."),
                        Material.ENDER_PEARL, 12, new ItemStack(Material.ENDER_EYE, 4), "4x Oko Endera"),
                new Quest(34, "Purpurowy Architekt", List.of("Zbierz purpurowe bloki z miast Endu."),
                        Material.PURPUR_BLOCK, 32, new ItemStack(Material.END_ROD, 8), "8x Pręt Endu"),
                new Quest(35, "Owoc Chorusu", List.of("Zbierz owoce chorusu w Endzie."),
                        Material.CHORUS_FRUIT, 32, new ItemStack(Material.POPPED_CHORUS_FRUIT, 16), "16x Prażony Owoc Chorusu"),
                new Quest(36, "Łowca Shulkerów", List.of("Pokonaj shulkery w miastach Endu."),
                        Material.SHULKER_SHELL, 4, new ItemStack(Material.SHULKER_BOX, 1), "1x Shulker Box"),
                new Quest(37, "Smoczy Oddech", List.of("Zbierz oddech smoka podczas walki z Enderdragonem."),
                        Material.DRAGON_BREATH, 8, new ItemStack(Material.NETHER_STAR, 1), "1x Gwiazda Netheru"),
                new Quest(38, "Skrzydła Wolności", List.of("Zbierz błony fantomów na coś specjalnego."),
                        Material.PHANTOM_MEMBRANE, 4, new ItemStack(Material.ELYTRA, 1), "1x Elytra"),
                new Quest(39, "Mistrz Farmera", List.of("Udowodnij, że Twoja farma stoi na najwyższym poziomie."),
                        Material.MELON_SLICE, 64, new ItemStack(Material.GOLDEN_HOE, 1), "1x Złota Motyka"),
                new Quest(40, "Mistrz Wyspy", List.of("Oddaj zdobytą Gwiazdę Netheru i ukończ ścieżkę!"),
                        Material.NETHER_STAR, 1, new ItemStack(Material.BEACON, 1), "1x Beacon")
        ));

        // GÓRNICTWO - 40 questów (pokazuje paginację)
        List<Quest> gornictwo = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            gornictwo.add(new Quest(i, "Górnik " + i, List.of(), Material.COBBLESTONE, 64, new ItemStack(Material.DIAMOND, 1), "1x Diament"));
        }
        questyKategorii.put("Górnictwo", gornictwo);

        // HODOWLA
        questyKategorii.put("Hodowla", List.of(
                new Quest(1, "Zbiory 1", List.of(), Material.CARROT, 32, new ItemStack(Material.EMERALD, 5), "5x Szmaragd"),
                new Quest(2, "Zbiory 2", List.of(), Material.WHEAT, 64, new ItemStack(Material.GOLD_INGOT, 10), "10x Złoto"),
                new Quest(3, "Zbiory 3", List.of(), Material.POTATO, 64, new ItemStack(Material.IRON_INGOT, 32), "32x Żelazo")
        ));

        // ŁOWCA
        questyKategorii.put("Łowca", List.of(
                new Quest(1, "Początkujący", List.of(), Material.ROTTEN_FLESH, 32, new ItemStack(Material.COOKED_BEEF, 16), "16x Pieczona Wołowina"),
                new Quest(2, "Strzelec", List.of(), Material.BONE, 16, new ItemStack(Material.BOW, 1), "1x Łuk"),
                new Quest(3, "Nocny Marek", List.of(), Material.STRING, 10, new ItemStack(Material.EXPERIENCE_BOTTLE, 16), "16x Butelka EXP")
        ));

        // QUESTY SPECJALNE (dawniej "Mistrz") - trudne, kosztowne zadania dla weteranów.
        questyKategorii.put("Questy Specjalne", List.of(
                new Quest(1, "Górski Kolos", List.of("Dla prawdziwych weteranów kopalni."),
                        Material.DIAMOND, 64, new ItemStack(Material.NETHERITE_INGOT, 1), "1x Sztabka Netherytu"),
                new Quest(2, "Wojownik Otchłani", List.of("Poluj na eliksir mocy w Netherze."),
                        Material.WITHER_SKELETON_SKULL, 4, new ItemStack(Material.TOTEM_OF_UNDYING, 1), "1x Totem Nieśmiertelności"),
                new Quest(3, "Skarb Smoka", List.of("Pokonaj Smoka Endera."),
                        Material.DRAGON_BREATH, 16, new ItemStack(Material.NETHER_STAR, 1), "1x Gwiazda Netheru")
        ));

        // Inicjalizacja pustych list dla kategorii narzędziowych, aby nie rzucały błędem.
        // "Mistrz Siekiery/Motyki/Łopaty" celowo usunięte z listy - to były czyste,
        // nierozróżnialne puste duplikaty (patrz KATEGORIE_GWIAZDY: gwiazda w 54-slotowym
        // GUI ma twardy geometryczny limit ~23 ramion bez zachodzenia na siebie/przycisk
        // powrotu, więc trzeba było skonsolidować najbardziej redundantne puste kategorie).
        questyKategorii.put("Mistrz Kilofa", new ArrayList<>());
        questyKategorii.put("Mistrz Miecza", new ArrayList<>());
    }

    /** Ikona/nazwa/opis kategorii z siatki gwiazdy - kolejność MUSI się zgadzać z SLOTY_GWIAZDY. */
    private record KategoriaGwiazdy(Material ikona, String nazwa, String opis) {}

    private static final List<KategoriaGwiazdy> KATEGORIE_GWIAZDY = List.of(
            // N
            new KategoriaGwiazdy(Material.IRON_PICKAXE, "Górnictwo", "Zadania w kopalni"),
            new KategoriaGwiazdy(Material.WHEAT, "Hodowla", "Zadania rolnicze"),
            // S
            new KategoriaGwiazdy(Material.BOW, "Łowca", "Zadania z potworami"),
            new KategoriaGwiazdy(Material.OAK_LOG, "Drwal", "Zadania z drewnem"),
            new KategoriaGwiazdy(Material.FISHING_ROD, "Rybak", "Zadania wędkarskie"),
            // E
            new KategoriaGwiazdy(Material.BREWING_STAND, "Alchemik", "Warzenie mikstur"),
            new KategoriaGwiazdy(Material.ANVIL, "Kowal", "Tworzenie narzędzi"),
            new KategoriaGwiazdy(Material.COOKED_BEEF, "Kucharz", "Zadania kulinarne"),
            new KategoriaGwiazdy(Material.BRICKS, "Budowniczy", "Budowa wyspy"),
            // W
            new KategoriaGwiazdy(Material.ENCHANTING_TABLE, "Mag", "Zaklęcia"),
            new KategoriaGwiazdy(Material.COMPASS, "Odkrywca", "Eksploracja mapy"),
            new KategoriaGwiazdy(Material.PORKCHOP, "Rzeźnik", "Zdobywanie mięsa"),
            new KategoriaGwiazdy(Material.OAK_SAPLING, "Ogrodnik", "Sadzenie drzew"),
            // NE
            new KategoriaGwiazdy(Material.DIAMOND, "Jubiler", "Cenne kruszce"),
            new KategoriaGwiazdy(Material.GOLD_NUGGET, "Złodziej", "Kradzież (Zadania)"),
            // NW
            new KategoriaGwiazdy(Material.DIAMOND_SWORD, "Wojownik", "Walka PvP/PvE"),
            new KategoriaGwiazdy(Material.EMERALD, "Handlarz", "Wymiana handlowa"),
            // SE
            new KategoriaGwiazdy(Material.REDSTONE, "Inżynier", "Mechanizmy"),
            new KategoriaGwiazdy(Material.ZOMBIE_HEAD, "Zabójca", "Eliminacje"),
            new KategoriaGwiazdy(Material.BONE_MEAL, "Zbieracz", "Zbieranie surowców"),
            // SW
            new KategoriaGwiazdy(Material.NETHER_STAR, "Questy Specjalne", "Trudne wyzwania dla weteranów"),
            new KategoriaGwiazdy(Material.DIAMOND_PICKAXE, "Mistrz Kilofa", "Zadania dla kilofa"),
            new KategoriaGwiazdy(Material.DIAMOND_SWORD, "Mistrz Miecza", "Zadania dla miecza")
    );

    // ---- Persystencja postępu (per gracz, per kategoria) ----

    private void wczytajPostep() {
        ConfigurationSection graczeSekcja = configPostepow.getConfigurationSection("gracze");
        if (graczeSekcja == null) return;

        for (String uuidStr : graczeSekcja.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); } catch (IllegalArgumentException e) { continue; }

            ConfigurationSection kategorieSekcja = graczeSekcja.getConfigurationSection(uuidStr);
            if (kategorieSekcja == null) continue;

            Map<String, Set<Integer>> mapaKategorii = new HashMap<>();
            for (String kategoria : kategorieSekcja.getKeys(false)) {
                mapaKategorii.put(kategoria, new HashSet<>(kategorieSekcja.getIntegerList(kategoria)));
            }
            postepyGraczy.put(uuid, mapaKategorii);
        }
    }

    private void zapiszPostep() {
        configPostepow.set("gracze", null); // czyścimy stare wpisy, żeby nie zostawały śmieci po zmianach nazw kategorii
        for (Map.Entry<UUID, Map<String, Set<Integer>>> gracz : postepyGraczy.entrySet()) {
            for (Map.Entry<String, Set<Integer>> kategoria : gracz.getValue().entrySet()) {
                configPostepow.set("gracze." + gracz.getKey() + "." + kategoria.getKey(), new ArrayList<>(kategoria.getValue()));
            }
        }
        try {
            configPostepow.save(plikPostepow);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać quests.yml: " + e.getMessage());
        }
    }

    private Set<Integer> postepyDlaKategorii(Player player, String kategoria) {
        return postepyGraczy.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(kategoria, k -> new HashSet<>());
    }

    /**
     * Stan pojedynczego zadania. Dla zwykłych kategorii to tylko DOSTEPNY/UKONCZONY -
     * ZABLOKOWANY istnieje wyłącznie w Głównej Ścieżce, gdzie zadanie N+1 czeka, aż
     * gracz ukończy zadanie N (index-1 na tej samej liście).
     */
    private StanQuestu ustalStan(String kategoria, List<Quest> questy, int indexGlobalny, Set<Integer> postepy) {
        Quest q = questy.get(indexGlobalny);
        if (postepy.contains(q.id())) return StanQuestu.UKONCZONY;
        if (!kategoria.equals(KATEGORIA_GLOWNA_SCIEZKA) || indexGlobalny == 0) return StanQuestu.DOSTEPNY;

        Quest poprzedni = questy.get(indexGlobalny - 1);
        return postepy.contains(poprzedni.id()) ? StanQuestu.DOSTEPNY : StanQuestu.ZABLOKOWANY;
    }

    private int[] slotyDlaKategorii(String kategoria) {
        return kategoria.equals(KATEGORIA_GLOWNA_SCIEZKA) ? SLOTY_WEZYK : slotySrodkowe;
    }

    // ---- GUI ----

    public void otworzMenuQuestow(Player player, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Kategorie Zadań", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);

        // Główna Ścieżka na SAMYM ŚRODKU gwiazdy - to punkt startowy dla nowych graczy,
        // reszta kategorii promieniście dookoła (patrz SLOTY_GWIAZDY/KATEGORIE_GWIAZDY).
        ItemStack glownaSciezkaIkona = stworzIkoneKategorii(Material.KNOWLEDGE_BOOK, "Główna Ścieżka", "Zacznij tutaj - zadania po kolei!");
        ItemMeta metaGlowna = glownaSciezkaIkona.getItemMeta();
        metaGlowna.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        metaGlowna.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        // Wskaźnik "masz dostępny quest" - dodatkowa linijka lore, żeby było widać
        // z poziomu samego menu kategorii, bez wchodzenia do środka (patrz też onJoin).
        if (maDostepnyQuestGlownejSciezki(player)) {
            List<Component> loreGlowna = new ArrayList<>(metaGlowna.lore());
            loreGlowna.add(Component.text("🔔 Nowy quest dostępny!", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            metaGlowna.lore(loreGlowna);
        }
        glownaSciezkaIkona.setItemMeta(metaGlowna);
        gui.setItem(SLOT_CENTRUM_GWIAZDY, glownaSciezkaIkona);

        for (int i = 0; i < SLOTY_GWIAZDY.length && i < KATEGORIE_GWIAZDY.size(); i++) {
            KategoriaGwiazdy k = KATEGORIE_GWIAZDY.get(i);
            gui.setItem(SLOTY_GWIAZDY[i], stworzIkoneKategorii(k.ikona(), k.nazwa(), k.opis()));
        }

        if (zMenu) {
            gui.setItem(SLOT_POWROT, stworzPrzycisk(Material.NETHER_STAR, "« Wróć do Menu głównego", NamedTextColor.RED));
        } else {
            gui.setItem(SLOT_POWROT, stworzPrzycisk(Material.BARRIER, "Zamknij Menu", NamedTextColor.RED));
        }

        player.openInventory(gui);
    }

    public void otworzKategorie(Player player, String nazwaKategorii, int strona) {
        List<Quest> questy = questyKategorii.getOrDefault(nazwaKategorii, new ArrayList<>());
        int[] sloty = slotyDlaKategorii(nazwaKategorii);

        String tytulMenu = "Strona " + (strona + 1) + " | " + nazwaKategorii;
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(tytulMenu, NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);

        Set<Integer> postepy = postepyDlaKategorii(player, nazwaKategorii);

        int startIndex = strona * 35;
        for (int i = 0; i < 35; i++) {
            if (startIndex + i < questy.size()) {
                StanQuestu stan = ustalStan(nazwaKategorii, questy, startIndex + i, postepy);
                gui.setItem(sloty[i], stworzIkoneQuesta(questy.get(startIndex + i), stan));
            }
        }

        gui.setItem(49, stworzPrzycisk(Material.DARK_OAK_DOOR, "Powrót do Kategorii", NamedTextColor.GOLD));
        if (strona > 0) gui.setItem(45, stworzPrzycisk(Material.ARROW, "Poprzednia Strona", NamedTextColor.YELLOW));
        if (startIndex + 35 < questy.size()) gui.setItem(53, stworzPrzycisk(Material.ARROW, "Następna Strona", NamedTextColor.YELLOW));

        player.openInventory(gui);
    }

    private void wypelnijTlo(Inventory gui) {
        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        for (int i = 0; i < 54; i++) gui.setItem(i, tlo);
    }

    private ItemStack stworzIkoneKategorii(Material material, String nazwa, String opis) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.lore(List.of(Component.text(opis, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack stworzPrzycisk(Material mat, String nazwa, NamedTextColor kolor) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, kolor, TextDecoration.BOLD));
        item.setItemMeta(meta);
        return item;
    }

    /** Czerwony barwnik = dostępne, zielony (lime) = ukończone, szary = zablokowane (tylko Główna Ścieżka). */
    private ItemStack stworzIkoneQuesta(Quest q, StanQuestu stan) {
        Material material = switch (stan) {
            case UKONCZONY -> Material.LIME_DYE;
            case ZABLOKOWANY -> Material.GRAY_DYE;
            case DOSTEPNY -> Material.RED_DYE;
        };
        NamedTextColor kolorTytulu = switch (stan) {
            case UKONCZONY -> NamedTextColor.GREEN;
            case ZABLOKOWANY -> NamedTextColor.DARK_GRAY;
            case DOSTEPNY -> NamedTextColor.RED;
        };

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(stan == StanQuestu.ZABLOKOWANY ? "??? (Zablokowane)" : q.tytul(), kolorTytulu, TextDecoration.BOLD));

        List<Component> lore = new ArrayList<>();
        if (stan == StanQuestu.ZABLOKOWANY) {
            // Nie zdradzamy treści zablokowanego zadania - zero spoilerów dalszej ścieżki.
            lore.add(Component.text("Ukończ poprzednie zadanie ścieżki, aby odblokować.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.empty());
            for (String linia : q.opis()) {
                lore.add(Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.text("Wymaga: " + q.wymaganaIlosc() + "x " + q.wymaganyMaterial().name(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(stan == StanQuestu.UKONCZONY
                    ? Component.text("Nagroda: " + q.nazwaNagrody(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                    : Component.text("Nagroda: ???", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(stan == StanQuestu.UKONCZONY
                    ? Component.text("✔ UKOŃCZONE", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
                    : Component.text("❌ KLIKNIJ, ABY ZDAĆ", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.equals("Kategorie Zadań") && !title.startsWith("Strona ")) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        if (title.equals("Kategorie Zadań")) {
            if (slot == SLOT_POWROT) {
                player.closeInventory();
                // Sprawdzamy czy gracz wszedł z menu, jeśli tak - cofamy go tam
                if (otwartoZMenu.getOrDefault(player.getUniqueId(), false)) {
                    player.performCommand("menu");
                }
            }
            else if (slot == SLOT_CENTRUM_GWIAZDY) {
                otworzKategorie(player, KATEGORIA_GLOWNA_SCIEZKA, 0);
            }
            else if (event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.GRAY_STAINED_GLASS_PANE) {
                String kategoria = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getCurrentItem().getItemMeta().displayName());
                otworzKategorie(player, kategoria, 0);
            }
        } else if (title.startsWith("Strona ")) {
            String[] parts = title.split("\\|");
            if (parts.length < 2) return;

            int strona = Integer.parseInt(parts[0].replace("Strona ", "").trim()) - 1;
            String kategoria = parts[1].trim();

            if (slot == 49) {
                // Główna Ścieżka i boczne kategorie mają wspólny "cofnij" - zawsze do gwiazdy głównej.
                otworzMenuQuestow(player, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
            }
            else if (slot == 53 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona + 1);
            else if (slot == 45 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona - 1);
            else {
                int[] sloty = slotyDlaKategorii(kategoria);
                for (int i = 0; i < sloty.length; i++) {
                    if (slot == sloty[i]) {
                        List<Quest> questy = questyKategorii.get(kategoria);
                        int questIndex = (strona * 35) + i;
                        if (questy != null && questIndex < questy.size()) {
                            zrealizujQuest(player, kategoria, questy, questIndex, strona);
                        }
                        break;
                    }
                }
            }
        }
    }

    private void zrealizujQuest(Player player, String kategoria, List<Quest> questy, int questIndex, int strona) {
        Quest q = questy.get(questIndex);
        Set<Integer> postepy = postepyDlaKategorii(player, kategoria);

        StanQuestu stan = ustalStan(kategoria, questy, questIndex, postepy);
        if (stan == StanQuestu.ZABLOKOWANY) {
            player.sendMessage(Component.text("Najpierw ukończ poprzednie zadanie tej ścieżki!", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        if (stan == StanQuestu.UKONCZONY) {
            player.sendMessage(Component.text("Zrobiłeś już to zadanie!", NamedTextColor.RED));
            return;
        }

        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(Component.text("Twój ekwipunek jest pełny! Zrób miejsce, aby odebrać nagrodę.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (player.getInventory().containsAtLeast(new ItemStack(q.wymaganyMaterial()), q.wymaganaIlosc())) {
            player.getInventory().removeItem(new ItemStack(q.wymaganyMaterial(), q.wymaganaIlosc()));
            postepy.add(q.id());
            zapiszPostep();

            wreczNagrode(player, kategoria, q);

            player.sendMessage(Component.text("Ukończyłeś zadanie: ", NamedTextColor.GREEN)
                    .append(Component.text(q.tytul(), NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text("! Otrzymałeś: ", NamedTextColor.GREEN))
                    .append(Component.text(q.nazwaNagrody(), NamedTextColor.AQUA)));

            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            otworzKategorie(player, kategoria, strona);
        } else {
            player.sendMessage(Component.text("Nie masz wymaganych przedmiotów!", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        }
    }

    /**
     * Wręcza nagrodę za quest. Jedyny wyjątek od "zwykłego ItemStacka z configu listy" -
     * quest 1 Głównej Ścieżki ("Witaj na Wyspie"), gdzie zamiast placeholdera z
     * zaladujQuesty() gracz dostaje PRAWDZIWY, działający ewoluujący kilof z
     * mainplugins-tools (patrz ToolsService/CoreAPI - ten sam mechanizm poziomowania,
     * ochrony przed wyrzuceniem itd. co reszta narzędzi). Jeśli mainplugins-tools nie
     * jest akurat wgrany, spadamy z powrotem na placeholder, żeby gracz nie stracił
     * przedmiotów za quest bez żadnej nagrody.
     */
    private void wreczNagrode(Player player, String kategoria, Quest q) {
        if (kategoria.equals(KATEGORIA_GLOWNA_SCIEZKA) && q.id() == 1) {
            ToolsService tools = CoreAPI.getToolsService();
            if (tools != null) {
                tools.dajEwoluujacyKilof(player);
                return;
            }
        }
        player.getInventory().addItem(q.nagroda());
    }

    /**
     * Czy gracz ma jeszcze niedokończone zadanie w Głównej Ścieżce, które jest już
     * ODBLOKOWANE (czyli realnie "czeka do zrobienia"). Ponieważ zadania odblokowują się
     * ściśle po kolei (patrz ustalStan), postęp gracza jest zawsze "prefiksem" listy -
     * więc to po prostu "czy ukończył wszystkie 40" - bez potrzeby liczenia stanu
     * pojedynczo dla każdego zadania.
     */
    private boolean maDostepnyQuestGlownejSciezki(Player player) {
        List<Quest> questy = questyKategorii.getOrDefault(KATEGORIA_GLOWNA_SCIEZKA, List.of());
        if (questy.isEmpty()) return false;
        int ukonczone = postepyDlaKategorii(player, KATEGORIA_GLOWNA_SCIEZKA).size();
        return ukonczone < questy.size();
    }

    /** Przypomnienie na ekranie (bossbar, znika samo po chwili) - nie wychodzi poza ekran jak hologram w świecie. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!maDostepnyQuestGlownejSciezki(player)) return;

        BossBar pasek = BossBar.bossBar(
                Component.text("📜 Masz dostępny quest w Głównej Ścieżce! Wpisz /quest", NamedTextColor.GOLD, TextDecoration.BOLD),
                1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        player.showBossBar(pasek);
        Bukkit.getScheduler().runTaskLater(plugin, () -> player.hideBossBar(pasek), 20L * 6);
    }
}