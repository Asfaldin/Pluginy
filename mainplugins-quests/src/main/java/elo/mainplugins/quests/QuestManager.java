package elo.mainplugins.quests;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.ToolsService;
import elo.mainplugins.core.api.TytulService;
import elo.mainplugins.core.util.CustomItemKeys;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
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
 * /quest w całości - menu kategorii w kształcie diamentu/brylantu (romb: wąsko u góry,
 * najszerzej w środku, znów wąsko na dole, patrz SLOTY_DIAMENTU), wszystkie kategorie
 * (włącznie z Główną Ścieżką na dolnym wierzchołku) to ten sam mechanizm "przynieś
 * przedmiot, oddaj, dostań nagrodę". Zero zależności od zewnętrznych pluginów (bez
 * NPC/Citizens) - to świadomy powrót do prostszego modelu, patrz komentarz w pom.xml
 * tego modułu.
 *
 * Dolny wierzchołek diamentu = Główna Ścieżka (KATEGORIA_GLOWNA_SCIEZKA) - jedyna
 * kategoria, w której zadania odblokowują się PO KOLEI (patrz ustalStan) i renderują się
 * jako wężyk (SLOTY_WEZYK), a nie siatka. Kategorie powyżej = zwykłe zadania poboczne w
 * dowolnej kolejności (siatka, slotySrodkowe), tak samo jak "Questy Specjalne" -
 * to po prostu kolejna kategoria z trudniejszą/rzadszą treścią, bez specjalnej logiki.
 */
public class QuestManager implements Listener, TytulService {

