package elo.mainplugins.quests;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.MarketService;
import elo.mainplugins.core.api.QuestService;
import elo.mainplugins.core.api.ToolsService;
import elo.mainplugins.core.api.TytulService;
import elo.mainplugins.core.util.CustomItemKeys;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
 * /quest w całości - Menu Główne ("Kategorie Zadań") to wężyk narysowany ręcznie na pełnym
 * ekwipunku (patrz SLOTY_KATEGORII_BOCZNYCH), pierwszy slot to zawsze Główna Ścieżka
 * (SLOT_GLOWNEJ_SCIEZKI_W_MENU - "zacznij tutaj"), reszta to 16 kategorii bocznych. Ten sam
 * mechanizm "przynieś przedmiot, oddaj, dostań nagrodę" dla wszystkich kategorii. Zero
 * zależności od zewnętrznych pluginów (bez NPC/Citizens) - to świadomy powrót do
 * prostszego modelu, patrz komentarz w pom.xml tego modułu.
 *
 * Główna Ścieżka (KATEGORIA_GLOWNA_SCIEZKA) - jedyna kategoria, w której zadania
 * odblokowują się PO KOLEI (patrz ustalStan) i renderuje się jako wężyk-spirala
 * (SLOTY_WEZYK_C - ten sam kształt co kategorie boczne, patrz niżej). Kategorie boczne =
 * zwykłe zadania poboczne w dowolnej kolejności, renderowane tą samą spiralą, tak samo jak
 * "Questy Specjalne" - to po prostu kolejna kategoria z trudniejszą/rzadszą treścią, bez
 * specjalnej logiki.
 */
public class QuestManager implements Listener, TytulService, QuestService {

    /**
     * PRZEDMIOT - klasyczne "przynieś N sztuk materiału (jednego lub kilku naraz), zostaje
     * zabrane" (domyślne, większość questów).
     * DARMOWY - brak wymogu, kliknięcie od razu zdaje quest (np. "sprawdź spawn").
     * MONETY - zamiast przedmiotów, prog to koszt w monetach (EconomyService).
     * NARZEDZIE - jak PRZEDMIOT, ale NIE zabiera przedmiotu po zdaniu. Sprawdza samo
     * POSIADANIE konkretnego wanilijskiego Materiału (np. quest 6 - "masz w ekwipunku
     * kamienną motykę?") - zwykłe removeItem() by tu ZABRAŁO graczowi jedyny egzemplarz
     * jako "zapłatę" za quest, stąd osobny typ. NIE służy już do weryfikacji tieru
     * ewoluujących narzędzi (siekiera/motyka/miecz/kilof) - żadne z nich nie zmienia już
     * realnego Materiału przy levelowaniu (patrz *SkillManager#materialOverride w
     * mainplugins-tools), więc "tier" sprawdza się przez POZIOM (patrz niżej), nie Material.
     * POZIOM_KILOFA/POZIOM_SIEKIERY/POZIOM_MIECZA - prog to minimalny poziom (1-100) danego
     * narzędzia (ToolsService.poziomKilofa/poziomSiekiery/poziomMiecza - NAJWYŻSZY poziom
     * wśród WSZYSTKICH trzymanych egzemplarzy tego typu). Każde z tych trzech narzędzi ma
     * WŁASNY silnik poziomowania (Pickaxe/Axe/SwordSkillManager) - stąd osobne wymogi
     * zamiast jednego ogólnego "poziom narzędzia". Motyka na razie nie ma odpowiednika -
     * żaden quest jeszcze nie sprawdza jej poziomu.
     * OFERTA_NA_TARGU - sprawdza (MarketService.maAktywnaOferte) czy gracz ma choć jedną
     * AKTYWNĄ ofertę na rynku graczy (mainplugins-market) w chwili kliknięcia - nic nie
     * zabiera, quest tylko potwierdza, że gracz faktycznie czegoś tam wystawił.
     */
    private enum TypWymogu { PRZEDMIOT, DARMOWY, MONETY, NARZEDZIE, POZIOM_KILOFA, POZIOM_SIEKIERY, POZIOM_MIECZA, OFERTA_NA_TARGU }

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

