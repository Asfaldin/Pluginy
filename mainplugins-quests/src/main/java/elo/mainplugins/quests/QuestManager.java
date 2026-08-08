package elo.mainplugins.quests;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.ToolsService;
import elo.mainplugins.core.util.CustomItemKeys;
import org.bukkit.persistence.PersistentDataType;
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

    /**
     * PRZEDMIOT - klasyczne "przynieś N sztuk materiału (jednego lub kilku naraz), zostaje
     * zabrane" (domyślne, większość questów).
     * DARMOWY - brak wymogu, kliknięcie od razu zdaje quest (np. "sprawdź spawn").
     * MONETY - zamiast przedmiotów, prog to koszt w monetach (EconomyService).
     * NARZEDZIE - jak PRZEDMIOT, ale NIE zabiera przedmiotu po zdaniu. Wyłącznie do
     * questów "awansuj narzędzie na tier X" - ewoluujące narzędzia z mainplugins-tools
     * zmieniają realny Material przy awansie tieru (LevelableToolsManager.pobierzMaterial),
     * więc containsAtLeast(DIAMOND_PICKAXE, 1) wystarcza do weryfikacji tieru. Zwykłe
     * removeItem() by tu ZABRAŁO graczowi jego jedyny, prawdziwy (nie placeholder)
     * ewoluujący kilof/siekierę/miecz jako "zapłatę" za quest - stąd osobny typ.
     * POZIOM_KILOFA - prog to minimalny poziom kilofa (ToolsService.poziomKilofa) - kilof
     * ma osobny system poziomowania (PickaxeSkillManager) niż reszta narzędzi.
     */
    private enum TypWymogu { PRZEDMIOT, DARMOWY, MONETY, NARZEDZIE, POZIOM_KILOFA }

    /**
     * Pojedynczy wymagany materiał + ilość - quest może mieć ich kilka naraz (np. "16x dąb + 16x brzoza").
     * customId/nazwaWyswietlana są null dla zwykłych wanilijskich wymogów (patrz Wymog.zwykly) - ustawione,
     * gdy quest musi rozróżnić konkretny custom-tagowany item (np. gatunek ryby z mainplugins-fishing) od
     * innych itemów dzielących ten sam wanilijski Material (patrz CustomItemKeys w mainplugins-core).
     */
    private record Wymog(Material material, int ilosc, String customId, String nazwaWyswietlana) {
        static Wymog zwykly(Material material, int ilosc) {
            return new Wymog(material, ilosc, null, null);
        }
    }

    private record Quest(int id, String tytul, List<String> opis, TypWymogu typWymogu, List<Wymog> wymogi,
                          double prog, List<ItemStack> nagrody, String nazwaNagrody, double monetyNagrody) {

        /** Zwykły quest "przynieś N sztuk materiału X, dostań przedmiot" - większość ścieżki. */
        static Quest przedmiot(int id, String tytul, List<String> opis, Material material, int ilosc, ItemStack nagroda, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.PRZEDMIOT, List.of(Wymog.zwykly(material, ilosc)), 0, List.of(nagroda), nazwaNagrody, 0);
        }

        /** Jak wyżej, ale kilka różnych materiałów naraz (np. dwa rodzaje drewna) i/lub kilka przedmiotów nagrody. */
        static Quest przedmioty(int id, String tytul, List<String> opis, List<Wymog> wymogi, List<ItemStack> nagrody, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.PRZEDMIOT, wymogi, 0, nagrody, nazwaNagrody, 0);
        }

        /** Wiele materiałów wymaganych naraz, ale pojedyncza nagroda. */
        static Quest przedmioty(int id, String tytul, List<String> opis, List<Wymog> wymogi, ItemStack nagroda, String nazwaNagrody) {
            return przedmioty(id, tytul, opis, wymogi, List.of(nagroda), nazwaNagrody);
        }

        /** Jak {@link #przedmiot}, ale wymóg to konkretny custom-tagowany item (np. gatunek ryby), nie dowolny o tym materiale. */
        static Quest przedmiotCustom(int id, String tytul, List<String> opis, Material material, int ilosc,
                                      String customId, String nazwaWyswietlana, ItemStack nagroda, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.PRZEDMIOT, List.of(new Wymog(material, ilosc, customId, nazwaWyswietlana)), 0, List.of(nagroda), nazwaNagrody, 0);
        }

        /** Quest bez żadnego wymogu - kliknięcie od razu zdaje zadanie i wręcza nagrodę. */
        static Quest darmowy(int id, String tytul, List<String> opis, ItemStack nagroda, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.DARMOWY, List.of(), 0, List.of(nagroda), nazwaNagrody, 0);
        }

        /** Quest płatny w monetach (koszt), nagroda zwykłym przedmiotem. */
        static Quest zaMonety(int id, String tytul, List<String> opis, double kosztMonet, ItemStack nagroda, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.MONETY, List.of(), kosztMonet, List.of(nagroda), nazwaNagrody, 0);
        }

        /** Zwykły quest przedmiotowy, ale nagrodą są monety zamiast itemu. */
        static Quest nagrodaMonety(int id, String tytul, List<String> opis, Material material, int ilosc, double monetyNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.PRZEDMIOT, List.of(Wymog.zwykly(material, ilosc)), 0, List.of(), monetyNagrody + " Monet", monetyNagrody);
        }

        /** Jak {@link #nagrodaMonety}, ale wymóg to konkretny custom-tagowany item (patrz {@link #przedmiotCustom}). */
        static Quest nagrodaMonetyCustom(int id, String tytul, List<String> opis, Material material, int ilosc,
                                          String customId, String nazwaWyswietlana, double monetyNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.PRZEDMIOT, List.of(new Wymog(material, ilosc, customId, nazwaWyswietlana)), 0, List.of(), monetyNagrody + " Monet", monetyNagrody);
        }

        /** Quest "awansuj narzędzie na tier X" - sprawdza posiadanie, NIE zabiera przedmiotu (patrz TypWymogu.NARZEDZIE). */
        static Quest narzedzie(int id, String tytul, List<String> opis, Material wymaganeNarzedzie, ItemStack nagroda, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.NARZEDZIE, List.of(Wymog.zwykly(wymaganeNarzedzie, 1)), 0, List.of(nagroda), nazwaNagrody, 0);
        }

        /** Quest "wbij kilofowi X poziom" - patrz TypWymogu.POZIOM_KILOFA. Może dawać kilka przedmiotów naraz. */
        static Quest poziomKilofa(int id, String tytul, List<String> opis, int minPoziom, List<ItemStack> nagrody, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.POZIOM_KILOFA, List.of(), minPoziom, nagrody, nazwaNagrody, 0);
        }
    }

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
        // pierwszego dnia na wyspie aż po pokonanie Enderdragona. Quest 40 dorzuca do
        // swojej zwykłej nagrody (trofeum) jeszcze Beacon w wreczNagrode() - jedyny
        // wyjątek w całej ścieżce, gdzie nagroda to więcej niż lista q.nagrody().
        //
        // Kilof/siekiera/miecz/motyka NIE są już dawane automatycznie przy pierwszym
        // wejściu - są w głównej mierze nagrodami z Głównej Ścieżki (questy 1/3/5/8
        // niżej), realne wręczenie w wreczNagrode() przez ToolsService. ItemStack w tych
        // questach to tylko placeholder do wyświetlenia w GUI, zanim gracz je ukończy.
        // Quest 2 to WYJĄTEK od wyjątku - sprawdza faktyczny POZIOM kilofa
        // (TypWymogu.POZIOM_KILOFA, ToolsService.poziomKilofa), a nagrodą są zwykłe
        // przedmioty (mączka + sadzonka), nie kolejne narzędzie. Quest 9 ("Kopacz spod
        // Ziemi") sprawdza awans kilofa do tieru Kamień, ale nagrodą jest zwykły
        // przedmiot (pochodnie) - łopata jako narzędzie została usunięta z gry.
        //
        // Późniejsze questy tych narzędzi (12, 21, 30, 31) NIE dają ich ponownie -
        // weryfikują AWANS TIERU (np. "STONE_PICKAXE x1" = kilof faktycznie doszedł do
        // tieru Kamień), bo ewoluujące narzędzia zmieniają realny Material przy awansie
        // tieru (patrz LevelableToolsManager.pobierzMaterial) - żadnego nowego
        // mechanizmu nie trzeba, to ten sam containsAtLeast() co reszta questów.
        questyKategorii.put(KATEGORIA_GLOWNA_SCIEZKA, List.of(
                Quest.darmowy(1, "Witaj na Wyspie", List.of("Twoja przygoda właśnie się zaczyna - odbierz swój pierwszy, ewoluujący kilof."),
                        new ItemStack(Material.WOODEN_PICKAXE, 1), "1x Ewoluujący Kilof"),
                Quest.poziomKilofa(2, "Zaczynamy", List.of("Wykop kilofem wystarczająco dużo, by zdobyć swój pierwszy poziom."),
                        2, List.of(new ItemStack(Material.BONE_MEAL, 10), new ItemStack(Material.BIRCH_SAPLING, 1)), "10x Mączka Kostna + Sadzonka Brzozy"),
                Quest.przedmioty(3, "Timberman", List.of("Zbierz drewno dębowe i brzozowe na rozbudowę bazy."),
                        List.of(Wymog.zwykly(Material.OAK_LOG, 16), Wymog.zwykly(Material.BIRCH_LOG, 16)),
                        new ItemStack(Material.WOODEN_AXE, 1), "1x Ewoluująca Siekiera"),
                Quest.nagrodaMonety(4, "Może zaczniemy zarabiać?", List.of("Sprzedaj nadwyżkę sadzonek dębu."),
                        Material.OAK_SAPLING, 32, 200),
                Quest.przedmioty(5, "Farmimy dalej", List.of("Kup sadzonki siana i marchewki pod przyszłe pole."),
                        List.of(Wymog.zwykly(Material.WHEAT_SEEDS, 5), Wymog.zwykly(Material.CARROT, 5)),
                        new ItemStack(Material.WOODEN_HOE, 1), "1x Ewoluująca Motyka"),
                Quest.przedmiot(6, "Plon czas zebrać", List.of("Zasadź ziarna pszenicy na swoim polu."),
                        Material.WHEAT_SEEDS, 30, new ItemStack(Material.MELON_SEEDS, 8), "8x Nasiona Arbuza"),
                Quest.nagrodaMonety(7, "Słodki Zysk", List.of("Zbierz i sprzedaj plasterki arbuza."),
                        Material.MELON_SLICE, 16, 150),
                Quest.przedmiot(8, "Pierwsza Krew", List.of("Stocz walkę z nieumarłymi i przynieś na to dowód."),
                        Material.ROTTEN_FLESH, 20, new ItemStack(Material.WOODEN_SWORD, 1), "1x Ewoluujący Miecz"),
                Quest.narzedzie(9, "Kopacz spod Ziemi", List.of("Ulepsz kilof do tieru Kamień (jeśli jeszcze nie awansował) - przyda Ci się światło w kopalni."),
                        Material.STONE_PICKAXE, new ItemStack(Material.TORCH, 32), "32x Pochodnia"),
                Quest.zaMonety(10, "Fundamenty Wyspy", List.of("Wpłać pierwsze oszczędności do banku wyspy.", "Kamień milowy - pierwszy etap za Tobą!"),
                        500, trofeum(Material.PLAYER_HEAD, "Głowa Osadnika", "Za pierwsze kroki na wyspie."), "Trofeum: Głowa Osadnika"),

                Quest.przedmiot(11, "Żelazna Gorączka", List.of("Wykop surowe żelazo w kopalni."),
                        Material.RAW_IRON, 24, new ItemStack(Material.IRON_BLOCK, 2), "2x Blok Żelaza"),
                Quest.narzedzie(12, "Zbrojmistrz", List.of("Ulepsz siekierę do tieru Żelazo."),
                        Material.IRON_AXE, new ItemStack(Material.IRON_CHESTPLATE, 1), "1x Żelazny Napierśnik"),
                Quest.przedmiot(13, "Sniffer na Etacie", List.of("Zdobądź jajo sniffera i uruchom automatyczną farmę."),
                        Material.SNIFFER_EGG, 1, new ItemStack(Material.TORCHFLOWER_SEEDS, 8), "8x Nasiona Kwiatu Pochodni"),
                Quest.przedmiot(14, "Pierwsze Zakupy", List.of("Zgromadź szmaragdy na zakupy w sklepie w gwieździe."),
                        Material.EMERALD, 10, new ItemStack(Material.EXPERIENCE_BOTTLE, 10), "10x Butelka Doświadczenia"),
                Quest.przedmiot(15, "Prąd w Ścianach", List.of("Zbierz redstone pod pierwsze mechanizmy."),
                        Material.REDSTONE, 32, new ItemStack(Material.PISTON, 8), "8x Tłok"),
                Quest.przedmiot(16, "Zaklinacz", List.of("Zbierz lapis lazuli pod stół zaklęć."),
                        Material.LAPIS_LAZULI, 32, new ItemStack(Material.ENCHANTING_TABLE, 1), "1x Stół Zaklęć"),
                Quest.zaMonety(17, "Skarbnik", List.of("Wpłać spory depozyt do banku wyspy."),
                        1000, new ItemStack(Material.GOLD_INGOT, 10), "10x Sztabka Złota (odsetki)"),
                Quest.darmowy(18, "Strażnik Wyspy", List.of("Odwiedź /spawn i poznaj chronione tereny wyspy."),
                        new ItemStack(Material.COMPASS, 1), "1x Kompas"),
                Quest.darmowy(19, "Czytelnik", List.of("Otwórz Poradnik Wyspiarza i poznaj resztę systemów wyspy."),
                        new ItemStack(Material.BOOK, 3), "3x Książka"),
                Quest.przedmiot(20, "Filar Wyspy", List.of("Udowodnij, że Twoja wyspa stoi na solidnych fundamentach.", "Kamień milowy - połowa ścieżki za Tobą!"),
                        Material.DIAMOND, 32, trofeum(Material.PLAYER_HEAD, "Głowa Górnika", "Za setki wykopanych bloków."), "Trofeum: Głowa Górnika"),

                Quest.narzedzie(21, "Diamentowa Żyła", List.of("Ulepsz kilof do tieru Diament."),
                        Material.DIAMOND_PICKAXE, new ItemStack(Material.DIAMOND_BLOCK, 1), "1x Blok Diamentu"),
                Quest.przedmiot(22, "Obsydianowy Mur", List.of("Zbierz obsydian pod portal do Netheru."),
                        Material.OBSIDIAN, 10, new ItemStack(Material.FLINT_AND_STEEL, 1), "1x Krzesiwo"),
                Quest.przedmiot(23, "Za Bramą", List.of("Wejdź do Netheru i zbierz netherrack."),
                        Material.NETHERRACK, 16, new ItemStack(Material.SOUL_TORCH, 16), "16x Duszowa Pochodnia"),
                Quest.przedmiot(24, "Łowca Blaze'ów", List.of("Zapoluj na blaze w Netherowej twierdzy."),
                        Material.BLAZE_ROD, 8, new ItemStack(Material.BLAZE_POWDER, 16), "16x Proch Blaze'a"),
                Quest.przedmiot(25, "Dusza Netheru", List.of("Zbierz duszowy piasek z Netheru."),
                        Material.SOUL_SAND, 32, new ItemStack(Material.SOUL_LANTERN, 1), "1x Duszowa Latarnia"),
                Quest.przedmiot(26, "Kwarcowy Górnik", List.of("Wydobądź kwarc netherowy."),
                        Material.QUARTZ, 32, new ItemStack(Material.QUARTZ_BLOCK, 8), "8x Blok Kwarcu"),
                Quest.przedmiot(27, "Pogromca Ghastów", List.of("Zapoluj na ghasty i zbierz ich łzy."),
                        Material.GHAST_TEAR, 4, new ItemStack(Material.FIRE_CHARGE, 8), "8x Ognista Kula"),
                Quest.przedmiot(28, "Netherytowy Traker", List.of("Znajdź złom netherytu w głębi Netheru."),
                        Material.NETHERITE_SCRAP, 4, new ItemStack(Material.GOLD_INGOT, 4), "4x Sztabka Złota"),
                Quest.przedmiot(29, "Kowal Netherytu", List.of("Wykuj pierwszą sztabkę netherytu w kuźni."),
                        Material.NETHERITE_INGOT, 1, new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1), "1x Szablon Kowalski"),
                Quest.narzedzie(30, "Pogromca Netheru", List.of("Wróć żywy z Netheru z mieczem gotowym na diamentowy tier walki.", "Kamień milowy - Nether zdobyty!"),
                        Material.DIAMOND_SWORD, trofeum(Material.WITHER_SKELETON_SKULL, "Głowa Wojownika Netheru", "Za przetrwanie Netheru."), "Trofeum: Głowa Wojownika Netheru"),

                Quest.narzedzie(31, "Netherytowy Rycerz", List.of("Ulepsz kilof do tieru Netheryt."),
                        Material.NETHERITE_PICKAXE, new ItemStack(Material.PHANTOM_MEMBRANE, 4), "4x Błona Fantoma"),
                Quest.przedmiot(32, "Brama do Endu", List.of("Zgromadź perły endermana na oczy endera."),
                        Material.ENDER_PEARL, 12, new ItemStack(Material.ENDER_EYE, 4), "4x Oko Endera"),
                Quest.przedmiot(33, "Purpurowy Architekt", List.of("Zbierz purpurowe bloki z miast Endu."),
                        Material.PURPUR_BLOCK, 32, new ItemStack(Material.END_ROD, 8), "8x Pręt Endu"),
                Quest.przedmiot(34, "Owoc Chorusu", List.of("Zbierz owoce chorusu w Endzie."),
                        Material.CHORUS_FRUIT, 32, new ItemStack(Material.POPPED_CHORUS_FRUIT, 16), "16x Prażony Owoc Chorusu"),
                Quest.przedmiot(35, "Łowca Shulkerów", List.of("Pokonaj shulkery w miastach Endu."),
                        Material.SHULKER_SHELL, 4, new ItemStack(Material.SHULKER_BOX, 1), "1x Shulker Box"),
                Quest.przedmiot(36, "Skrzydła Wolności", List.of("Zbierz błony fantomów na coś specjalnego."),
                        Material.PHANTOM_MEMBRANE, 4, new ItemStack(Material.ELYTRA, 1), "1x Elytra"),
                Quest.przedmiot(37, "Ostatni Krok", List.of("Przygotuj się na finałową walkę - zbierz złote marchewki."),
                        Material.GOLDEN_CARROT, 16, new ItemStack(Material.GOLDEN_APPLE, 4), "4x Złote Jabłko"),
                Quest.przedmiot(38, "Smoczy Oddech", List.of("Zbierz oddech smoka podczas walki z Enderdragonem."),
                        Material.DRAGON_BREATH, 8, new ItemStack(Material.NETHER_STAR, 1), "1x Gwiazda Netheru"),
                Quest.przedmiot(39, "Mistrz Farmera", List.of("Udowodnij, że Twoja farma stoi na najwyższym poziomie."),
                        Material.MELON_SLICE, 64, new ItemStack(Material.GOLDEN_HOE, 1), "1x Złota Motyka"),
                Quest.przedmiot(40, "Mistrz Wyspy", List.of("Oddaj zdobytą Gwiazdę Netheru i ukończ ścieżkę!"),
                        Material.NETHER_STAR, 1, trofeum(Material.PLAYER_HEAD, "Głowa Smoka", "Za pokonanie Enderdragona.", "Otrzymujesz też Beacon!"), "Trofeum: Głowa Smoka + Beacon")
        ));

        // GÓRNICTWO - 40 questów (pokazuje paginację)
        List<Quest> gornictwo = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            gornictwo.add(Quest.przedmiot(i, "Górnik " + i, List.of(), Material.COBBLESTONE, 64, new ItemStack(Material.DIAMOND, 1), "1x Diament"));
        }
        questyKategorii.put("Górnictwo", gornictwo);

        // HODOWLA
        questyKategorii.put("Hodowla", List.of(
                Quest.przedmiot(1, "Zbiory 1", List.of(), Material.CARROT, 32, new ItemStack(Material.EMERALD, 5), "5x Szmaragd"),
                Quest.przedmiot(2, "Zbiory 2", List.of(), Material.WHEAT, 64, new ItemStack(Material.GOLD_INGOT, 10), "10x Złoto"),
                Quest.przedmiot(3, "Zbiory 3", List.of(), Material.POTATO, 64, new ItemStack(Material.IRON_INGOT, 32), "32x Żelazo")
        ));

        // ŁOWCA
        questyKategorii.put("Łowca", List.of(
                Quest.przedmiot(1, "Początkujący", List.of(), Material.ROTTEN_FLESH, 32, new ItemStack(Material.COOKED_BEEF, 16), "16x Pieczona Wołowina"),
                Quest.przedmiot(2, "Strzelec", List.of(), Material.BONE, 16, new ItemStack(Material.BOW, 1), "1x Łuk"),
                Quest.przedmiot(3, "Nocny Marek", List.of(), Material.STRING, 10, new ItemStack(Material.EXPERIENCE_BOTTLE, 16), "16x Butelka EXP")
        ));

        // RYBAK - ikona w gwieździe istniała od dawna, ale kategoria była pusta (patrz
        // getOrDefault w otworzKategorie). Ścieżka wędkarska mainplugins-fishing: gracz
        // zaczyna zwykłą (wanilijską, craftowalną) wędką, questy 1/2/3 uczą podstaw łowienia
        // w wodzie, quest 4 (darmowy) daje recepturę - połączona w kowadle ze zwykłą wędką
        // daje Niebiańską Wędkę (łowienie w powietrzu, patrz mainplugins-fishing), questy 5/6
        // wymuszają realne użycie nowej wędki (te gatunki łapie się WYŁĄCZNIE w powietrzu),
        // quest 7 (darmowy) daje drugą recepturę na Wędkę Kosmiczną DARKSTAR, quest 8 to
        // finałowe złowienie najrzadszej ryby w grze. Zero zależności od mainplugins-fishing -
        // receptury to zwykłe tagowane papiery (patrz receptura()), fishing plugin rozpoznaje
        // je przez współdzielony CustomItemKeys.CUSTOM_ITEM_ID, tak samo jak gatunki ryb niżej.
        questyKategorii.put("Rybak", List.of(
                Quest.przedmiotCustom(1, "Pierwszy Połów", List.of("Złów i przynieś swoje pierwsze ryby zwykłą wędką."),
                        Material.COD, 5, "FISH_KARP_MIELIZNY", "Karp Mielizny",
                        new ItemStack(Material.EMERALD, 5), "5x Szmaragd"),
                Quest.nagrodaMonetyCustom(2, "W Głąb Wody", List.of("Łów dalej - tym razem srebrne leszcze."),
                        Material.SALMON, 8, "FISH_SREBRNY_LESZCZ", "Srebrny Leszcz", 500),
                Quest.nagrodaMonetyCustom(3, "Kolekcjoner Łusek", List.of("Złów coś rzadszego niż zwykłe ryby."),
                        Material.TROPICAL_FISH, 1, "FISH_TECZOWY_SKRZELACZ", "Tęczowy Skrzelacz", 350),
                Quest.darmowy(4, "Sekret Kowala", List.of("Miejscowy kowal zna sekret wędek, których nie da się kupić.",
                                "Połącz tę recepturę ze zwykłą wędką w kowadle."),
                        receptura("FISHING_RECIPE_NIEBIANSKA", "Receptura: Niebiańska Wędka",
                                "Połącz z Zwykłą Wędką w kowadle,", "by otrzymać Niebiańską Wędkę."),
                        "Receptura: Niebiańska Wędka"),
                Quest.przedmiotCustom(5, "Rybak Niebios", List.of("Wykuj Niebiańską Wędkę i złów nią coś,",
                                "czego nie da się złowić w wodzie."),
                        Material.SALMON, 3, "FISH_OBLOCZNY_LATAWIEC", "Obłoczny Latawiec",
                        new ItemStack(Material.EXPERIENCE_BOTTLE, 32), "32x Butelka Doświadczenia"),
                Quest.nagrodaMonetyCustom(6, "Więcej niż Ryby", List.of("Łów dalej w powietrzu - trafiają się coraz ciekawsze gatunki."),
                        Material.TROPICAL_FISH, 2, "FISH_GWIEZDNY_PROMYK", "Gwiezdny Promyk", 1200),
                Quest.darmowy(7, "Szept Kosmosu", List.of("Wśród gwiazd krąży plotka o wędce, która przyciąga jeszcze rzadsze ryby."),
                        receptura("FISHING_RECIPE_KOSMICZNA", "Receptura: Wędka Kosmiczna DARKSTAR",
                                "Połącz z Niebiańską Wędką w kowadle,", "by otrzymać Wędkę Kosmiczną DARKSTAR."),
                        "Receptura: Wędka Kosmiczna DARKSTAR"),
                Quest.przedmiotCustom(8, "Mistrz Wędki", List.of("Złów legendarną Iskrę Ciemnej Gwiazdy Wędką Kosmiczną DARKSTAR.",
                                "Ostatni krok ścieżki wędkarskiej!"),
                        Material.COD, 1, "FISH_DARKSTAR_ISKRA", "Iskra Ciemnej Gwiazdy",
                        trofeum(Material.PLAYER_HEAD, "Głowa Rybaka Otchłani", "Za złowienie tego, czego nie da się złowić."),
                        "Trofeum: Głowa Rybaka Otchłani")
        ));

        // QUESTY SPECJALNE (dawniej "Mistrz") - trudne, kosztowne zadania dla weteranów.
        questyKategorii.put("Questy Specjalne", List.of(
                Quest.przedmiot(1, "Górski Kolos", List.of("Dla prawdziwych weteranów kopalni."),
                        Material.DIAMOND, 64, new ItemStack(Material.NETHERITE_INGOT, 1), "1x Sztabka Netherytu"),
                Quest.przedmiot(2, "Wojownik Otchłani", List.of("Poluj na eliksir mocy w Netherze."),
                        Material.WITHER_SKELETON_SKULL, 4, new ItemStack(Material.TOTEM_OF_UNDYING, 1), "1x Totem Nieśmiertelności"),
                Quest.przedmiot(3, "Skarb Smoka", List.of("Pokonaj Smoka Endera."),
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

    /** Nagroda-trofeum za kamień milowy Głównej Ścieżki (id 10/20/30/40) - nazwana głowa z lore. */
    private static ItemStack trofeum(Material material, String nazwa, String... loreLinie) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        for (String linia : loreLinie) {
            lore.add(Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** "16x OAK_LOG, 16x BIRCH_LOG" - łączy kilka wymaganych materiałów w jeden czytelny tekst do lore. */
    private String opisWymogow(List<Wymog> wymogi) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wymogi.size(); i++) {
            if (i > 0) sb.append(", ");
            Wymog w = wymogi.get(i);
            sb.append(w.ilosc()).append("x ").append(w.nazwaWyswietlana() != null ? w.nazwaWyswietlana() : w.material().name());
        }
        return sb.toString();
    }

    /**
     * Papier-receptura wręczany jako nagroda za darmowy quest (np. "Sekret Kowala") - tagowany
     * współdzielonym CustomItemKeys.CUSTOM_ITEM_ID, żeby mainplugins-fishing (osobny plugin,
     * bez żadnej zależności między modułami) rozpoznał go w kowadle i podmienił na lepszą wędkę.
     */
    private static ItemStack receptura(String customId, String nazwa, String... loreLinie) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, NamedTextColor.AQUA, TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        for (String linia : loreLinie) {
            lore.add(Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, customId);
        meta.setEnchantmentGlintOverride(true);
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
            String wymogTekst = switch (q.typWymogu()) {
                case DARMOWY -> "Wymaga: nic - kliknij, by odebrać!";
                case MONETY -> "Wymaga: " + (int) q.prog() + " monet";
                case POZIOM_KILOFA -> "Wymaga: kilofa na poziomie " + (int) q.prog();
                case PRZEDMIOT -> "Wymaga: " + opisWymogow(q.wymogi());
                case NARZEDZIE -> "Wymaga: posiadania " + opisWymogow(q.wymogi()) + " (zostaje przy Tobie)";
            };
            lore.add(Component.text(wymogTekst, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
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

    /** Zwykły wymóg (customId null) -> zwykłe containsAtLeast po materiale. Custom (np. gatunek ryby) -> liczy tylko itemy z pasującym PDC tagiem. */
    private boolean posiadaWymaganaIlosc(Player player, Wymog w) {
        if (w.customId() == null) {
            return player.getInventory().containsAtLeast(new ItemStack(w.material()), w.ilosc());
        }
        int suma = 0;
        for (ItemStack is : player.getInventory().getContents()) {
            if (is == null || is.getType() != w.material() || !is.hasItemMeta()) continue;
            if (w.customId().equals(is.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING))) {
                suma += is.getAmount();
            }
        }
        return suma >= w.ilosc();
    }

    /** Odpowiednik {@link #posiadaWymaganaIlosc} przy zabieraniu - zwykły removeItem po materiale, albo precyzyjne zdjęcie tylko pasujących custom itemów. */
    private void zabierzWymog(Player player, Wymog w) {
        if (w.customId() == null) {
            player.getInventory().removeItem(new ItemStack(w.material(), w.ilosc()));
            return;
        }
        int doZabrania = w.ilosc();
        ItemStack[] zawartosc = player.getInventory().getContents();
        for (int i = 0; i < zawartosc.length && doZabrania > 0; i++) {
            ItemStack is = zawartosc[i];
            if (is == null || is.getType() != w.material() || !is.hasItemMeta()) continue;
            if (!w.customId().equals(is.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING))) continue;

            int zabierz = Math.min(is.getAmount(), doZabrania);
            is.setAmount(is.getAmount() - zabierz);
            doZabrania -= zabierz;
            if (is.getAmount() <= 0) zawartosc[i] = null;
        }
        player.getInventory().setContents(zawartosc);
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

        boolean spelnionyWymog = switch (q.typWymogu()) {
            case DARMOWY -> true;
            case MONETY -> CoreAPI.getEconomyService().maWystarczajaco(player.getUniqueId(), q.prog());
            case POZIOM_KILOFA -> {
                ToolsService tools = CoreAPI.getToolsService();
                yield tools != null && tools.poziomKilofa(player) >= q.prog();
            }
            case PRZEDMIOT, NARZEDZIE -> q.wymogi().stream().allMatch(w -> posiadaWymaganaIlosc(player, w));
        };

        if (spelnionyWymog) {
            switch (q.typWymogu()) {
                case DARMOWY, NARZEDZIE, POZIOM_KILOFA -> {} // te typy tylko sprawdzają - nic nie zabierają graczowi
                case MONETY -> CoreAPI.getEconomyService().odejmijKase(player.getUniqueId(), q.prog());
                case PRZEDMIOT -> q.wymogi().forEach(w -> zabierzWymog(player, w));
            }
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
            String powod = switch (q.typWymogu()) {
                case MONETY -> "Nie masz wystarczająco monet!";
                case NARZEDZIE -> "Twoje narzędzie nie jest jeszcze na wymaganym tierze!";
                case POZIOM_KILOFA -> "Twój kilof nie jest jeszcze na wymaganym poziomie!";
                case PRZEDMIOT, DARMOWY -> "Nie masz wymaganych przedmiotów!";
            };
            player.sendMessage(Component.text(powod, NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        }
    }

    /**
     * Wręcza nagrodę za quest. Wyjątek od "zwykłego ItemStacka z configu listy" - questy
     * 1/3/5/8 Głównej Ścieżki (kilof/siekiera/motyka/miecz), gdzie zamiast placeholdera
     * z zaladujQuesty() gracz dostaje PRAWDZIWE, działające ewoluujące narzędzie z
     * mainplugins-tools (patrz ToolsService/CoreAPI - ten sam mechanizm poziomowania,
     * ochrony przed wyrzuceniem itd.). Jeśli mainplugins-tools nie jest akurat wgrany,
     * spadamy z powrotem na placeholder, żeby gracz nie stracił przedmiotów za quest bez
     * żadnej nagrody.
     */
    private void wreczNagrode(Player player, String kategoria, Quest q) {
        if (kategoria.equals(KATEGORIA_GLOWNA_SCIEZKA)) {
            ToolsService tools = CoreAPI.getToolsService();
            if (tools != null) {
                switch (q.id()) {
                    case 1 -> { tools.dajEwoluujacyKilof(player); return; }
                    case 3 -> { tools.dajEwoluujacaSiekiere(player); return; }
                    case 5 -> { tools.dajEwoluujacaMotyke(player); return; }
                    case 8 -> { tools.dajEwoluujacyMiecz(player); return; }
                }
            }
        }
        if (q.monetyNagrody() > 0) {
            CoreAPI.getEconomyService().dodajKase(player.getUniqueId(), q.monetyNagrody());
        } else {
            q.nagrody().forEach(item -> player.getInventory().addItem(item));
        }
        // Quest 40 ("Mistrz Wyspy") dorzuca Beacon do trofeum - jedyny wyjątek, gdzie
        // ścieżka wręcza dwa przedmioty naraz, patrz komentarz w zaladujQuesty().
        if (kategoria.equals(KATEGORIA_GLOWNA_SCIEZKA) && q.id() == 40) {
            player.getInventory().addItem(new ItemStack(Material.BEACON, 1));
        }
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