    /**
     * PRZEDMIOT - klasyczne "przynieś N sztuk materiału (jednego lub kilku naraz), zostaje
     * zabrane" (domyślne, większość questów).
     * DARMOWY - brak wymogu, kliknięcie od razu zdaje quest (np. "sprawdź spawn").
     * MONETY - zamiast przedmiotów, prog to koszt w monetach (EconomyService).
     * NARZEDZIE - jak PRZEDMIOT, ale NIE zabiera przedmiotu po zdaniu. Wyłącznie do
     * questów "awansuj narzędzie na tier X" - ewoluujące narzędzia (siekiera/motyka/miecz,
     * mainplugins-tools) zmieniają realny Material przy awansie tieru
     * (LevelableToolsManager.pobierzMaterial), więc containsAtLeast(DIAMOND_AXE, 1)
     * wystarcza do weryfikacji tieru. Zwykłe removeItem() by tu ZABRAŁO graczowi jego
     * jedyne, prawdziwe (nie placeholder) narzędzie jako "zapłatę" za quest - stąd osobny
     * typ. Kilof NIE używa już tego typu (patrz POZIOM_KILOFA) - odkąd ma kilka
     * odrębnych TYPÓW zamiast tierów (PickaxeType), nie ma już jednego "Material tieru X"
     * do sprawdzenia.
     * POZIOM_KILOFA - prog to minimalny poziom kilofa (ToolsService.poziomKilofa, TERAZ:
     * najwyższy poziom wśród WSZYSTKICH trzymanych kilofów, dowolnego typu) - kilof ma
     * osobny system poziomowania (PickaxeSkillManager) niż reszta narzędzi.
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

        /** Jak {@link #nagrodaMonety}, ale kilka różnych materiałów naraz (np. arbuz + pszenica + marchewka). */
        static Quest nagrodaMonety(int id, String tytul, List<String> opis, List<Wymog> wymogi, double monetyNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.PRZEDMIOT, wymogi, 0, List.of(), monetyNagrody + " Monet", monetyNagrody);
        }

        /** Jak {@link #nagrodaMonety}, ale wymóg to konkretny custom-tagowany item (patrz {@link #przedmiotCustom}). */
        static Quest nagrodaMonetyCustom(int id, String tytul, List<String> opis, Material material, int ilosc,
                                          String customId, String nazwaWyswietlana, double monetyNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.PRZEDMIOT, List.of(new Wymog(material, ilosc, customId, nazwaWyswietlana)), 0, List.of(), monetyNagrody + " Monet", monetyNagrody);
        }

        /**
         * Jedyny wariant łączący RÓWNOCZEŚNIE przedmioty i monety jako nagrodę (patrz
         * odpowiadająca zmiana w wreczNagrode() - if/if zamiast if/else). Reszta factory
         * method zawsze ustawia tylko jedno z dwóch (nagrody ITEMS xor monetyNagrody).
         */
        static Quest przedmiotyIMonety(int id, String tytul, List<String> opis, List<Wymog> wymogi,
                                        List<ItemStack> nagrody, double monetyNagrody, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.PRZEDMIOT, wymogi, 0, nagrody, nazwaNagrody, monetyNagrody);
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

    /**
     * Stan jednego aktywnego "ataku" Płaczącego Obsydianu - pasek bossbara resetuje się
     * do pełna na starcie każdej fali i zmniejsza się wraz z zabijaniem TYLKO zombie z TEJ
     * fali (patrz onZombieDeath/kluczSesjiFali). Klucz mapy aktywneFale to losowe UUID sesji
     * (nie gracza), żeby dwóch graczy mogło jednocześnie odpalić własny Płaczący Obsydian
     * bez kolizji stanu. zywoZombie śledzi UUID-y aktualnie żywych zombie tej sesji - patrz
     * pilnujSpadajacychZombie(), który co sekundę teleportuje je z powrotem, gdyby spadły
     * za krawędź wyspy w otchłań (kotwica = blok, przy którym siedzą wszystkie fale).
     */
    private static final class StanFali {
        final BossBar pasek;
        final Block kotwica;
        int numerFali;
        int wFaliLacznie;
        int pozostaliWFali;
        final Set<UUID> zywoZombie = new HashSet<>();
        StanFali(BossBar pasek, Block kotwica) { this.pasek = pasek; this.kotwica = kotwica; }
    }

    private final Map<UUID, StanFali> aktywneFaleZombie = new HashMap<>();
    private final NamespacedKey kluczSesjiFali;

    // Definiujemy 35 slotów na środku (7 kolumn x 5 rzędów) - kategorie zwykłe (dowolna kolejność).
    private final int[] slotySrodkowe = {
            1, 2, 3, 4, 5, 6, 7,
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    /**
     * 23 sloty (nie 35 jak slotySrodkowe) - Główna Ścieżka ma teraz "wężyk z przerwą":
     * pełny rząd (7 slotów) → JEDEN łącznik na krawędzi w następnym rzędzie (reszta tego
     * rzędu to czyste tło, przerwa) → znów pełny rząd, itd. Łącznik naprzemiennie po prawej
     * (slot 16) i lewej (slot 28) stronie, żeby dotykał sąsiedniego rzędu W TEJ SAMEJ
     * kolumnie co koniec poprzedniego/początek następnego odcinka - wężyk fizycznie się
     * styka na bokach, tylko środek każdego "pustego" rzędu zostaje pusty. Reszta rzędu 1/3
     * (poza łącznikiem) NIE ma wpisu tutaj - wypelnijTlo() i tak wypełnia całe GUI szarym
     * szkłem jako tło PRZED ustawieniem ikon questów, więc pominięte sloty automatycznie
     * zostają "przerwą" bez dodatkowego kodu.
     */
    private static final int[] SLOTY_WEZYK = {
            1, 2, 3, 4, 5, 6, 7,
            16,
            25, 24, 23, 22, 21, 20, 19,
            28,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final int SLOT_DOL_DIAMENTU = 40; // Główna Ścieżka - dolny wierzchołek diamentu, wyśrodkowany, widoczny na KAŻDEJ stronie
    private static final String KATEGORIA_GLOWNA_SCIEZKA = "Główna Ścieżka";

    // Tag CustomItemKeys.CUSTOM_ITEM_ID nagrody questu 9 - patrz placzacyObsydian()/onPlaceObsydian().
    private static final String CUSTOM_ID_PLACZACY_OBSYDIAN = "QUEST_PLACZACY_OBSYDIAN";

    /**
     * Kategorie boczne układają się nad SLOT_DOL_DIAMENTU jako romb (diament/brylant) -
     * pełne, wyśrodkowane pasy, które rosną do środka i znów zwężają się ku dołowi:
     * rząd 1 (góra) → 1 slot (wierzchołek), rząd 2 → 3, rząd 3 → 5 (najszerszy środek),
     * rząd 4 → 3, zbiegając się w Główną Ścieżkę w rzędzie 5 (drugi wierzchołek) - każdy
     * rząd wyśrodkowany na kolumnie 5 (9-kolumnowe, 54-slotowe GUI). To 12 slotów na stronę;
     * 22 kategorie boczne nie mieszczą się naraz, więc stronicujemy (patrz
     * KATEGORII_NA_STRONE/otworzMenuQuestow). Kolejność w tablicy = kolejność przypisywania
     * kategorii z KATEGORIE_GWIAZDY niżej.
     */
    private static final int[] SLOTY_DIAMENTU = {
            4,
            12, 13, 14,
            20, 21, 22, 23, 24,
            30, 31, 32
    };

    private static final int KATEGORII_NA_STRONE = SLOTY_DIAMENTU.length; // 12

    public QuestManager(Plugin plugin) {
        this.plugin = plugin;
        this.kluczSesjiFali = new NamespacedKey(plugin, "siege-session");
        this.plikPostepow = new File(plugin.getDataFolder(), "quests.yml");
        if (!plikPostepow.exists()) {
            plikPostepow.getParentFile().mkdirs();
            try { plikPostepow.createNewFile(); } catch (IOException ignored) {}
        }
        this.configPostepow = YamlConfiguration.loadConfiguration(plikPostepow);
        zaladujQuesty();
        wczytajPostep();
        Bukkit.getScheduler().runTaskTimer(plugin, this::pilnujSpadajacychZombie, 20L, 20L);
    }

    private void zaladujQuesty() {
        // GŁÓWNA ŚCIEŻKA - dolny wierzchołek diamentu, 40 zadań PO KOLEI (patrz ustalStan), od
        // pierwszego dnia na wyspie aż po pokonanie Enderdragona. Quest 40 dorzuca do
        // swojej zwykłej nagrody (trofeum) jeszcze Beacon w wreczNagrode() - jedyny
        // wyjątek w całej ścieżce, gdzie nagroda to więcej niż lista q.nagrody().
        //
        // Kamienie milowe 18/20/30/40 dorzucają też skrzynkę+klucz (mainplugins-crates,
        // tier 1/1/2/3 - patrz wreczSkrzynkeKamienMilowy()) - to jedyne organiczne źródło
        // skrzynek dla zwykłego gracza (reszta to komendy admina albo ~3.7% szansy z
        // wędkowania), więc Główna Ścieżka celowo pełni tę rolę zamiast zostawiać to
        // przypadkowi. Quest 8 dorzuca WŁASNĄ, osobną skrzynkę tier 1 - patrz specjalny
        // przypadek q.id()==8 w wreczNagrode() (NIE przez wreczSkrzynkeKamienMilowy, bo
        // tam skrzynka jest cichym bonusem obok innej głównej nagrody, a tu ma to być
        // JEDYNA, w pełni widoczna nagroda questu). Questy 17/18 to para "spore
        // oszczędności" (17, 5000$) + "pierwszy powazny zakup" (18, 20000$) - progi
        // dobrane tak, by nie dało się ich zdać samą sprzedażą kamienia/drewna z
        // wczesnych questów, ale były realne po ogarnięciu rudy/emeraldów (questy 11/14)
        // - patrz też sklep.yml: NETHERITE_SCRAP/GHAST_TEAR/BLAZE_ROD celowo NIE mają
        // już buy-price, żeby questy 24/27/28 (polowanie na blaze'y/ghasty, szukanie
        // złomu netherytu) nie dało się odkupić w sklepie zamiast faktycznie zrobić -
        // inne "trudne" materiały (SHULKER_SHELL, ROTTEN_FLESH, RAW_IRON, LAPIS_LAZULI,
        // ENDER_PEARL, PHANTOM_MEMBRANE) już tak miały (sprzedaż-only albo zaporowa
        // cena), to ujednolica resztę ścieżki do tego samego standardu.
        //
        // Kilof/siekiera/miecz/motyka NIE są już dawane automatycznie przy pierwszym
        // wejściu - są w głównej mierze nagrodami z Głównej Ścieżki (questy 1/4/6/9
        // niżej), realne wręczenie w wreczNagrode() przez ToolsService. ItemStack w tych
        // questach to tylko placeholder do wyświetlenia w GUI, zanim gracz je ukończy.
        // Quest 2 to WYJĄTEK od wyjątku - sprawdza faktyczny POZIOM kilofa
        // (TypWymogu.POZIOM_KILOFA, ToolsService.poziomKilofa), a nagrodą są zwykłe
        // przedmioty (ziemia), nie kolejne narzędzie. Quest 9 to DRUGI wyjątek od
        // wzorca "1 quest = 1 narzędzie" - oprócz miecza (wręczanego w wreczNagrode(),
        // BEZ return po tools.dajEwoluujacyMiecz(), żeby quest wciąż dorzucił swoją
        // zwykłą nagrodę niżej) daje jeszcze Płaczący Obsydian Wezwania - patrz sekcja
        // "Płaczący Obsydian" niżej w pliku.
        //
        // Późniejsze questy tych narzędzi (12, 30) NIE dają ich ponownie - weryfikują
        // AWANS TIERU (np. "IRON_AXE x1" = siekiera faktycznie doszła do tieru Żelazo),
        // bo ewoluujące narzędzia zmieniają realny Material przy awansie tieru (patrz
        // LevelableToolsManager.pobierzMaterial) - ten sam containsAtLeast() co reszta
        // questów. Kilof (21, 31) już NIE ma tierów (patrz PickaxeType) - te dwa questy
        // zamiast tego wymagają konkretnego POZIOMU kilofa (Quest.poziomKilofa).
        questyKategorii.put(KATEGORIA_GLOWNA_SCIEZKA, List.of(
                Quest.darmowy(1, "Witaj na Wyspie", List.of("Twoja przygoda właśnie się zaczyna - odbierz swój pierwszy kilof."),
                        new ItemStack(Material.DIAMOND_PICKAXE, 1), "1x Kilof Wydajnościowy"),
                Quest.poziomKilofa(2, "Zaczynamy", List.of("Wykop kilofem wystarczająco dużo, by zdobyć swój pierwszy poziom."),
                        1, List.of(new ItemStack(Material.DIRT, 16)), "16x Ziemia"),
                Quest.przedmioty(3, "Kamienny Fundament", List.of("Zbierz stack kamienia - podwaliny pod całą resztę wyspy."),
                        List.of(Wymog.zwykly(Material.COBBLESTONE, 64)),
                        List.of(new ItemStack(Material.BONE_MEAL, 20), new ItemStack(Material.BIRCH_SAPLING, 1)), "20x Mączka Kostna + Sadzonka Brzozy"),
                Quest.przedmioty(4, "Drwal i Siewca", List.of("Zbierz drewno dębowe i sadzonki brzozy na rozbudowę bazy."),
                        List.of(Wymog.zwykly(Material.OAK_LOG, 8), Wymog.zwykly(Material.BIRCH_SAPLING, 6)),
                        new ItemStack(Material.WOODEN_AXE, 1), "1x Ewoluująca Siekiera"),
                Quest.przedmioty(5, "Rozpal Ognisko", List.of("Zbierz węgiel z kopalni i węgiel drzewny z paleniska."),
                        List.of(Wymog.zwykly(Material.COAL, 5), Wymog.zwykly(Material.CHARCOAL, 5)),
                        List.of(new ItemStack(Material.CARROT, 2), new ItemStack(Material.MELON_SEEDS, 4)), "2x Marchewka + 4x Nasiona Arbuza"),
                Quest.narzedzie(6, "Rolniczy Krok", List.of("Stwórz kamienną motykę - to sygnał, że jesteś gotowy na prawdziwe pole."),
                        Material.STONE_HOE, new ItemStack(Material.WOODEN_HOE, 1), "1x Ewoluująca Motyka"),
                Quest.nagrodaMonety(7, "Plony Wyspy", List.of("Zbierz pierwsze duże zbiory - arbuza, pszenicy i marchewki."),
                        List.of(Wymog.zwykly(Material.MELON_SLICE, 16), Wymog.zwykly(Material.WHEAT, 16), Wymog.zwykly(Material.CARROT, 16)),
                        200),
                Quest.przedmiot(8, "Ciacho na Szczęście", List.of("Upiecz i zdobądź ciastka - drobna nagroda za słodki gest."),
                        Material.COOKIE, 32, new ItemStack(Material.CHEST, 1), "Podstawowa Skrzynka + Klucz (słaby drop)"),
                Quest.zaMonety(9, "Fundusz Obronny", List.of("Wpłać pierwsze oszczędności do banku wyspy.", "Coś w mrocznym obsydianie już się budzi..."),
                        100, placzacyObsydian(), "Płaczący Obsydian Wezwania + 1x Ewoluujący Miecz"),
                Quest.przedmiot(10, "Egzamin Początkującego", List.of("Sprzedaj dowód stoczonych walk z nieumarłymi.", "Kamień milowy - pierwszy etap za Tobą!"),
                        Material.ROTTEN_FLESH, 5, trofeum(Material.PLAYER_HEAD, "Głowa Początkującego", "Za pierwsze pokonane zombie.", "Odblokowuje tytuł \"Początkujący\" na czacie!"), "Trofeum: Głowa Początkującego + Tytuł"),

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
                        5000, new ItemStack(Material.GOLD_INGOT, 10), "10x Sztabka Złota (odsetki)"),
                Quest.zaMonety(18, "Pierwsza Inwestycja", List.of("Udowodnij, że stać Cię na coś poważniejszego niż podstawy.",
                                "Nie kupujemy Ci tego za Ciebie - po prostu pokaż, że masz na to środki."),
                        20000, trofeum(Material.PLAYER_HEAD, "Głowa Inwestora", "Za pierwszy powazny kapital."), "Trofeum: Głowa Inwestora"),
                Quest.darmowy(19, "Czytelnik", List.of("Otwórz Poradnik Wyspiarza i poznaj resztę systemów wyspy."),
                        new ItemStack(Material.BOOK, 3), "3x Książka"),
                Quest.przedmiot(20, "Filar Wyspy", List.of("Udowodnij, że Twoja wyspa stoi na solidnych fundamentach.", "Kamień milowy - połowa ścieżki za Tobą!"),
                        Material.DIAMOND, 32, trofeum(Material.PLAYER_HEAD, "Głowa Górnika", "Za setki wykopanych bloków."), "Trofeum: Głowa Górnika"),

                Quest.poziomKilofa(21, "Diamentowa Żyła", List.of("Wbij swojemu kilofowi 30 poziom."),
                        30, List.of(new ItemStack(Material.DIAMOND_BLOCK, 1)), "1x Blok Diamentu"),
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

                Quest.poziomKilofa(31, "Netherytowy Rycerz", List.of("Wbij swojemu kilofowi 60 poziom."),
                        60, List.of(new ItemStack(Material.PHANTOM_MEMBRANE, 4)), "4x Błona Fantoma"),
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

        // GÓRNICTWO - odblokowywana questem #3 Głównej Ścieżki (patrz WYMOG_ODBLOKOWANIA_KATEGORII).
        // W odróżnieniu od Głównej Ścieżki (fabuła, jeden konkretny tier narzędzia na raz)
        // to grind na ZMIENNOŚĆ i GOTÓWKĘ - różne rudy naraz, głównie nagrody pieniężne,
        // rosnące ilości. Zero powtórzeń wymogów z Głównej Ścieżki (tam już jest diament/
        // netheryt jako fabularny wymóg tieru narzędzia - tu to osobny, równoległy grind).
        questyKategorii.put("Górnictwo", List.of(
                Quest.nagrodaMonety(1, "Węglowa Żyła", List.of("Rozgrzej się na start - zbierz węgiel."),
                        Material.COAL, 64, 300),
                Quest.nagrodaMonety(2, "Miedziany Początek", List.of("Miedź nie jest efektowna, ale dobrze się sprzedaje."),
                        Material.RAW_COPPER, 48, 400),
                Quest.nagrodaMonety(3, "Rudonośna Żyła", List.of("Wykop więcej surowego żelaza niż potrzebujesz na własny użytek."),
                        Material.RAW_IRON, 48, 700),
                Quest.nagrodaMonety(4, "Złota Gorączka", List.of("Złoto w Overworldzie jest rzadsze niż żelazo - warto po nie zejść głębiej."),
                        Material.RAW_GOLD, 32, 900),
                Quest.przedmiot(5, "Prąd pod Ziemią", List.of("Zbierz redstone na własne mechanizmy - to nie jest ten sam depozyt co w Głównej Ścieżce."),
                        Material.REDSTONE, 48, new ItemStack(Material.REDSTONE_BLOCK, 4), "4x Blok Redstone"),
                Quest.przedmiot(6, "Lazurowa Żyła", List.of("Zbierz lapis - starczy na więcej niż jeden stół zaklęć."),
                        Material.LAPIS_LAZULI, 48, new ItemStack(Material.EXPERIENCE_BOTTLE, 20), "20x Butelka Doświadczenia"),
                Quest.nagrodaMonety(7, "Ametystowa Jaskinia", List.of("Znajdź geodę ametystu i zbierz z niej kryształy."),
                        Material.AMETHYST_SHARD, 20, 1200),
                Quest.nagrodaMonety(8, "Kwarcowe Podziemia", List.of("Nether ma nie tylko blaze - kwarc też się przyda."),
                        Material.QUARTZ, 48, 1000),
                Quest.nagrodaMonety(9, "Diamentowe Oko", List.of("Osiem diamentów - solidny dowód, że umiesz już kopać głęboko."),
                        Material.DIAMOND, 8, 2500),
                Quest.przedmiot(10, "Skarb Otchłani", List.of("Trzydzieści dwa diamenty naraz - to już poważny wynik."),
                        Material.DIAMOND, 32, new ItemStack(Material.DIAMOND_BLOCK, 2), "2x Blok Diamentu"),
                Quest.nagrodaMonety(11, "Starożytny Ślad", List.of("Złom netherytu trzeba faktycznie wykopać - w sklepie już go nie kupisz."),
                        Material.NETHERITE_SCRAP, 2, 12000),
                Quest.przedmioty(12, "Mistrz Kopalni", List.of("Ostatni sprawdzian - pokaż, że Twoja kopalnia stoi na solidnych fundamentach.",
                                "Wymaga żelaza, złota i diamentów naraz."),
                        List.of(Wymog.zwykly(Material.RAW_IRON, 64), Wymog.zwykly(Material.RAW_GOLD, 64), Wymog.zwykly(Material.DIAMOND, 16)),
                        new ItemStack(Material.NETHERITE_BLOCK, 1), "1x Blok Netherytu")
        ));

        // HODOWLA - odblokowywana questem #5 Głównej Ścieżki (Ewoluująca Motyka).
        questyKategorii.put("Hodowla", List.of(
                Quest.przedmiot(1, "Zbiory 1", List.of("Rozkręć swoje pole - zacznij od marchewki."),
                        Material.CARROT, 32, new ItemStack(Material.EMERALD, 5), "5x Szmaragd"),
                Quest.przedmiot(2, "Zbiory 2", List.of("Pszenica to podstawa każdej dobrej farmy."),
                        Material.WHEAT, 64, new ItemStack(Material.GOLD_INGOT, 10), "10x Złoto"),
                Quest.przedmiot(3, "Zbiory 3", List.of("Ziemniaki rosną szybko - zbierz spory zapas."),
                        Material.POTATO, 64, new ItemStack(Material.IRON_INGOT, 32), "32x Żelazo"),
                Quest.przedmiot(4, "Burakowe Pole", List.of("Buraki przydają się do farb i zup - zbierz ich sporo."),
                        Material.BEETROOT, 48, new ItemStack(Material.EMERALD, 8), "8x Szmaragd"),
                Quest.nagrodaMonety(5, "Dyniowy Zbiór", List.of("Dynie dobrze się sprzedają - zbuduj z nich niezłą farmę."),
                        Material.PUMPKIN, 32, 1500),
                Quest.przedmiot(6, "Mistrz Farmy", List.of("Wykorzystaj złote marchewki - dowód, że Twoja farma jest w pełni samowystarczalna."),
                        Material.GOLDEN_CARROT, 8, new ItemStack(Material.HONEY_BOTTLE, 12), "12x Butelka Miodu")
        ));

        // ŁOWCA - odblokowywana questem #8 Głównej Ścieżki (Ewoluujący Miecz). Trzyma się
        // Overworldu (potwory z Netheru/Endu to już domena Głównej Ścieżki, questy 24/27/35).
        questyKategorii.put("Łowca", List.of(
                Quest.przedmiot(1, "Początkujący", List.of("Nieumarli w nocy nie odpuszczają - obróć to na swoją korzyść."),
                        Material.ROTTEN_FLESH, 32, new ItemStack(Material.COOKED_BEEF, 16), "16x Pieczona Wołowina"),
                Quest.przedmiot(2, "Strzelec", List.of("Kości ze szkieletów przydadzą się na coś więcej niż mączkę kostną."),
                        Material.BONE, 16, new ItemStack(Material.BOW, 1), "1x Łuk"),
                Quest.przedmiot(3, "Nocny Marek", List.of("Pajęczyny z pająków są zaskakująco poszukiwane."),
                        Material.STRING, 10, new ItemStack(Material.EXPERIENCE_BOTTLE, 16), "16x Butelka EXP"),
                Quest.nagrodaMonety(4, "Prochowy Zbieracz", List.of("Creepery wybuchają prochem, nie tylko kraterami."),
                        Material.GUNPOWDER, 24, 800),
                Quest.przedmiot(5, "Pajęcze Oko", List.of("Zbierz oczy pająków - podstawa niejednej mikstury."),
                        Material.SPIDER_EYE, 12, new ItemStack(Material.GLASS_BOTTLE, 16), "16x Pusta Butelka"),
                Quest.przedmiot(6, "Nocna Zjawa", List.of("Fantomy nawiedzają tych, co za długo nie śpią - upoluj kilka."),
                        Material.PHANTOM_MEMBRANE, 6, new ItemStack(Material.ELYTRA, 1), "1x Elytra")
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
        // nierozróżnialne puste duplikaty (patrz KATEGORIE_GWIAZDY - diament stronicuje się
        // automatycznie, więc limit slotów już nie wymusza konsolidacji, ale zostały usunięte
        // wcześniej i nie ma powodu ich przywracać).
        questyKategorii.put("Mistrz Kilofa", new ArrayList<>());
        questyKategorii.put("Mistrz Miecza", new ArrayList<>());
    }

    /** Ikona/nazwa/opis kategorii bocznej - kolejność MUSI się zgadzać z SLOTY_DIAMENTU (patrz stronicowanie w otworzMenuQuestow). */
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

    /**
     * Kategoria odblokowuje się dopiero po ukończeniu danego questu Głównej Ścieżki -
     * "woda rozlewająca się na boki" od dolnego wierzchołka diamentu, zamiast wszystkiego
     * dostępnego od razu. Tylko kategorie z REALNĄ treścią questową mają tu wpis - pozostałe
     * ~16 ikon w diamencie (Drwal, Alchemik, Kowal, ...) to i tak puste placeholdery bez questów
     * (patrz questyKategorii/getOrDefault w otworzKategorie), więc gating byłby bez sensu,
     * dopóki ktoś nie doda im treści. Dopasowanie tematyczne:
     * - Górnictwo po queście 3 (stack kamienia w kieszeni - realnie zaczął już kopać).
     * - Hodowla po queście 7 (pierwsze duże zbiory - gracz ma już czym rozkręcić farmę).
     * - Łowca po queście 9 (Ewoluujący Miecz - pierwsza walka, plus fale zombie z
     *   Płaczącego Obsydianu, za nim).
     * - Rybak po queście 13 (Sniffer postawiony - wyspa na tyle ogarnięta, że gracz
     *   ma czas na poboczne hobby).
     * - Questy Specjalne po queście 30 (Nether pokonany) - prawdziwy endgame dla
     *   weteranów, zgodnie z własnym opisem kategorii.
     */
    private static final Map<String, Integer> WYMOG_ODBLOKOWANIA_KATEGORII = Map.of(
            "Górnictwo", 3,
            "Hodowla", 7,
            "Łowca", 9,
            "Rybak", 13,
            "Questy Specjalne", 30
    );

    /** Czy gracz ukończył quest Głównej Ścieżki wymagany do odblokowania danej kategorii (patrz WYMOG_ODBLOKOWANIA_KATEGORII). */
    private boolean kategoriaOdblokowana(Player player, String kategoria) {
        Integer wymaganyQuest = WYMOG_ODBLOKOWANIA_KATEGORII.get(kategoria);
        if (wymaganyQuest == null) return true;
        return postepyDlaKategorii(player, KATEGORIA_GLOWNA_SCIEZKA).contains(wymaganyQuest);
    }

    /** Czytelny tekst wymogu do lore zablokowanej ikony kategorii - "Ukończ <tytuł questu> (#N) w Głównej Ścieżce." */
    private String tekstWymoguOdblokowania(String kategoria) {
        Integer id = WYMOG_ODBLOKOWANIA_KATEGORII.get(kategoria);
        if (id == null) return "";
        String tytul = questyKategorii.getOrDefault(KATEGORIA_GLOWNA_SCIEZKA, List.of()).stream()
                .filter(q -> q.id() == id).findFirst().map(Quest::tytul).orElse("?");
        return "Ukończ \"" + tytul + "\" (#" + id + ") w Głównej Ścieżce.";
    }

    /**
     * Czy kategoria ma jakąkolwiek realną treść questową. ~16 ikon w diamencie (Drwal,
     * Alchemik, Kowal, Mag, Odkrywca, Mistrz Kilofa/Miecza...) to czyste, puste
     * placeholdery bez ani jednego questu - bez tego rozróżnienia kliknięcie w nie
     * otwierało całkowicie pustą siatkę bez wyjaśnienia. Patrz stworzIkoneKategorii/W_BUDOWIE.
     */
    private boolean kategoriaMaTresc(String kategoria) {
        return !questyKategorii.getOrDefault(kategoria, List.of()).isEmpty();
    }

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
        otworzMenuQuestow(player, zMenu, 0);
    }

    /**
     * strona > 0 tylko dla kategorii bocznych (patrz SLOTY_DIAMENTU/KATEGORII_NA_STRONE) -
     * Główna Ścieżka na SLOT_DOL_DIAMENTU jest PRZYPIĘTA i widoczna identycznie na każdej
     * stronie, bo to stały punkt startowy, a nie coś do stronicowania.
     */
    private void otworzMenuQuestow(Player player, boolean zMenu, int strona) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        String tytulMenu = strona == 0 ? "Kategorie Zadań" : "Kategorie Zadań (Strona " + (strona + 1) + ")";
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(tytulMenu, NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);

        // Główna Ścieżka na DOLNYM WIERZCHOŁKU diamentu - to punkt startowy dla nowych graczy,
        // reszta kategorii układa się nad nią jako romb, najszerzej w środku (patrz SLOTY_DIAMENTU/KATEGORIE_GWIAZDY).
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
        gui.setItem(SLOT_DOL_DIAMENTU, glownaSciezkaIkona);

        int startIndex = strona * KATEGORII_NA_STRONE;
        for (int i = 0; i < SLOTY_DIAMENTU.length && startIndex + i < KATEGORIE_GWIAZDY.size(); i++) {
            KategoriaGwiazdy k = KATEGORIE_GWIAZDY.get(startIndex + i);
            gui.setItem(SLOTY_DIAMENTU[i], stworzIkoneKategorii(player, k));
        }

        // Bez przycisku zamknięcia/powrotu - gracz po prostu wychodzi klawiszem Escape.
        if (strona > 0) gui.setItem(45, stworzPrzycisk(Material.ARROW, "Poprzednia Strona", NamedTextColor.YELLOW));
        if (startIndex + KATEGORII_NA_STRONE < KATEGORIE_GWIAZDY.size()) gui.setItem(53, stworzPrzycisk(Material.ARROW, "Następna Strona", NamedTextColor.YELLOW));

        player.openInventory(gui);
    }

    public void otworzKategorie(Player player, String nazwaKategorii, int strona) {
        List<Quest> questy = questyKategorii.getOrDefault(nazwaKategorii, new ArrayList<>());
        int[] sloty = slotyDlaKategorii(nazwaKategorii);

        String tytulMenu = "Strona " + (strona + 1) + " | " + nazwaKategorii;
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(tytulMenu, NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);

        Set<Integer> postepy = postepyDlaKategorii(player, nazwaKategorii);

        // sloty.length zamiast sztywnej 35 - Główna Ścieżka (SLOTY_WEZYK) ma teraz 23
        // sloty/stronę (wężyk z przerwą), reszta kategorii (slotySrodkowe) nadal 35.
        int startIndex = strona * sloty.length;
        for (int i = 0; i < sloty.length; i++) {
            if (startIndex + i < questy.size()) {
                StanQuestu stan = ustalStan(nazwaKategorii, questy, startIndex + i, postepy);
                gui.setItem(sloty[i], stworzIkoneQuesta(questy.get(startIndex + i), stan));
            }
        }

        gui.setItem(49, stworzPrzycisk(Material.DARK_OAK_DOOR, "Powrót do Kategorii", NamedTextColor.GOLD));
        if (strona > 0) gui.setItem(45, stworzPrzycisk(Material.ARROW, "Poprzednia Strona", NamedTextColor.YELLOW));
        if (startIndex + sloty.length < questy.size()) gui.setItem(53, stworzPrzycisk(Material.ARROW, "Następna Strona", NamedTextColor.YELLOW));

        player.openInventory(gui);
    }

    private void wypelnijTlo(Inventory gui) {
        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        for (int i = 0; i < 54; i++) gui.setItem(i, tlo);
    }

    private enum StanKategorii { DOSTEPNA, ZABLOKOWANA, W_BUDOWIE }

    /** Ikona Głównej Ścieżki - zawsze dostępna, jedyny wywołujący spoza pętli diamentu. */
    private ItemStack stworzIkoneKategorii(Material material, String nazwa, String opis) {
        return stworzIkoneKategoriiZeStanem(material, nazwa, opis, StanKategorii.DOSTEPNA, null);
    }

    /** Ustala stan (dostępna/zablokowana/w budowie) jednej ikony kategorii bocznej w diamencie i buduje jej item. */
    private ItemStack stworzIkoneKategorii(Player player, KategoriaGwiazdy k) {
        if (!kategoriaMaTresc(k.nazwa())) {
            return stworzIkoneKategoriiZeStanem(k.ikona(), k.nazwa(), k.opis(), StanKategorii.W_BUDOWIE, null);
        }
        if (!kategoriaOdblokowana(player, k.nazwa())) {
            return stworzIkoneKategoriiZeStanem(k.ikona(), k.nazwa(), k.opis(), StanKategorii.ZABLOKOWANA, tekstWymoguOdblokowania(k.nazwa()));
        }
        return stworzIkoneKategoriiZeStanem(k.ikona(), k.nazwa(), k.opis(), StanKategorii.DOSTEPNA, null);
    }

    /**
     * Buduje ikonę kategorii dla danego stanu - szary barwnik + kłódka w lore, gdy
     * zablokowana (patrz WYMOG_ODBLOKOWANIA_KATEGORII), albo barierka + "w budowie",
     * gdy kategoria nie ma jeszcze ani jednego questu (patrz kategoriaMaTresc) - bez
     * tego rozróżnienia kliknięcie w ~16 pustych ikon gwiazdy (Drwal, Alchemik, Kowal...)
     * otwierało zupełnie pustą siatkę bez wyjaśnienia. Nazwa kategorii w displayName
     * ZOSTAJE prawdziwa nawet zablokowana (nie "???" jak przy questach Głównej Ścieżki) -
     * onInventoryClick() odczytuje z niej nazwę kategorii do routingu, a poza tym sama
     * nazwa kategorii to nie spoiler.
     */
    private ItemStack stworzIkoneKategoriiZeStanem(Material material, String nazwa, String opis, StanKategorii stan, String dodatkowyTekst) {
        Material materialIkony = switch (stan) {
            case DOSTEPNA -> material;
            case ZABLOKOWANA -> Material.GRAY_DYE;
            case W_BUDOWIE -> Material.BARRIER;
        };
        NamedTextColor kolorNazwy = stan == StanKategorii.DOSTEPNA ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY;

        ItemStack item = new ItemStack(materialIkony);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, kolorNazwy, TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(opis, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (stan == StanKategorii.ZABLOKOWANA) {
            lore.add(Component.empty());
            lore.add(Component.text("🔒 Zablokowane", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(dodatkowyTekst, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else if (stan == StanKategorii.W_BUDOWIE) {
            lore.add(Component.empty());
            lore.add(Component.text("🚧 W budowie", NamedTextColor.YELLOW, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Wróć tu później - jeszcze nie ma tu zadań.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
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

    /**
     * Polskie nazwy materiałów do lore wymogów questa - bez tego "Wymaga: 8x OAK_LOG"
     * pokazywałoby surową nazwę enuma Bukkita zamiast "8x Kłoda Dębu". Tylko materiały,
     * które faktycznie występują jako WYMÓG (nie nagroda - te mają zawsze własne
     * nazwaNagrody) gdziekolwiek w questyKategorii - patrz nazwaMaterialu().
     */
    private static final Map<Material, String> NAZWY_MATERIALOW = Map.ofEntries(
            Map.entry(Material.COBBLESTONE, "Brukowiec"),
            Map.entry(Material.OAK_LOG, "Kłoda Dębu"),
            Map.entry(Material.BIRCH_SAPLING, "Sadzonka Brzozy"),
            Map.entry(Material.COAL, "Węgiel"),
            Map.entry(Material.CHARCOAL, "Węgiel Drzewny"),
            Map.entry(Material.STONE_HOE, "Kamienna Motyka"),
            Map.entry(Material.MELON_SLICE, "Plasterek Arbuza"),
            Map.entry(Material.WHEAT, "Pszenica"),
            Map.entry(Material.CARROT, "Marchewka"),
            Map.entry(Material.COOKIE, "Ciastko"),
            Map.entry(Material.ROTTEN_FLESH, "Zgniłe Mięso"),
            Map.entry(Material.RAW_IRON, "Surowe Żelazo"),
            Map.entry(Material.IRON_AXE, "Żelazna Siekiera"),
            Map.entry(Material.SNIFFER_EGG, "Jajo Sniffera"),
            Map.entry(Material.EMERALD, "Szmaragd"),
            Map.entry(Material.REDSTONE, "Redstone"),
            Map.entry(Material.LAPIS_LAZULI, "Lapis Lazuli"),
            Map.entry(Material.DIAMOND, "Diament"),
            Map.entry(Material.DIAMOND_PICKAXE, "Diamentowy Kilof"),
            Map.entry(Material.OBSIDIAN, "Obsydian"),
            Map.entry(Material.NETHERRACK, "Netherrack"),
            Map.entry(Material.BLAZE_ROD, "Różdżka Blaze'a"),
            Map.entry(Material.SOUL_SAND, "Duszowy Piasek"),
            Map.entry(Material.QUARTZ, "Kwarc Netherowy"),
            Map.entry(Material.GHAST_TEAR, "Łza Ghasta"),
            Map.entry(Material.NETHERITE_SCRAP, "Złom Netherytu"),
            Map.entry(Material.NETHERITE_INGOT, "Sztabka Netherytu"),
            Map.entry(Material.DIAMOND_SWORD, "Diamentowy Miecz"),
            Map.entry(Material.NETHERITE_PICKAXE, "Netherytowy Kilof"),
            Map.entry(Material.ENDER_PEARL, "Perła Endermana"),
            Map.entry(Material.PURPUR_BLOCK, "Blok Purpuru"),
            Map.entry(Material.CHORUS_FRUIT, "Owoc Chorusu"),
            Map.entry(Material.SHULKER_SHELL, "Skorupa Shulkera"),
            Map.entry(Material.PHANTOM_MEMBRANE, "Błona Fantoma"),
            Map.entry(Material.GOLDEN_CARROT, "Złota Marchewka"),
            Map.entry(Material.DRAGON_BREATH, "Oddech Smoka"),
            Map.entry(Material.NETHER_STAR, "Gwiazda Netheru"),
            Map.entry(Material.RAW_COPPER, "Surowa Miedź"),
            Map.entry(Material.RAW_GOLD, "Surowe Złoto"),
            Map.entry(Material.AMETHYST_SHARD, "Odłamek Ametystu"),
            Map.entry(Material.POTATO, "Ziemniak"),
            Map.entry(Material.BEETROOT, "Burak"),
            Map.entry(Material.PUMPKIN, "Dynia"),
            Map.entry(Material.BONE, "Kość"),
            Map.entry(Material.STRING, "Sznurek"),
            Map.entry(Material.GUNPOWDER, "Proch Strzelniczy"),
            Map.entry(Material.SPIDER_EYE, "Oko Pająka"),
            Map.entry(Material.WITHER_SKELETON_SKULL, "Czaszka Witherowego Szkieletu")
    );

    /** Polska nazwa materiału z NAZWY_MATERIALOW, a jeśli czegoś zabraknie w mapie - surowa nazwa enuma jako bezpieczny fallback zamiast wyjątku. */
    private static String nazwaMaterialu(Material material) {
        return NAZWY_MATERIALOW.getOrDefault(material, material.name());
    }

    /** "16x Kłoda Dębu, 16x Sadzonka Brzozy" - łączy kilka wymaganych materiałów w jeden czytelny tekst do lore. */
    private String opisWymogow(List<Wymog> wymogi) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wymogi.size(); i++) {
            if (i > 0) sb.append(", ");
            Wymog w = wymogi.get(i);
            sb.append(w.ilosc()).append("x ").append(w.nazwaWyswietlana() != null ? w.nazwaWyswietlana() : nazwaMaterialu(w.material()));
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

    /**
     * Nagroda questu 9 - zwykły CRYING_OBSIDIAN, ale otagowany CustomItemKeys.CUSTOM_ITEM_ID
     * (patrz {@link #CUSTOM_ID_PLACZACY_OBSYDIAN}), żeby onPlaceObsydian() odróżnił go od
     * dowolnego innego płaczącego obsydianu, jaki gracz mógłby kiedyś zdobyć/postawić.
     */
    private static ItemStack placzacyObsydian() {
        ItemStack item = new ItemStack(Material.CRYING_OBSIDIAN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Płaczący Obsydian Wezwania", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Postaw go na wyspie, by przywołać", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("5 fal zombie broniących Twojej bazy.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, CUSTOM_ID_PLACZACY_OBSYDIAN);
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
        boolean jestKategoriami = title.equals("Kategorie Zadań") || title.startsWith("Kategorie Zadań (Strona ");
        if (!jestKategoriami && !title.startsWith("Strona ")) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        if (jestKategoriami) {
            // "Kategorie Zadań" = strona 0, "Kategorie Zadań (Strona N)" = strona N-1 (patrz otworzMenuQuestow).
            int stronaKategorii = title.equals("Kategorie Zadań") ? 0 : Integer.parseInt(title.replaceAll("\\D+", "")) - 1;

            if (slot == 45 && stronaKategorii > 0) {
                otworzMenuQuestow(player, otwartoZMenu.getOrDefault(player.getUniqueId(), false), stronaKategorii - 1);
            }
            else if (slot == 53 && (stronaKategorii + 1) * KATEGORII_NA_STRONE < KATEGORIE_GWIAZDY.size()) {
                otworzMenuQuestow(player, otwartoZMenu.getOrDefault(player.getUniqueId(), false), stronaKategorii + 1);
            }
            else if (slot == SLOT_DOL_DIAMENTU) {
                otworzKategorie(player, KATEGORIA_GLOWNA_SCIEZKA, 0);
            }
            else if (event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.GRAY_STAINED_GLASS_PANE) {
                String kategoria = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getCurrentItem().getItemMeta().displayName());
                if (!kategoriaMaTresc(kategoria)) {
                    player.sendMessage(Component.text("Ta kategoria jest jeszcze w budowie - wróć później!", NamedTextColor.YELLOW));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
                if (!kategoriaOdblokowana(player, kategoria)) {
                    player.sendMessage(Component.text("Ta kategoria jest jeszcze zablokowana!", NamedTextColor.RED));
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
                otworzKategorie(player, kategoria, 0);
            }
        } else if (title.startsWith("Strona ")) {
            String[] parts = title.split("\\|");
            if (parts.length < 2) return;

            int strona = Integer.parseInt(parts[0].replace("Strona ", "").trim()) - 1;
            String kategoria = parts[1].trim();

            if (slot == 49) {
                // Główna Ścieżka i boczne kategorie mają wspólny "cofnij" - zawsze do diamentu głównego (strona 0).
                otworzMenuQuestow(player, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
            }
            else if (slot == 53 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona + 1);
            else if (slot == 45 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona - 1);
            else {
                int[] sloty = slotyDlaKategorii(kategoria);
                for (int i = 0; i < sloty.length; i++) {
                    if (slot == sloty[i]) {
                        List<Quest> questy = questyKategorii.get(kategoria);
                        int questIndex = (strona * sloty.length) + i;
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
     * 1/4/6/9 Głównej Ścieżki (kilof/siekiera/motyka/miecz), gdzie zamiast placeholdera
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
                    case 4 -> { tools.dajEwoluujacaSiekiere(player); return; }
                    case 6 -> { tools.dajEwoluujacaMotyke(player); return; }
                    // Brak "return" celowo - quest 9 poza mieczem dorzuca jeszcze zwykłą
                    // nagrodę (Płaczący Obsydian Wezwania) z listy q.nagrody() niżej.
                    case 9 -> tools.dajEwoluujacyMiecz(player);
                }
            }
            // Quest 8 ("Ciacho na Szczęście") - JEDYNA widoczna nagroda to podstawowa
            // skrzynka+klucz (mainplugins-crates, tier 1, słaby drop) - w odróżnieniu od
            // wreczSkrzynkeKamienMilowy() (cichy BONUS obok głównej nagrody na 18/20/30/40),
            // tu skrzynka JEST główną nagrodą, więc wracamy od razu i pomijamy q.nagrody().
            // Jeśli mainplugins-crates nie jest wgrany, spadamy na zwykłą listę q.nagrody()
            // (garść szmaragdów) niżej, żeby gracz nie skończył z pustymi rękami.
            if (q.id() == 8) {
                CrateService crateService = CoreAPI.getCrateService();
                if (crateService != null) {
                    var nieZmieszczone = player.getInventory().addItem(crateService.stworzSkrzynke(1), crateService.stworzKlucz());
                    nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
                    return;
                }
            }
        }
        // Dwa NIEZALEŻNE if (nie if/else) - Quest.przedmiotyIMonety() to jedyny wariant,
        // gdzie oba pola są naraz niepuste (monety + przedmioty w jednej nagrodzie).
        // Dla reszty questów jedno z nich zawsze jest puste/zero, więc zachowanie się
        // nie zmienia względem starego if/else.
        if (q.monetyNagrody() > 0) {
            CoreAPI.getEconomyService().dodajKase(player.getUniqueId(), q.monetyNagrody());
        }
        if (!q.nagrody().isEmpty()) {
            // Sprawdzenie firstEmpty()==-1 przed wywolaniem tej metody gwarantuje tylko
            // JEDEN wolny slot - questy z kilkoma roznymi przedmiotami nagrody naraz
            // (np. quest 3: mączka kostna + sadzonka) mogly wczesniej po cichu gubic druga
            // sztuke, jesli ta pierwsza zajela jedyny wolny slot. addItem() zwraca
            // niezmieszczone itemy - teraz zawsze ladujemy je na ziemie zamiast tracic.
            for (ItemStack item : q.nagrody()) {
                var nieZmieszczone = player.getInventory().addItem(item);
                nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
            }
        }
        // Quest 40 ("Mistrz Wyspy") dorzuca Beacon do trofeum - jedyny wyjątek, gdzie
        // ścieżka wręcza dwa przedmioty naraz, patrz komentarz w zaladujQuesty().
        if (kategoria.equals(KATEGORIA_GLOWNA_SCIEZKA) && q.id() == 40) {
            var nieZmieszczoneBeacon = player.getInventory().addItem(new ItemStack(Material.BEACON, 1));
            nieZmieszczoneBeacon.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        }
        if (kategoria.equals(KATEGORIA_GLOWNA_SCIEZKA)) {
            wreczSkrzynkeKamienMilowy(player, q.id());
        }
    }

    /**
     * Skrzynki (mainplugins-crates) na kamieniach milowych Głównej Ścieżki - 18/20/30/40.
     * Bez tego nowy gracz nie miałby ŻADNEGO organicznego sposobu na zdobycie skrzynki/klucza
     * (patrz komentarz przy CrateService) - questy to jedyne kontrolowane źródło. Tier rośnie
     * wraz z postępem (1 -> 1 -> 2 -> 3), zawsze skrzynka + klucz naraz, żeby dało się ją od
     * razu otworzyć. Cichy no-op, jeśli mainplugins-crates nie jest wgrany - patrz CrateService.
     */
    private void wreczSkrzynkeKamienMilowy(Player player, int questId) {
        int tier = switch (questId) {
            case 18, 20 -> 1;
            case 30 -> 2;
            case 40 -> 3;
            default -> 0;
        };
        if (tier == 0) return;

        CrateService crateService = CoreAPI.getCrateService();
        if (crateService == null) return;

        var nieZmieszczone = player.getInventory().addItem(crateService.stworzSkrzynke(tier), crateService.stworzKlucz());
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
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

    // ---- Płaczący Obsydian: fale zombie (nagroda questu 9 "Fundusz Obronny") ----

    /**
     * Rozpoznaje nagrodę questu 9 po CustomItemKeys.CUSTOM_ITEM_ID (patrz placzacyObsydian()) -
     * zwykły, kupiony/wydobyty płaczący obsydian NIE wywołuje fal. ignoreCancelled=true, żeby
     * np. IslandProtectionManager (postawienie poza granicami własnej wyspy) mógł zablokować
     * samo postawienie bloku bez odpalenia fal na lokacji, gdzie fizycznie nic nie stanęło.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlaceObsydian(BlockPlaceEvent event) {
        ItemStack reka = event.getItemInHand();
        if (!reka.hasItemMeta()) return;
        String customId = reka.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
        if (!CUSTOM_ID_PLACZACY_OBSYDIAN.equals(customId)) return;

        rozpocznijFaleZombie(event.getPlayer(), event.getBlock(), true);
    }

    /**
     * (Admin) /addfale - testowe odpalenie 5 fal pod nogami gracza, bez zużywania
     * questowego Płaczącego Obsydianu. prawdziwaNagroda=false, więc blok pod graczem
     * NIE znika po piątej fali i gracz NIE dostaje 200 monet ani finałowego lootu -
     * to czysto testowe narzędzie do sprawdzania balansu fal/lootu/paska, nie sposób
     * na farmienie nagrody questu 9 w kółko.
     */
    public void wywolajTestoweFale(Player player) {
        Block podNogami = player.getLocation().getBlock().getRelative(0, -1, 0);
        rozpocznijFaleZombie(player, podNogami, false);
    }

    /**
     * 5 fal zombie w stałych, z góry ustalonych odstępach (NIE czekamy, aż gracz zabije
     * poprzednią falę - prostsza i przewidywalna sekwencja, koniec zawsze po ~85s).
     * Zombie spawnują WYŁĄCZNIE na solidnym gruncie w promieniu bloku (patrz
     * znajdzMiejsceNaZiemi) - nigdy w powietrzu, i są otagowane sesją (kluczSesjiFali) +
     * dopisane do StanFali.zywoZombie, żeby pilnujSpadajacychZombie() mogło je teleportować
     * z powrotem, gdyby spadły za krawędź wyspy w otchłań. To zwykłe, nieuzbrojone zombie
     * (żadnego pancerza/broni), więc mają tylko domyślne wanilijskie dropy - świeży gracz
     * z drewnianym/kamiennym mieczem ma z tym realną szansę. KAŻDA fala (nie tylko ostatnia)
     * dorzuca małą premię lootu + 1x zgniłe mięso (5 fal x 1 = dokładnie 5, tyle ile wymaga
     * quest 10 "Egzamin Początkującego" - celowe powiązanie fabularne). prawdziwaNagroda
     * steruje TYLKO efektami "to jest realna nagroda questu 9": zniszczeniem bloku po
     * piątej fali i wypłatą 200 monet - patrz wywolajTestoweFale (/addfale).
     *
     * Pasek bossbara (StanFali) resetuje się do pełna na starcie każdej fali i zmniejsza
     * się o krok przy KAŻDYM zabitym zombie tej sesji (patrz onZombieDeath). Sesja
     * identyfikowana losowym UUID (nie graczem), żeby dwie osoby mogły odpalić własny
     * Płaczący Obsydian (albo /addfale) jednocześnie bez wzajemnego mieszania stanu.
     */
    private void rozpocznijFaleZombie(Player player, Block block, boolean prawdziwaNagroda) {
        block.getWorld().setTime(13000);
        player.sendMessage(Component.text("Płaczący Obsydian budzi się do życia... nadchodzą fale zombie!", NamedTextColor.DARK_RED, TextDecoration.BOLD));

        UUID sesjaId = UUID.randomUUID();
        StanFali stan = new StanFali(BossBar.bossBar(Component.text("Fala 1/5"), 1.0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10), block);
        aktywneFaleZombie.put(sesjaId, stan);
        player.showBossBar(stan.pasek);

        int[] rozmiaryFal = {2, 2, 3, 3, 4};
        List<List<ItemStack>> premieFal = List.of(
                List.of(new ItemStack(Material.BREAD, 8), new ItemStack(Material.ROTTEN_FLESH, 1)),
                List.of(new ItemStack(Material.ARROW, 12), new ItemStack(Material.ROTTEN_FLESH, 1)),
                List.of(new ItemStack(Material.SHIELD, 1), new ItemStack(Material.ROTTEN_FLESH, 1)),
                List.of(new ItemStack(Material.ARROW, 16), new ItemStack(Material.ROTTEN_FLESH, 1)),
                List.of(new ItemStack(Material.IRON_INGOT, 3), new ItemStack(Material.GOLDEN_APPLE, 1), new ItemStack(Material.ROTTEN_FLESH, 1))
        );
        long odstepMiedzyFalami = 20L * 15; // 15 sekund na falę

        for (int fala = 0; fala < rozmiaryFal.length; fala++) {
            int numerFali = fala + 1;
            int iloscZombie = rozmiaryFal[fala];
            List<ItemStack> premia = premieFal.get(fala);
            boolean ostatnia = numerFali == rozmiaryFal.length;

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                int zespawnowane = 0;
                for (int i = 0; i < iloscZombie; i++) {
                    Location miejsce = znajdzMiejsceNaZiemi(block, 6);
                    if (miejsce != null) {
                        Zombie zombie = block.getWorld().spawn(miejsce, Zombie.class, z -> {
                            z.setBaby(false);
                            z.getPersistentDataContainer().set(kluczSesjiFali, PersistentDataType.STRING, sesjaId.toString());
                        });
                        stan.zywoZombie.add(zombie.getUniqueId());
                        zespawnowane++;
                    }
                }
                // Reset paska do pełna na start tej fali - liczy TYLKO zombie faktycznie
                // zespawnowane teraz (jeśli znajdzMiejsceNaZiemi zawiodło kilka razy, pasek
                // i tak trafnie odzwierciedla realną liczbę przeciwników do zabicia).
                stan.numerFali = numerFali;
                stan.wFaliLacznie = zespawnowane;
                stan.pozostaliWFali = zespawnowane;
                odswiezPasekFali(stan);

                for (ItemStack item : premia) {
                    block.getWorld().dropItemNaturally(block.getLocation(), item);
                }
                if (player.isOnline()) {
                    player.sendMessage(Component.text("Fala " + numerFali + "/5 nadchodzi!", NamedTextColor.RED));
                }

                if (ostatnia) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (prawdziwaNagroda) {
                            block.setType(Material.AIR);
                            block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(Material.IRON_INGOT, 5));
                            block.getWorld().dropItemNaturally(block.getLocation(), new ItemStack(Material.GOLDEN_CARROT, 4));
                            if (player.isOnline()) {
                                CoreAPI.getEconomyService().dodajKase(player.getUniqueId(), 200);
                                player.sendMessage(Component.text("Ostatnia fala odparta! Obsydian rozpada się, zostawiając nagrodę i 200 monet.", NamedTextColor.GREEN, TextDecoration.BOLD));
                            }
                        } else if (player.isOnline()) {
                            player.sendMessage(Component.text("Ostatnia fala odparta! (test /addfale - bez nagrody)", NamedTextColor.GREEN, TextDecoration.BOLD));
                        }
                        if (player.isOnline()) player.hideBossBar(stan.pasek);
                        aktywneFaleZombie.remove(sesjaId);
                    }, 20L * 10); // 10 sekund na dobicie ostatnich zombie, zanim blok zniknie
                }
            }, odstepMiedzyFalami * numerFali);
        }
    }

    /** Odświeża tytuł i wypełnienie paska StanFali na podstawie pozostaliWFali/wFaliLacznie. */
    private void odswiezPasekFali(StanFali stan) {
        float postep = stan.wFaliLacznie == 0 ? 0f : (float) stan.pozostaliWFali / stan.wFaliLacznie;
        stan.pasek.progress(Math.max(0f, Math.min(1f, postep)));
        stan.pasek.name(Component.text("⚔ Fala " + stan.numerFali + "/5 - pozostało " + stan.pozostaliWFali + " zombie",
                NamedTextColor.RED, TextDecoration.BOLD));
    }

    /** Zmniejsza pasek aktywnej fali, gdy ginie zombie oznaczone kluczSesjiFali (patrz rozpocznijFaleZombie) - obce zombie ignorujemy. */
    @EventHandler
    public void onZombieDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        String sesjaStr = zombie.getPersistentDataContainer().get(kluczSesjiFali, PersistentDataType.STRING);
        if (sesjaStr == null) return;

        UUID sesjaId;
        try {
            sesjaId = UUID.fromString(sesjaStr);
        } catch (IllegalArgumentException e) {
            return;
        }
        StanFali stan = aktywneFaleZombie.get(sesjaId);
        if (stan == null) return;

        stan.zywoZombie.remove(zombie.getUniqueId());
        stan.pozostaliWFali = Math.max(0, stan.pozostaliWFali - 1);
        odswiezPasekFali(stan);
    }

    /**
     * Co sekundę sprawdza wszystkie żywe zombie aktywnych fal (StanFali.zywoZombie) - jeśli
     * któreś spadło ponad 10 bloków poniżej kotwicy (typowo: za krawędź wyspy w otchłań),
     * teleportuje je z powrotem na solidny grunt obok kotwicy (patrz znajdzMiejsceNaZiemi).
     * Przy okazji sprząta z zestawu zombie, które już zginęły gdzie indziej (np. od upadku,
     * zanim zdążyliśmy je złapać) - onZombieDeath by je i tak usunął, to tylko dodatkowe
     * zabezpieczenie przeciw wyciekowi pamięci, gdyby event się nie odpalił.
     */
    private void pilnujSpadajacychZombie() {
        for (StanFali stan : aktywneFaleZombie.values()) {
            if (stan.zywoZombie.isEmpty()) continue;
            for (UUID id : new ArrayList<>(stan.zywoZombie)) {
                Entity encja = Bukkit.getEntity(id);
                if (!(encja instanceof Zombie zombie) || zombie.isDead()) {
                    stan.zywoZombie.remove(id);
                    continue;
                }
                if (zombie.getLocation().getY() < stan.kotwica.getY() - 10) {
                    Location powrot = znajdzMiejsceNaZiemi(stan.kotwica, 6);
                    zombie.teleport(powrot != null ? powrot : stan.kotwica.getLocation().add(0.5, 1, 0.5));
                }
            }
        }
    }

    /**
     * Szuka losowego miejsca na solidnym gruncie (2 bloki powietrza nad nim) w promieniu
     * wokół bloku - zombie NIGDY nie spawnują w powietrzu. Zwraca null po 20 nieudanych
     * próbach (np. teren bez żadnego pasującego miejsca w zasięgu) - wołający po prostu
     * pomija tę sztukę zombie zamiast rzucać błędem.
     */
    private Location znajdzMiejsceNaZiemi(Block centrum, int promien) {
        Random random = new Random();
        for (int probka = 0; probka < 20; probka++) {
            int dx = random.nextInt(promien * 2 + 1) - promien;
            int dz = random.nextInt(promien * 2 + 1) - promien;
            for (int dy = -3; dy <= 3; dy++) {
                Block podstawa = centrum.getRelative(dx, dy, dz);
                Block powietrze1 = podstawa.getRelative(0, 1, 0);
                Block powietrze2 = podstawa.getRelative(0, 2, 0);
                if (podstawa.getType().isSolid() && powietrze1.getType().isAir() && powietrze2.getType().isAir()) {
                    return powietrze1.getLocation().add(0.5, 0, 0.5);
                }
            }
        }
        return null;
    }

    // ---- TytulService: tytuł na czacie za ukończenie questu 10 (patrz komentarz w interfejsie) ----

    /**
     * {@inheritDoc} Zero osobnej persystencji - to bezpośrednio ta sama flaga co ukończenie
     * questu 10 ("Egzamin Początkującego") w quests.yml. mainplugins-ranks (RankManager.onChat)
     * dokleja to PRZED własnym tagiem rangi, zamiast rejestrować drugi, konkurencyjny renderer.
     */
    @Override
    public Component tytulGracza(UUID uuid) {
        Map<String, Set<Integer>> kategorie = postepyGraczy.get(uuid);
        if (kategorie == null) return null;
        Set<Integer> glownaSciezka = kategorie.get(KATEGORIA_GLOWNA_SCIEZKA);
        if (glownaSciezka == null || !glownaSciezka.contains(10)) return null;
        return Component.text("[Początkujący] ", NamedTextColor.GRAY, TextDecoration.BOLD);
    }
}