        /** Jak {@link #poziomKilofa}, ale siekiera - patrz TypWymogu.POZIOM_SIEKIERY. */
        static Quest poziomSiekiery(int id, String tytul, List<String> opis, int minPoziom, ItemStack nagroda, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.POZIOM_SIEKIERY, List.of(), minPoziom, List.of(nagroda), nazwaNagrody, 0);
        }

        /** Jak {@link #poziomKilofa}, ale miecz - patrz TypWymogu.POZIOM_MIECZA. */
        static Quest poziomMiecza(int id, String tytul, List<String> opis, int minPoziom, ItemStack nagroda, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.POZIOM_MIECZA, List.of(), minPoziom, List.of(nagroda), nazwaNagrody, 0);
        }

        /** Quest "wystaw coś na rynku graczy" - patrz TypWymogu.OFERTA_NA_TARGU. */
        static Quest wymagaOfertyNaTargu(int id, String tytul, List<String> opis, ItemStack nagroda, String nazwaNagrody) {
            return new Quest(id, tytul, opis, TypWymogu.OFERTA_NA_TARGU, List.of(), 0, List.of(nagroda), nazwaNagrody, 0);
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
     * Wężyk-spirala, 29 slotów - kolejność w tablicy = kolejność wizualna (i, dla Głównej
     * Ścieżki, kolejność odblokowywania kolejnych zadań, patrz ustalStan). Ten sam kształt
     * dla Głównej Ścieżki i wszystkich kategorii bocznych (patrz otworzKategorie) -
     * narysowany ręcznie na pełnym 54-slotowym ekwipunku, stąd sloty przy samej krawędzi
     * (0-8...) też się tu pojawiają. Wszystko poza tą tablicą dostaje czarne szkło
     * automatycznie (patrz wypelnijPrzerwy/panelCzarny) - nie ma osobnej tablicy "przerw"
     * do ręcznego przeliczania przy każdej zmianie kształtu.
     */
    private static final int[] SLOTY_WEZYK_C = {
            0, 1, 2, 3, 4, 5, 6, 7, 8,
            17, 26, 23, 22, 25, 24, 21, 19, 18,
            20, 27, 36, 37, 38, 39, 40, 42, 41,
            44, 43
    };

    /**
     * Menu Główne ("Kategorie Zadań") - kształt narysowany ręcznie, 17 slotów na pełnym
     * ekwipunku. Pierwszy slot to zawsze Główna Ścieżka (SLOT_GLOWNEJ_SCIEZKI_W_MENU,
     * "zacznij tutaj"), pozostałe 16 to kategorie boczne w kolejności z KATEGORIE_GWIAZDY
     * (stąd lista tam ma dokładnie 16 pozycji - tyle ile tu slotów). Wszystko poza tymi
     * slotami dostaje czarne szkło (patrz wypelnijPrzerwy/panelCzarny), tak samo jak w
     * Głównej Ścieżce.
     */
    private static final int SLOT_GLOWNEJ_SCIEZKI_W_MENU = 22;

    private static final int[] SLOTY_KATEGORII_BOCZNYCH = {
            31, 49, 40, 13, 21, 11, 29, 19,
            28, 37, 23, 15, 33, 25, 34, 43
    };

    private static final String KATEGORIA_GLOWNA_SCIEZKA = "Główna Ścieżka";

    // Tag CustomItemKeys.CUSTOM_ITEM_ID trofeum questu 9 - patrz DungeonManager (mainplugins-dungeons),
    // które wręcza je przy zabiciu "Władcy Lochu".
    private static final String CUSTOM_ID_TROFEUM_LOCHU = "DUNGEON_TROFEUM_WLADCA_LOCHU";

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
        // GŁÓWNA ŚCIEŻKA - 40 zadań PO KOLEI (patrz ustalStan), od
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
        // przedmioty (ziemia), nie kolejne narzędzie. Quest 9 ("Pierwszy Loch") wymaga
        // przyniesienia Serca Władcy Lochu - custom-tagowanego trofeum (patrz
        // CUSTOM_ID_TROFEUM_LOCHU), które DungeonManager (mainplugins-dungeons) wręcza
        // przy zabiciu "Władcy Lochu" (/tpdun albo /tpboss). To pierwsze zetknięcie
        // gracza z systemem lochów w grze - a skoro boss płaci $500 + klucz do skrzynki
        // przy KAŻDYM zabiciu (nie tylko pierwszym), quest zostawia graczowi gotowy,
        // powtarzalny cel na długo po ukończeniu Głównej Ścieżki.
        //
        // Późniejsze questy tych narzędzi (12, 21, 30, 31) NIE dają ich ponownie - żadne z
        // czterech duszozłączonych narzędzi (kilof/siekiera/motyka/miecz) nie zmienia już
        // realnego Materiału przy levelowaniu (patrz *SkillManager#materialOverride w
        // mainplugins-tools - stały Material, tylko statystyki rosną z poziomem), więc
        // "czy narzędzie jest wystarczająco mocne" sprawdza się przez POZIOM (1-100), nie
        // przez Material/tier: 12 i 21 to POZIOM_SIEKIERY/POZIOM_KILOFA w pierwszej połowie
        // ścieżki, 30 i 31 to POZIOM_MIECZA/POZIOM_KILOFA przy zamknięciu wątku Netheru.
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
                Quest.przedmiotCustom(9, "Pierwszy Loch", List.of("Udaj się do lochu (/tpdun) i pokonaj Władcę Lochu.", "Przynieś dowód zwycięstwa - jego serce wciąż bije."),
                        Material.HEART_OF_THE_SEA, 1, CUSTOM_ID_TROFEUM_LOCHU, "Serce Władcy Lochu",
                        new ItemStack(Material.WOODEN_SWORD, 1), "1x Ewoluujący Miecz"),
                Quest.przedmiot(10, "Egzamin Początkującego", List.of("Sprzedaj dowód stoczonych walk z nieumarłymi.", "Kamień milowy - pierwszy etap za Tobą!"),
                        Material.ROTTEN_FLESH, 5, trofeum(Material.PLAYER_HEAD, "Głowa Początkującego", "Za pierwsze pokonane zombie.", "Odblokowuje tytuł \"Początkujący\" na czacie!"), "Trofeum: Głowa Początkującego + Tytuł"),

                Quest.przedmioty(11, "Piaskowy Mozół", List.of("Zbierz piasek i żwir ręcznie - żmudne zajęcie.",
                                "Na szczęście istnieje na to lepszy sposób..."),
                        List.of(Wymog.zwykly(Material.SAND, 64), Wymog.zwykly(Material.GRAVEL, 64)),
                        List.of(GeneratorKruchychManager.stworzGenerator(), GeneratorKruchychManager.stworzKsiazkaPrzewodnik()),
                        "1x Generator Kruchych Surowców + Przewodnik"),
                Quest.poziomSiekiery(12, "Zbrojmistrz", List.of("Wbij swojej siekierze 15 poziom."),
                        15, new ItemStack(Material.IRON_CHESTPLATE, 1), "1x Żelazny Napierśnik"),
                Quest.przedmiot(13, "Nad Wodą", List.of("Twoja wyspa stoi już na solidnych nogach - czas na hobby.", "Zbierz wodorosty rosnące przy brzegu."),
                        Material.KELP, 32, new ItemStack(Material.TORCHFLOWER_SEEDS, 8), "8x Nasiona Kwiatu Pochodni"),
                Quest.przedmiot(14, "Pierwsze Zakupy", List.of("Zgromadź szmaragdy na zakupy w sklepie w gwieździe."),
                        Material.EMERALD, 10, new ItemStack(Material.EXPERIENCE_BOTTLE, 10), "10x Butelka Doświadczenia"),
                Quest.przedmiot(15, "Prąd w Ścianach", List.of("Zbierz redstone pod pierwsze mechanizmy."),
                        Material.REDSTONE, 32, new ItemStack(Material.PISTON, 8), "8x Tłok"),
                Quest.przedmiot(16, "Kowal Wyspy", List.of("Zbierz lapis lazuli na zapłatę dla kowala.", "Odblokowuje /warp kowal!"),
                        Material.LAPIS_LAZULI, 32, new ItemStack(Material.ANVIL, 1), "1x Kowadło + odblokowanie /warp kowal"),
                Quest.zaMonety(17, "Skarbnik", List.of("Wpłać spory depozyt do banku wyspy."),
                        5000, new ItemStack(Material.GOLD_INGOT, 10), "10x Sztabka Złota (odsetki)"),
                Quest.zaMonety(18, "Pierwsza Inwestycja", List.of("Zainwestuj w prawdziwą infrastrukturę wyspy.",
                                "Ten sam Generator Bruku, co w sklepie - tu zdobędziesz go quest'owo."),
                        8000, GeneratorBrukuManager.stworzGenerator(), "1x Generator Bruku"),
                Quest.wymagaOfertyNaTargu(19, "Kupiec Wyspy", List.of("Wystaw dowolny przedmiot na Targu (/targ wystaw <cena>).", "Ekonomia wyspy to nie tylko sklep w gwieździe."),
                        new ItemStack(Material.DIAMOND, 1), "1x Diament"),
                Quest.przedmiot(20, "Filar Wyspy", List.of("Udowodnij, że Twoja wyspa stoi na solidnych fundamentach.", "Kamień milowy - połowa ścieżki za Tobą!"),
                        Material.DIAMOND, 32, trofeum(Material.PLAYER_HEAD, "Głowa Górnika", "Za setki wykopanych bloków."), "Trofeum: Głowa Górnika"),

                Quest.poziomKilofa(21, "Diamentowa Żyła", List.of("Wbij swojemu kilofowi 30 poziom."),
                        30, List.of(new ItemStack(Material.DIAMOND_BLOCK, 1)), "1x Blok Diamentu"),
                Quest.przedmiot(22, "Obsydianowy Mur", List.of("Zbierz obsydian pod portal do Netheru."),
                        Material.OBSIDIAN, 10, new ItemStack(Material.FLINT_AND_STEEL, 1), "1x Krzesiwo"),
                Quest.przedmiot(23, "Za Bramą", List.of("Wejdź do Netheru i zbierz netherrack."),
                        Material.NETHERRACK, 16, new ItemStack(Material.SOUL_TORCH, 16), "16x Duszowa Pochodnia"),
                Quest.nagrodaMonety(24, "Łowca Blaze'ów", List.of("Zapoluj na blaze w Netherowej twierdzy."),
                        Material.BLAZE_ROD, 8, 1500),
                Quest.przedmiot(25, "Dusza Netheru", List.of("Zbierz duszowy piasek z Netheru."),
                        Material.SOUL_SAND, 32, new ItemStack(Material.SOUL_LANTERN, 1), "1x Duszowa Latarnia"),
                Quest.przedmiot(26, "Kwarcowy Górnik", List.of("Wydobądź kwarc netherowy."),
                        Material.QUARTZ, 32, new ItemStack(Material.GLOWSTONE_DUST, 16), "16x Blask Poświaty"),
                Quest.przedmiot(27, "Pogromca Ghastów", List.of("Zapoluj na ghasty i zbierz ich łzy."),
                        Material.GHAST_TEAR, 4, new ItemStack(Material.FIRE_CHARGE, 8), "8x Ognista Kula"),
                Quest.przedmiot(28, "Netherytowy Traker", List.of("Znajdź złom netherytu w głębi Netheru."),
                        Material.NETHERITE_SCRAP, 4, new ItemStack(Material.GOLD_INGOT, 4), "4x Sztabka Złota"),
                Quest.przedmiot(29, "Kowal Netherytu", List.of("Wykuj pierwszą sztabkę netherytu w kuźni."),
                        Material.NETHERITE_INGOT, 1, new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1), "1x Szablon Kowalski"),
                Quest.poziomMiecza(30, "Pogromca Netheru", List.of("Wróć żywy z Netheru z mieczem wbitym na 50 poziom.", "Kamień milowy - Nether zdobyty!"),
                        50, trofeum(Material.WITHER_SKELETON_SKULL, "Głowa Wojownika Netheru", "Za przetrwanie Netheru."), "Trofeum: Głowa Wojownika Netheru"),

                Quest.poziomKilofa(31, "Netherytowy Rycerz", List.of("Wbij swojemu kilofowi 60 poziom."),
                        60, List.of(new ItemStack(Material.PHANTOM_MEMBRANE, 4)), "4x Błona Fantoma"),
                Quest.przedmiot(32, "Brama do Endu", List.of("Zgromadź perły endermana na oczy endera."),
                        Material.ENDER_PEARL, 12, new ItemStack(Material.ENDER_EYE, 4), "4x Oko Endera"),
                Quest.przedmiot(33, "Purpurowy Architekt", List.of("Zbierz purpurowe bloki z miast Endu."),
                        Material.PURPUR_BLOCK, 32, new ItemStack(Material.END_ROD, 8), "8x Pręt Endu"),
                Quest.przedmiot(34, "Owoc Chorusu", List.of("Zbierz owoce chorusu w Endzie."),
                        Material.CHORUS_FRUIT, 32, new ItemStack(Material.EXPERIENCE_BOTTLE, 24), "24x Butelka Doświadczenia"),
                Quest.przedmiot(35, "Łowca Shulkerów", List.of("Pokonaj shulkery w miastach Endu."),
                        Material.SHULKER_SHELL, 4, new ItemStack(Material.SHULKER_BOX, 1), "1x Shulker Box"),
                Quest.przedmiot(36, "Skrzydła Wolności", List.of("Zbierz błony fantomów na coś specjalnego."),
                        Material.PHANTOM_MEMBRANE, 4, new ItemStack(Material.ELYTRA, 1), "1x Elytra"),
                Quest.przedmiot(37, "Ostatni Krok", List.of("Przygotuj się na finałową walkę - zbierz złote marchewki."),
                        Material.GOLDEN_CARROT, 16, new ItemStack(Material.GOLDEN_APPLE, 4), "4x Złote Jabłko"),
                Quest.przedmiot(38, "Smoczy Oddech", List.of("Zbierz oddech smoka podczas walki z Enderdragonem."),
                        Material.DRAGON_BREATH, 8, new ItemStack(Material.NETHER_STAR, 1), "1x Gwiazda Netheru"),
                Quest.przedmiot(39, "Mistrz Farmera", List.of("Udowodnij, że Twoja farma stoi na najwyższym poziomie."),
                        Material.MELON_SLICE, 64, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1), "1x Notch Apple"),
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

        // "Mistrz Siekiery/Motyki/Łopaty/Kilofa/Miecza" celowo NIE ma w KATEGORIE_GWIAZDY -
        // to były czyste, nierozróżnialne puste duplikaty, a kilof/miecz mają już własne,
        // prawdziwe drzewka umiejętności w mainplugins-tools, więc osobna pusta kategoria
        // questowa byłaby zbędna. Menu Główne ma tylko 17 slotów (wężyk narysowany ręcznie -
        // patrz SLOTY_KATEGORII_BOCZNYCH), więc KATEGORIE_GWIAZDY trzyma się świadomie
        // krótkiej listy 16 kategorii zamiast wszystkich możliwych pomysłów na przyszłość.
    }

    /** Ikona/nazwa/opis kategorii bocznej - kolejność MUSI się zgadzać z SLOTY_KATEGORII_BOCZNYCH (patrz otworzMenuQuestow). */
    private record KategoriaGwiazdy(Material ikona, String nazwa, String opis) {}

    /**
     * DOKŁADNIE 16 pozycji - tyle samo, ile slotów zostaje w Menu Głównym po odjęciu
     * Głównej Ścieżki (17 slotów wężyka - 1 = 16, patrz SLOTY_KATEGORII_BOCZNYCH). Górnictwo/
     * Hodowla/Łowca/Rybak/Questy Specjalne mają realną treść questową (patrz
     * questyKategorii/WYMOG_ODBLOKOWANIA_KATEGORII) - reszta to placeholdery na przyszłość.
     * Świadomie WYCIĘTE z tej listy (żeby zmieścić się w 17 slotach, patrz rozmowa przy
     * wprowadzaniu ręcznie rysowanego kształtu menu): Kowal (dublował system tworzenia/
     * levelowania narzędzi z mainplugins-tools), Wojownik i Zabójca (dublowały Łowcę -
     * trzy kategorie "walka z potworami" naraz), Handlarz (dubluje cały moduł
     * mainplugins-market), Zbieracz (zbyt ogólny, dublował właściwie każdą inną kategorię
     * zbierania surowców), Mistrz Kilofa/Miecza (patrz komentarz w zaladujQuesty()).
     */
    private static final List<KategoriaGwiazdy> KATEGORIE_GWIAZDY = List.of(
            new KategoriaGwiazdy(Material.IRON_PICKAXE, "Górnictwo", "Zadania w kopalni"),
            new KategoriaGwiazdy(Material.WHEAT, "Hodowla", "Zadania rolnicze"),
            new KategoriaGwiazdy(Material.BOW, "Łowca", "Zadania z potworami"),
            new KategoriaGwiazdy(Material.OAK_LOG, "Drwal", "Zadania z drewnem"),
            new KategoriaGwiazdy(Material.FISHING_ROD, "Rybak", "Zadania wędkarskie"),
            new KategoriaGwiazdy(Material.BREWING_STAND, "Alchemik", "Warzenie mikstur"),
            new KategoriaGwiazdy(Material.COOKED_BEEF, "Kucharz", "Zadania kulinarne"),
            new KategoriaGwiazdy(Material.BRICKS, "Budowniczy", "Budowa wyspy"),
            new KategoriaGwiazdy(Material.ENCHANTING_TABLE, "Mag", "Zaklęcia"),
            new KategoriaGwiazdy(Material.COMPASS, "Odkrywca", "Eksploracja mapy"),
            new KategoriaGwiazdy(Material.PORKCHOP, "Rzeźnik", "Zdobywanie mięsa"),
            new KategoriaGwiazdy(Material.OAK_SAPLING, "Ogrodnik", "Sadzenie drzew"),
            new KategoriaGwiazdy(Material.DIAMOND, "Jubiler", "Cenne kruszce"),
            new KategoriaGwiazdy(Material.GOLD_NUGGET, "Złodziej", "Kradzież (Zadania)"),
            new KategoriaGwiazdy(Material.REDSTONE, "Inżynier", "Mechanizmy"),
            new KategoriaGwiazdy(Material.NETHER_STAR, "Questy Specjalne", "Trudne wyzwania dla weteranów")
    );

    /**
     * Kategoria odblokowuje się dopiero po ukończeniu danego questu Głównej Ścieżki -
     * "woda rozlewająca się w bok" od pierwszego slotu wężyka, zamiast wszystkiego
     * dostępnego od razu. Tylko kategorie z REALNĄ treścią questową mają tu wpis - pozostałe
     * 11 ikon w menu (Drwal, Alchemik, Kucharz, ...) to i tak puste placeholdery bez questów
     * (patrz questyKategorii/getOrDefault w otworzKategorie), więc gating byłby bez sensu,
     * dopóki ktoś nie doda im treści. Dopasowanie tematyczne:
     * - Górnictwo po queście 3 (stack kamienia w kieszeni - realnie zaczął już kopać).
     * - Hodowla po queście 7 (pierwsze duże zbiory - gracz ma już czym rozkręcić farmę).
     * - Łowca po queście 9 (Ewoluujący Miecz - pierwszy loch pokonany, gracz jest
     *   już gotowy na realną walkę).
     * - Rybak po queście 13 (Nad Wodą - wyspa na tyle ogarnięta, że gracz ma czas na
     *   poboczne hobby).
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
     * Czy kategoria ma jakąkolwiek realną treść questową. 11 ikon w menu (Drwal, Alchemik,
     * Kucharz, Mag, Odkrywca...) to czyste, puste placeholdery bez ani jednego questu - bez
     * tego rozróżnienia kliknięcie w nie otwierało całkowicie pustą siatkę bez wyjaśnienia.
     * Patrz stworzIkoneKategorii/W_BUDOWIE.
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

    // ---- GUI ----

    public void otworzMenuQuestow(Player player, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Kategorie Zadań", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);
        wypelnijPrzerwy(gui, new int[]{SLOT_GLOWNEJ_SCIEZKI_W_MENU}, SLOTY_KATEGORII_BOCZNYCH);

        // Główna Ścieżka na PIERWSZYM slocie wężyka - to punkt startowy dla nowych
        // graczy, reszta kategorii ciągnie się od niej w prawo (patrz SLOTY_KATEGORII_BOCZNYCH/KATEGORIE_GWIAZDY).
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
        gui.setItem(SLOT_GLOWNEJ_SCIEZKI_W_MENU, glownaSciezkaIkona);

        // 16 slotów na 16 kategorii bocznych - wszystkie mieszczą się naraz, bez stronicowania.
        for (int i = 0; i < SLOTY_KATEGORII_BOCZNYCH.length && i < KATEGORIE_GWIAZDY.size(); i++) {
            KategoriaGwiazdy k = KATEGORIE_GWIAZDY.get(i);
            gui.setItem(SLOTY_KATEGORII_BOCZNYCH[i], stworzIkoneKategorii(player, k));
        }

        // Bez przycisku zamknięcia/powrotu - gracz po prostu wychodzi klawiszem Escape.
        player.openInventory(gui);
    }

    public void otworzKategorie(Player player, String nazwaKategorii, int strona) {
        List<Quest> questy = questyKategorii.getOrDefault(nazwaKategorii, new ArrayList<>());
        int[] sloty = SLOTY_WEZYK_C;

        String tytulMenu = "Strona " + (strona + 1) + " | " + nazwaKategorii;
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(tytulMenu, NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);
        // Każda kategoria renderuje się jako ten sam wężyk-spirala (SLOTY_WEZYK_C), więc
        // przerwy liczą się tak samo dla wszystkich.
        wypelnijPrzerwy(gui, sloty);

        Set<Integer> postepy = postepyDlaKategorii(player, nazwaKategorii);

        // sloty.length zamiast sztywnej liczby - SLOTY_WEZYK_C ma 29 slotów/stronę.
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

    /** Czarne szkło dla świadomych przerw wężyka (menu i Główna Ścieżka) - odróżnia je od szarej ramki GUI z wypelnijTlo(). */
    private ItemStack panelCzarny() {
        ItemStack panel = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = panel.getItemMeta();
        meta.displayName(Component.empty());
        panel.setItemMeta(meta);
        return panel;
    }

    /**
     * Czarne szkło na KAŻDYM slocie spoza podanych ścieżek - uniwersalne dla dowolnego
     * ręcznie narysowanego kształtu wężyka, więc nie trzeba osobno przeliczać "przerw"
     * za każdym razem, gdy kształt się zmienia. Wywołuj PRZED ustawieniem ikon questów/
     * kategorii, bo inaczej nadpisze je czarnym szkłem.
     */
    private void wypelnijPrzerwy(Inventory gui, int[]... sciezki) {
        Set<Integer> sciezka = new HashSet<>();
        for (int[] tablica : sciezki) {
            for (int slot : tablica) sciezka.add(slot);
        }
        for (int slot = 0; slot < 54; slot++) {
            if (!sciezka.contains(slot)) gui.setItem(slot, panelCzarny());
        }
    }

    private enum StanKategorii { DOSTEPNA, ZABLOKOWANA, W_BUDOWIE }

    /** Ikona Głównej Ścieżki - zawsze dostępna, jedyny wywołujący spoza pętli wężyka menu. */
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
     * tego rozróżnienia kliknięcie w 11 pustych ikon gwiazdy (Drwal, Alchemik, Kucharz...)
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
            Map.entry(Material.SAND, "Piasek"),
            Map.entry(Material.GRAVEL, "Żwir"),
            Map.entry(Material.IRON_AXE, "Żelazna Siekiera"),
            Map.entry(Material.KELP, "Wodorosty"),
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
                case POZIOM_SIEKIERY -> "Wymaga: siekiery na poziomie " + (int) q.prog();
                case POZIOM_MIECZA -> "Wymaga: miecza na poziomie " + (int) q.prog();
                case OFERTA_NA_TARGU -> "Wymaga: aktywnej oferty na Targu (/targ wystaw)";
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
        boolean jestKategoriami = title.equals("Kategorie Zadań");
        if (!jestKategoriami && !title.startsWith("Strona ")) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        if (jestKategoriami) {
            if (slot == SLOT_GLOWNEJ_SCIEZKI_W_MENU) {
                otworzKategorie(player, KATEGORIA_GLOWNA_SCIEZKA, 0);
            }
            else if (event.getCurrentItem() != null
                    && event.getCurrentItem().getType() != Material.GRAY_STAINED_GLASS_PANE
                    && event.getCurrentItem().getType() != Material.BLACK_STAINED_GLASS_PANE) {
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
                // Główna Ścieżka i boczne kategorie mają wspólny "cofnij" - zawsze do Menu Głównego (strona 0).
                otworzMenuQuestow(player, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
            }
            else if (slot == 53 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona + 1);
            else if (slot == 45 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona - 1);
            else {
                int[] sloty = SLOTY_WEZYK_C;
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
            case POZIOM_SIEKIERY -> {
                ToolsService tools = CoreAPI.getToolsService();
                yield tools != null && tools.poziomSiekiery(player) >= q.prog();
            }
            case POZIOM_MIECZA -> {
                ToolsService tools = CoreAPI.getToolsService();
                yield tools != null && tools.poziomMiecza(player) >= q.prog();
            }
            case OFERTA_NA_TARGU -> {
                MarketService market = CoreAPI.getMarketService();
                yield market != null && market.maAktywnaOferte(player.getUniqueId());
            }
            case PRZEDMIOT, NARZEDZIE -> q.wymogi().stream().allMatch(w -> posiadaWymaganaIlosc(player, w));
        };

        if (spelnionyWymog) {
            switch (q.typWymogu()) {
                case DARMOWY, NARZEDZIE, POZIOM_KILOFA, POZIOM_SIEKIERY, POZIOM_MIECZA, OFERTA_NA_TARGU -> {} // te typy tylko sprawdzają - nic nie zabierają graczowi
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

            // Quest 1 ("Witaj na Wyspie") to pierwsza rzecz, jaką nowy gracz robi na
            // serwerze - zamiast zwykłego powrotu do GUI, zamykamy ekwipunek (zostaje
            // sam pulpit wyspy) i witamy dużym tytułem + fanfarą, żeby ten moment
            // faktycznie zapadł w pamięć i zachęcił do dalszej gry.
            if (kategoria.equals(KATEGORIA_GLOWNA_SCIEZKA) && q.id() == 1) {
                player.closeInventory();
                player.showTitle(Title.title(
                        Component.text("Witaj na Wyspie", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("Twoja opowieść zaczyna się teraz.", NamedTextColor.YELLOW)
                ));
                // Własna fanfara (freesound "medieval fanfare") zamiast wbudowanego dźwięku -
                // patrz mainplugins-core/resourcepack/assets/mainplugins/sounds.json +
                // sounds/quest_welcome.ogg. Wymaga wgranej paczki resourcepack u gracza
                // (patrz ResourcePackManager) - jeśli paczka się nie załaduje, klient po
                // prostu nic nie odtworzy (cichy no-op, bez błędu).
                player.playSound(player.getLocation(), "mainplugins:quest_welcome", 1.0f, 1.0f);
                return;
            }

            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            otworzKategorie(player, kategoria, strona);
        } else {
            String powod = switch (q.typWymogu()) {
                case MONETY -> "Nie masz wystarczająco monet!";
                case NARZEDZIE -> "Nie masz wymaganego narzędzia!";
                case POZIOM_KILOFA -> "Twój kilof nie jest jeszcze na wymaganym poziomie!";
                case POZIOM_SIEKIERY -> "Twoja siekiera nie jest jeszcze na wymaganym poziomie!";
                case POZIOM_MIECZA -> "Twój miecz nie jest jeszcze na wymaganym poziomie!";
                case OFERTA_NA_TARGU -> "Nie masz jeszcze żadnej aktywnej oferty na Targu! Wpisz /targ wystaw <cena>.";
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
                    case 9 -> { tools.dajEwoluujacyMiecz(player); return; }
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

    // ---- QuestService: postęp Głównej Ścieżki dla innych modułów (np. mainplugins-spawn, /warp kowal) ----

    /** {@inheritDoc} Zero mutacji stanu (w przeciwieństwie do postepyDlaKategorii) - to czysty odczyt pod bramki innych modułów. */
    @Override
    public boolean ukonczylGlownaSciezke(UUID uuid, int questId) {
        Map<String, Set<Integer>> kategorie = postepyGraczy.get(uuid);
        if (kategorie == null) return false;
        Set<Integer> glownaSciezka = kategorie.get(KATEGORIA_GLOWNA_SCIEZKA);
        return glownaSciezka != null && glownaSciezka.contains(questId);
    }
}