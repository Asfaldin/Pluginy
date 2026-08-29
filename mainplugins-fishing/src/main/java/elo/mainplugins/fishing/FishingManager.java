package elo.mainplugins.fishing;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.ObszarService;
import elo.mainplugins.core.util.CustomItemKeys;
import elo.mainplugins.fishing.config.FishingConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Łowienie - wersja 1 (celowo uproszczona, patrz niżej). Jedna zwykła wędka (/wedka,
 * bez tierów, bez upgrade'u w kowadle) - wanilijski hak działa normalnie wszędzie,
 * ale gdy przychodzi BITE (branie) W OBRĘBIE obszaru oznaczonego jako łowisko (patrz
 * ObszarService, flaga ryby-dozwolone ustawiana /@obszar ryby w mainplugins-spawn),
 * przejmujemy go: usuwamy hak i odpalamy własną minigrę "pasek" w stylu Stardew Valley
 * (FishingMinigame), a nagrodą jest jeden z własnych gatunków ryb zamiast wanilijskiego
 * looty. POZA łowiskiem plugin się w ogóle nie wtrąca - zwykłe wanilijskie łowienie.
 *
 * Świadomie wycięte na razie (wraca później jako osobny etap, patrz historia gita):
 * tiery wędek (ale patrz WedkaProfil - "style gry", nie tiery progresji), łowienie w
 * powietrzu/nad pustką, upgrade wędki w kowadle (receptury). Bonusowy drop skrzynki z
 * mainplugins-crates JEST już (patrz rzucBonusowaSkrzynke, tuning w fishing-config.yml).
 *
 * Zero zależności od mainplugins-quests/mainplugins-shop: gatunki ryb są rozpoznawane
 * wyłącznie po współdzielonym CustomItemKeys.CUSTOM_ITEM_ID (patrz mainplugins-core) -
 * te moduły zgadzają się na te same stringi tylko przez konwencję, bez twardej zależności.
 */
public class FishingManager implements Listener {

    private final Plugin plugin;

    // Statystyki rybackie graczy (suma zlowionych kg + rekord najciezszej ryby, patrz
    // /rybtop w MainpluginsFishing) - osobny plik/manager, ten sam wzorzec co
    // EconomyManager w mainplugins-core. Wstrzykiwany, nie tworzony tutaj, zeby
    // MainpluginsFishing mogl go zamknac (zapis natychmiastowy) w onDisable().
    private final FishingStatsManager statystyki;

    // Tuning minigry i bonusowej skrzynki - fishing-config.yml, przeladowywalny bez
    // restartu (patrz aktualizujKonfiguracje / @reloadfishing). Gatunki ryb NIE sa tutaj -
    // patrz gatunki nizej.
    private volatile FishingConfig config;

    // Gatunki ryb do losowania - wczytywane z ryby.yml (w folderze danych tego pluginu,
    // patrz wczytajGatunki) zamiast trzymane na sztywno w kodzie, zeby dalo sie je tuningowac
    // (wagi/rzadkosc/nazwy) bez rekompilacji. Domyslny plik ma 6 gatunkow (po 2 na tier
    // ZWYKLA/NIEZWYKLA/RZADKA - Karp+Dorsz, Sum+Strzelczyk, Wioslozab+Latimeria), wszystkie
    // z prawdziwym custom modelem z resourcepacka (patrz custom-items.yml + stworzRybe
    // nizej) zamiast budowane bezposrednio z golego Materialu.
    private final List<RybaGatunek> gatunki = new ArrayList<>();

    // Aktywna minigra "pasek" - patrz rozpocznijMinigre.
    private final Map<UUID, FishingMinigame> aktywneMinigry = new HashMap<>();

    /** Gatunek + profil wędki wylosowane/ustalone RAZEM przy rzucie - patrz oczekujaceGatunki niżej. */
    private record OczekujacyPolow(RybaGatunek gatunek, WedkaProfil profil) {}

    // Runnable zatrzymujący trwający efekt złowienia (świecące cząsteczki/laser, patrz
    // efektZlapania) - działa dopóki minigra trwa, gaszone na sukces/porażkę/rozłączenie
    // (patrz zakonczEfektPolowu).
    private final Map<UUID, Runnable> aktywneEfektyPolowu = new HashMap<>();

    // Gatunek (+ profil wędki, patrz OczekujacyPolow) wylosowany JUŻ przy rzucie (nie
    // dopiero przy braniu) w łowisku - dzięki temu przy BITE efekt (patrz efektZlapania)
    // od razu wie jakim kolorem świecić, a minigra dostaje profil ustalony w momencie
    // rzutu, nie brania (gdyby gracz zdążył zmienić wędkę w ręce w międzyczasie).
    // Zdejmowane przy BITE (przechodzi do minigry) albo przy dowolnym innym/końcowym
    // stanie zdarzenia (patrz onFish) - żeby nic nie zostawało "wiszące" po spudłowanym rzucie.
    private final Map<UUID, OczekujacyPolow> oczekujaceGatunki = new HashMap<>();

    // Tag na wędkach TESTOWYCH (patrz stworzWedkeTestowa/@wedka1-3 w MainpluginsFishing) -
    // trzyma 0-based indeks do gatunki, żeby onFish mógł wymusić konkretny gatunek zamiast
    // losować, i przyspieszyć branie (patrz FishHook.setWaitTime) do testów. WYŁĄCZNIE do
    // testów na permisji mainplugins.fishing.admin - normalna /wedka tego tagu nie ma.
    private final NamespacedKey tagWedkiTestowej;

    // Tag na wędkach TESTOWYCH PROFILU (patrz stworzWedkeProfilTestowa/@wedkacierpliwa itd.
    // w MainpluginsFishing) - trzyma nazwę stałej WedkaProfil. W odróżnieniu od tagu wyżej
    // NIE wymusza gatunku - normalne losowanie (patrz losujRybe) dalej działa, tylko z
    // biasem tego profilu, żeby dało się realnie przetestować wpływ wędki na szanse na
    // gatunek, a nie tylko na fizykę suwaka.
    private final NamespacedKey tagProfiluTestowego;

    // Tag na SAMYM GRACZU (nie na itemie) - preferencja gdzie ma się wyświetlać pasek
    // minigry (patrz PozycjaPaska, komenda /rybpasek w MainpluginsFishing). Trwały
    // (PersistentDataContainer gracza), więc pamiętany między sesjami bez osobnego pliku.
    private final NamespacedKey tagPozycjaPaska;

    public FishingManager(Plugin plugin, FishingConfig config, FishingStatsManager statystyki) {
        this.plugin = plugin;
        this.config = config;
        this.statystyki = statystyki;
        this.tagWedkiTestowej = new NamespacedKey(plugin, "wedka_test_indeks");
        this.tagProfiluTestowego = new NamespacedKey(plugin, "wedka_test_profil");
        this.tagPozycjaPaska = new NamespacedKey(plugin, "fishing_pozycja_paska");
        wczytajGatunki();
    }

    // ==================================================================== Preferencje gracza ====

    /** Patrz tagPozycjaPaska - PozycjaPaska.GORA (dotychczasowe zachowanie) jeśli gracz nigdy nic nie ustawiał. */
    public PozycjaPaska pozycjaPaska(Player player) {
        String nazwa = player.getPersistentDataContainer().get(tagPozycjaPaska, PersistentDataType.STRING);
        if (nazwa == null) return PozycjaPaska.GORA;
        try {
            return PozycjaPaska.valueOf(nazwa);
        } catch (IllegalArgumentException e) {
            return PozycjaPaska.GORA;
        }
    }

    /** Wywoływane z komendy /rybpasek (patrz MainpluginsFishing). */
    public void ustawPozycjePaska(Player player, PozycjaPaska pozycja) {
        player.getPersistentDataContainer().set(tagPozycjaPaska, PersistentDataType.STRING, pozycja.name());
    }

    /** Przeladowuje tuning minigry/bonusowej skrzynki (fishing-config.yml) i gatunki ryb (ryby.yml) - patrz komenda @reloadfishing. */
    public void aktualizujKonfiguracje(FishingConfig nowy) {
        this.config = nowy;
        wczytajGatunki();
    }

    /** Wszystkie znane gatunki, w KANONICZNEJ kolejnosci z ryby.yml - patrz /rybindeks w MainpluginsFishing (kolejnosc wyswietlania indeksu). Defensywna kopia. */
    public List<RybaGatunek> gatunki() {
        return List.copyOf(gatunki);
    }

    // ==================================================================== Konfiguracja ====

    /**
     * Wczytuje gatunki ryb z ryby.yml w folderze danych pluginu (kopiowanego z zasobow
     * przy pierwszym uruchomieniu, patrz plugin.saveResource) - ten sam wzorzec co
     * RotacjaManager.wczytajPule() w mainplugins-shop. Publiczna, zeby dalo sie przeladowac
     * bez restartu serwera (patrz komenda @reloadfishing w MainpluginsFishing / aktualizujKonfiguracje).
     */
    public void wczytajGatunki() {
        File plik = new File(plugin.getDataFolder(), "ryby.yml");
        if (!plik.exists()) {
            plugin.saveResource("ryby.yml", false);
        }

        FileConfiguration plikRyb = YamlConfiguration.loadConfiguration(plik);
        List<RybaGatunek> wczytane = new ArrayList<>();
        for (Map<?, ?> wpis : plikRyb.getMapList("gatunki")) {
            try {
                String customId = String.valueOf(wpis.get("custom-id"));
                String nazwa = String.valueOf(wpis.get("name"));
                Material material = Material.valueOf(String.valueOf(wpis.get("material")));
                NamedTextColor kolor = parsujKolor(wpis.get("color") != null ? String.valueOf(wpis.get("color")) : "white");
                RybaGatunek.Rzadkosc rzadkosc = RybaGatunek.Rzadkosc.valueOf(String.valueOf(wpis.get("rarity")));
                int waga = wpis.get("weight") != null ? ((Number) wpis.get("weight")).intValue() : 1;
                // Domyslne kg-* (gdyby ktos dodal nowy gatunek i zapomnial ich dopisac) -
                // baseline identyczny z ZWYKLA (patrz ryby.yml), zeby brakujacy wpis dostal
                // rozsadna, gotowa do gry wartosc zamiast psuc losowanie wagi/NPE.
                double kgTypowyMin = liczbaZMapy(wpis, "kg-typowy-min", 10.0);
                double kgTypowyMax = liczbaZMapy(wpis, "kg-typowy-max", 20.0);
                double kgMin = liczbaZMapy(wpis, "kg-min", 5.0);
                double kgMax = liczbaZMapy(wpis, "kg-max", 40.0);
                wczytane.add(new RybaGatunek(customId, nazwa, material, kolor, rzadkosc, Math.max(1, waga),
                        kgTypowyMin, kgTypowyMax, kgMin, kgMax));
            } catch (Exception e) {
                plugin.getLogger().warning("Zły wpis w ryby.yml, pomijam: " + e.getMessage());
            }
        }

        if (wczytane.isEmpty()) {
            plugin.getLogger().warning("ryby.yml nie zawiera żadnego poprawnego gatunku - łowienie w łowiskach nie będzie działać!");
        }

        gatunki.clear();
        gatunki.addAll(wczytane);
        plugin.getLogger().info("Fishing: wczytano " + gatunki.size() + " gatunków ryb z ryby.yml.");
    }

    private NamedTextColor parsujKolor(String nazwa) {
        NamedTextColor kolor = NamedTextColor.NAMES.value(nazwa.toLowerCase());
        return kolor != null ? kolor : NamedTextColor.WHITE;
    }

    private double liczbaZMapy(Map<?, ?> wpis, String klucz, double domyslna) {
        Object wartosc = wpis.get(klucz);
        return wartosc instanceof Number number ? number.doubleValue() : domyslna;
    }

    // ==================================================================== Przedmioty ====

    /** Jedyna wędka na razie - zwykły wanilijski FISHING_ROD, zero tagów. Działanie zależy WYŁĄCZNIE od miejsca (patrz onFish), nie od samego przedmiotu. */
    public ItemStack stworzWedke() {
        return new ItemStack(Material.FISHING_ROD);
    }

    /**
     * WYŁĄCZNIE do testów (patrz /wedka1, /wedka2, /wedka3 w MainpluginsFishing, za
     * permisją mainplugins.fishing.admin) - wędka otagowana indeksem (1-based) do gatunki
     * z ryby.yml. W łowisku onFish rozpoznaje tag i: (1) wymusza TEN gatunek zamiast
     * losować, (2) ustawia FishHook.setWaitTime na prawie natychmiastowe branie - żeby nie
     * czekać za każdym razem na wanilijski losowy timer przy testowaniu minigry/efektów.
     */
    public ItemStack stworzWedkeTestowa(int indeks1Based) {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        String nazwaGatunku = (indeks1Based - 1 >= 0 && indeks1Based - 1 < gatunki.size())
                ? gatunki.get(indeks1Based - 1).nazwa() : "?";
        meta.displayName(Component.text("Wędka Testowa #" + indeks1Based + " (" + nazwaGatunku + ")", NamedTextColor.YELLOW, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Wymusza " + indeks1Based + ". gatunek z ryby.yml", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("i prawie natychmiastowe branie w łowisku.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(tagWedkiTestowej, PersistentDataType.INTEGER, indeks1Based - 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * WYŁĄCZNIE do testów (patrz /wedkazrownowazona, /wedkacierpliwa, /wedkaszarpana w
     * MainpluginsFishing, za permisją mainplugins.fishing.admin) - wędka otagowana danym
     * WedkaProfil. W odróżnieniu od stworzWedkeTestowa wyżej NIE wymusza gatunku - onFish
     * dalej losuje normalnie (patrz losujRybe), tylko z biasem tego profilu, i minigra
     * (patrz FishingMinigame) dostaje jego mnożniki fizyki suwaka.
     */
    public ItemStack stworzWedkeProfilTestowa(WedkaProfil profil) {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Wędka " + profil.nazwa() + " [TEST]", profil.kolor(), TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Profil testowy: " + profil.nazwa() + ".", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Normalne losowanie gatunku, z biasem tego profilu.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(tagProfiluTestowego, PersistentDataType.STRING, profil.name());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Jeśli gatunek ma wpis w rejestrze custom itemów (patrz mainplugins-core,
     * custom-items.yml) - np. FISH_KARP z własnym modelem z resourcepacka -
     * wydajemy DOKŁADNIE ten item stamtąd (ten sam wzorzec co ShopManager.stworzBazowyItem).
     * W przeciwnym razie (reszta gatunków - zwykłe przefarbowane materiały) budujemy
     * item ręcznie, tak jak dotychczas. Bez wagi w lore - patrz stworzRybe (polow) i
     * iconaIndeksu (GUI /rybindeks), ktore dorzucaja WLASNE, rozne linie lore na tej samej bazie.
     */
    private ItemStack bazowyItemRyby(RybaGatunek gatunek) {
        CustomItemService rejestr = CoreAPI.getCustomItemService();
        ItemStack custom = (rejestr != null && rejestr.exists(gatunek.customId())) ? rejestr.create(gatunek.customId(), 1) : null;
        if (custom != null) return custom;

        ItemStack item = new ItemStack(gatunek.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(gatunek.nazwa(), gatunek.kolor(), TextDecoration.BOLD));
        meta.lore(new ArrayList<>(List.of(Component.text(opisRzadkosci(gatunek.rzadkosc()), gatunek.kolor()).decoration(TextDecoration.ITALIC, false))));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, gatunek.customId());
        if (gatunek.rzadkosc().ordinal() >= RybaGatunek.Rzadkosc.RZADKA.ordinal()) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Item wydawany graczowi po udanym polowie - bazowy item gatunku (patrz
     * bazowyItemRyby) plus osobna linia lore z wagą TEJ KONKRETNEJ złowionej ryby (patrz
     * losujWageDziesieteKg) - stąd dwie złowione ryby tego samego gatunku prawie nigdy
     * nie są identycznym stackiem (różna waga w lore), więc się NIE STAPIAJĄ w
     * ekwipunku. Świadomy kompromis: każda ryba ma swoją indywidualną wagę (cały sens
     * rankingu /rybtop), kosztem zajmowania większej liczby slotów przy intensywnym
     * łowieniu. Quest/sklep i tak liczą po custom-id w PDC, NIE po pełnym porównaniu
     * itemu, więc różna waga/lore niczego tam nie psuje.
     */
    private ItemStack stworzRybe(RybaGatunek gatunek, int wagaDziesieteKg) {
        ItemStack item = bazowyItemRyby(gatunek);

        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.text("Waga: " + formatKg(wagaDziesieteKg) + " kg", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String formatKg(int wagaDziesieteKg) {
        return String.format(Locale.ROOT, "%.1f", wagaDziesieteKg / 10.0);
    }

    private String opisRzadkosci(RybaGatunek.Rzadkosc r) {
        return switch (r) {
            case ZWYKLA -> "Zwykła ryba";
            case NIEZWYKLA -> "Niezwykła ryba";
            case RZADKA -> "Rzadka ryba";
            case EPICKA -> "Epicka ryba";
            case LEGENDARNA -> "Legendarna ryba";
            case MITYCZNA -> "Mityczna ryba";
        };
    }

    // ==================================================================== Losowanie ====

    /**
     * Waga z ryby.yml, przemnożona przez profil.mnoznikRzadkosci() do potęgi rzadkości
     * gatunku (patrz WedkaProfil) - ZWYKLA (ordinal 0) zawsze wychodzi bez zmian, każdy
     * kolejny stopień rzadkości mnoży się profilem o kolejną potęgę. Stąd double zamiast
     * int (mnożenie wag przestaje dawać liczby całkowite).
     */
    private double wagaEfektywna(RybaGatunek g, WedkaProfil profil) {
        return g.waga() * Math.pow(profil.mnoznikRzadkosci(), g.rzadkosc().ordinal());
    }

    private RybaGatunek losujRybe(WedkaProfil profil) {
        if (gatunki.isEmpty()) return null;

        double suma = 0;
        for (RybaGatunek g : gatunki) suma += wagaEfektywna(g, profil);

        double los = ThreadLocalRandom.current().nextDouble(suma);
        double akumulator = 0;
        for (RybaGatunek g : gatunki) {
            akumulator += wagaEfektywna(g, profil);
            if (los < akumulator) return g;
        }
        return gatunki.getLast();
    }

    // Ile prob odrzucenia-i-ponownego-losowania (patrz losujWageDziesieteKg) zanim
    // poddamy sie i przytniemy do granicy - czysty bezpiecznik na wypadek zle
    // skonfigurowanego gatunku w ryby.yml (np. kg-typowy szerszy niz kg-twardy), zeby
    // NIGDY nie zapetlic sie w nieskonczonosc. Przy sensownym configu (typowy MIESCI
    // SIE w twardym) prawie zawsze trafiamy w 1-2 probach.
    private static final int MAX_PROB_LOSOWANIA_WAGI = 100;

    // Ile "odchylen standardowych" ma dzielic srodek typowego zakresu od kg-max, czyli
    // jak bardzo jackpotowy ma byc najciezszy mozliwy okaz (patrz losujWageDziesieteKg).
    // 4.0 odchylenia = szansa na wynik TAK DALEKI od srodka (czyli - w praktyce - na
    // wylosowanie czegos w okolicy kg-max) to ok. 1 na 31 500 polowow danego gatunku -
    // ustalone z userem 2026-08-29 (chcial "1 na 30 000"). Ta sama stala dla kazdego
    // gatunku, wiec kazdy ma swoj najciezszy mozliwy okaz rownie rzadki WZGLEDEM WLASNEJ
    // skali, niezaleznie jak szeroki/waski ma zakres w ryby.yml.
    private static final double ILOSC_SIGMA_DO_MAKSIMUM = 4.0;

    /**
     * Losuje fizyczna wage konkretnej zlowionej ryby w "dziesiatych kg" (np. 143 = 14.3kg) -
     * rozklad normalny (Random.nextGaussian) wysrodkowany na srodku kg-typowy-min/max z
     * gatunku. Sigma dobrane TAK, ZEBY DYSTANS OD SRODKA DO KG-MAX ODPOWIADAL
     * ILOSC_SIGMA_DO_MAKSIMUM ODCHYLENIOM (patrz jej javadoc) - nie od szerokosci samego
     * typowego zakresu, bo w tej grze kg-max bywa DUZO dalej od srodka niz kg-min (patrz
     * ryby.yml - np. Karp: srodek 15, kg-min tylko 10 od niego, kg-max az 25) - to celowo
     * asymetryczne, bo tylko GORNY kraniec ma byc prawdziwym jackpotem (male okazy moga
     * byc relatywnie czestsze, tak jak user chcial - podkreslal trudnosc tylko dla duzej
     * ryby). Wynik spoza twardego limitu (kg-min/kg-max) jest ODRZUCANY i losowany ponownie
     * (zamiast przyciety do granicy) - dzieki temu szansa zanika plynnie do zera, zamiast
     * tworzyc sztuczny "garb" dokladnie na granicy limitu.
     */
    private int losujWageDziesieteKg(RybaGatunek gatunek) {
        double srodek = (gatunek.kgTypowyMin() + gatunek.kgTypowyMax()) / 2.0;
        double sigma = Math.max(0.01, (gatunek.kgMax() - srodek) / ILOSC_SIGMA_DO_MAKSIMUM);

        double waga = srodek;
        for (int proba = 0; proba < MAX_PROB_LOSOWANIA_WAGI; proba++) {
            waga = srodek + ThreadLocalRandom.current().nextGaussian() * sigma;
            if (waga >= gatunek.kgMin() && waga <= gatunek.kgMax()) break;
        }
        waga = Math.max(gatunek.kgMin(), Math.min(gatunek.kgMax(), waga)); // bezpiecznik, patrz MAX_PROB_LOSOWANIA_WAGI

        return (int) Math.round(waga * 10);
    }

    /** Patrz stworzWedkeProfilTestowa - WedkaProfil.ZROWNOWAZONA (bez zmian) jeśli gracz nie trzyma otagowanej wędki testowej. */
    private WedkaProfil profilZWedki(Player player) {
        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (!wRece.hasItemMeta()) return WedkaProfil.ZROWNOWAZONA;

        String nazwa = wRece.getItemMeta().getPersistentDataContainer().get(tagProfiluTestowego, PersistentDataType.STRING);
        if (nazwa == null) return WedkaProfil.ZROWNOWAZONA;

        try {
            return WedkaProfil.valueOf(nazwa);
        } catch (IllegalArgumentException e) {
            return WedkaProfil.ZROWNOWAZONA;
        }
    }

    /**
     * Niezależny bonusowy drop skrzynki z mainplugins-crates po udanym połowie - płaska
     * szansa na razie (brak tierów wędki do skalowania nią, patrz javadoc klasy). Cichy
     * no-op, jeśli mainplugins-crates nie jest wgrany (opcjonalny serwis, patrz CoreAPI).
     */
    private void rzucBonusowaSkrzynke(Player player) {
        CrateService crateService = CoreAPI.getCrateService();
        if (crateService == null) return;
        if (ThreadLocalRandom.current().nextDouble(100.0) >= config.bonusowaSkrzynkaSzansaProcent()) return;

        ItemStack skrzynka = crateService.stworzSkrzynke(1);
        var nieZmieszczone = player.getInventory().addItem(skrzynka);
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        player.sendMessage(Component.text("Z haczyka wypadła też skrzynka!", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
    }

    // ==================================================================== Łowienie ====

    /**
     * Poza łowiskiem nie robimy NIC - zdarzenie leci dalej nietknięte, gracz łowi zupełnie
     * wanilijsko (włącznie z wanilijskimi cząsteczkami plusku/bąbelków - świadomie
     * zostawione, patrz historia rozmowy: próba ich wycięcia przez ProtocolLib okazała
     * się niewarta zachodu). Wewnątrz łowiska: przy rzucie (FISHING) losujemy gatunek OD
     * RAZU (nie dopiero przy braniu), żeby przy BITE efekt złowienia (patrz efektZlapania)
     * od razu wiedział jakim kolorem świecić. Dopiero przy faktycznym braniu (BITE), czyli
     * gdy ryba NAPRAWDĘ dochodzi do spławika, odpalamy ten efekt I przejmujemy połów na
     * dobre: usuwamy prawdziwy hak (żeby wanilijski CAUGHT_FISH nigdy nie nastąpił) i
     * startujemy minigrę z tym samym, już wylosowanym gatunkiem. Każdy inny/końcowy stan
     * (spudłowany rzut, wyciągnięty za wcześnie, złowiony mob itd.) porzuca ten gatunek.
     */
    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getHook() == null) return;
        UUID uuid = event.getPlayer().getUniqueId();

        if (event.getState() != PlayerFishEvent.State.FISHING && event.getState() != PlayerFishEvent.State.BITE) {
            oczekujaceGatunki.remove(uuid);
            return;
        }

        ObszarService obszarService = CoreAPI.getObszarService();
        if (obszarService == null || !obszarService.jestLowiskiem(event.getHook().getLocation())) return;
        if (gatunki.isEmpty()) return; // ryby.yml bez gatunkow - zostaw wanilijskie lowienie

        if (event.getState() == PlayerFishEvent.State.FISHING) {
            WedkaProfil profil = profilZWedki(event.getPlayer());
            RybaGatunek wymuszony = wymuszonyGatunekZWedkiTestowej(event.getPlayer());
            RybaGatunek gatunek = wymuszony != null ? wymuszony : losujRybe(profil);
            if (gatunek == null) return; // ryby.yml pusty/uszkodzony - patrz wczytajGatunki, zostawiamy wanilijskie łowienie

            // Zapis PRZED próbą przyspieszenia brania - jeśli setWaitTime akurat rzuci
            // wyjątek (nieudokumentowane ograniczenie tej wersji MC), wymuszony gatunek
            // i tak zostaje zapamiętany, zamiast po cichu spaść do losowania na BITE.
            oczekujaceGatunki.put(uuid, new OczekujacyPolow(gatunek, profil));

            if (wymuszony != null) {
                event.getPlayer().sendMessage(Component.text("[TEST] Wymuszono: " + gatunek.nazwa(), NamedTextColor.YELLOW));
                try {
                    event.getHook().setWaitTime(1, 5); // patrz stworzWedkeTestowa - branie prawie natychmiastowe
                } catch (Exception e) {
                    plugin.getLogger().warning("Wedka testowa: nie udalo sie przyspieszyc brania (setWaitTime) - " + e);
                }
            }
            return;
        }

        // BITE - ryba naprawdę doszła do spławika
        Location lokalizacjaHaka = event.getHook().getLocation().clone();
        OczekujacyPolow oczekujacy = oczekujaceGatunki.remove(uuid);
        if (oczekujacy == null) {
            // Asekuracyjnie, gdyby FISHING nie doszedl do glosu (np. hak w locie byl jeszcze
            // poza granicami lowiska, a osiadl w nim dopiero pozniej) - sprawdzamy wedke
            // testowa TERAZ, zamiast od razu skakac do czystego losowania, zeby wymuszanie
            // gatunku dzialalo niezaleznie od tego kiedy dokladnie hak wpadl w granice.
            WedkaProfil profil = profilZWedki(event.getPlayer());
            RybaGatunek wymuszony = wymuszonyGatunekZWedkiTestowej(event.getPlayer());
            RybaGatunek gatunek = wymuszony != null ? wymuszony : losujRybe(profil);
            oczekujacy = gatunek != null ? new OczekujacyPolow(gatunek, profil) : null;
        }
        if (oczekujacy == null) return;

        event.setCancelled(true);
        event.getHook().remove();

        rozpocznijMinigre(event.getPlayer(), oczekujacy.gatunek(), oczekujacy.profil(), lokalizacjaHaka);
    }

    /** Patrz stworzWedkeTestowa - null jeśli gracz nie trzyma wędki testowej albo jej indeks wypadł poza aktualną listę gatunki (np. po edycji ryby.yml). */
    private RybaGatunek wymuszonyGatunekZWedkiTestowej(Player player) {
        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (!wRece.hasItemMeta()) return null;

        Integer indeks = wRece.getItemMeta().getPersistentDataContainer().get(tagWedkiTestowej, PersistentDataType.INTEGER);
        if (indeks == null || indeks < 0 || indeks >= gatunki.size()) return null;

        return gatunki.get(indeks);
    }

    // ==================================================================== Efekt złowienia (cząsteczki) ====

    private static final long OKRES_SWIECENIA_TICKOW = 4;

    /**
     * Kolorowe cząsteczki (kolor gatunku) DOKŁADNIE w miejscu spławika - wybuch na start
     * (moment gdy ryba naprawdę dochodzi do spławika, patrz onFish) i od tego momentu
     * dalej ŚWIECI CIĄGLE, dopóki trwa walka z rybą (minigra paska) - gaśnie dopiero na
     * sukces/porażkę (patrz rozpocznijMinigre, które woła zwrócony stąd Runnable). Od
     * rzadkości RZADKA w górę (ten sam próg co enchant glint w stworzRybe) dorzuca duży
     * "widowiskowy" efekt - patrz MistycznyLaser (świecący słup + korkociąg cząsteczek +
     * wybuch + tytuł na ekranie), w 100% na bezpiecznym Bukkit API.
     *
     * @return Runnable do wywołania, gdy efekt ma zgasnąć - patrz rozpocznijMinigre.
     */
    private Runnable efektZlapania(Player player, RybaGatunek gatunek, Location lokalizacjaHaka) {
        boolean rzadka = gatunek.rzadkosc().ordinal() >= RybaGatunek.Rzadkosc.RZADKA.ordinal();
        Particle.DustOptions kolor = new Particle.DustOptions(Color.fromRGB(gatunek.kolor().value()), 1.4f);

        player.getWorld().spawnParticle(Particle.DUST, lokalizacjaHaka, 30, 0.2, 0.12, 0.2, 0, kolor);

        BukkitTask swiecenie = Bukkit.getScheduler().runTaskTimer(plugin, () ->
                player.getWorld().spawnParticle(Particle.DUST, lokalizacjaHaka, 4, 0.15, 0.08, 0.15, 0, kolor),
                OKRES_SWIECENIA_TICKOW, OKRES_SWIECENIA_TICKOW);

        Runnable zatrzymajLaser = rzadka
                ? MistycznyLaser.pokaz(plugin, player, lokalizacjaHaka, gatunek.kolor(), gatunek.nazwa())
                : null;

        return () -> {
            swiecenie.cancel();
            if (zatrzymajLaser != null) zatrzymajLaser.run();
        };
    }

    // ==================================================================== Minigra "pasek" ====

    private void rozpocznijMinigre(Player player, RybaGatunek gatunek, WedkaProfil profil, Location lokalizacjaHaka) {
        UUID uuid = player.getUniqueId();
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f);
        // Wyrazny "dzwonek" DOKLADNIE w momencie brania - inny instrument niz
        // pling/bass uzywane wewnatrz samej minigry (patrz FishingMinigame.tick) zeby
        // gracz od razu, po samym dzwieku (bez patrzenia na ekran), wiedzial ze RYBA
        // WLASNIE WZIELA i minigra ruszyla - trzeba zaczac klikac PPM.
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.4f);

        aktywneEfektyPolowu.put(uuid, efektZlapania(player, gatunek, lokalizacjaHaka));

        FishingMinigame gra = new FishingMinigame(plugin, player, gatunek, profil, config.minigra(), pozycjaPaska(player),
                () -> {
                    aktywneMinigry.remove(uuid);
                    zakonczEfektPolowu(uuid);
                    nagrodaZaPolow(player, gatunek);
                },
                () -> {
                    aktywneMinigry.remove(uuid);
                    zakonczEfektPolowu(uuid);
                    player.sendMessage(Component.text("Ryba się wyrwała...", NamedTextColor.GRAY));
                });
        aktywneMinigry.put(uuid, gra);
    }

    private void zakonczEfektPolowu(UUID uuid) {
        Runnable zatrzymaj = aktywneEfektyPolowu.remove(uuid);
        if (zatrzymaj != null) zatrzymaj.run();
    }

    private void nagrodaZaPolow(Player player, RybaGatunek zlowiona) {
        int wagaDziesieteKg = losujWageDziesieteKg(zlowiona);

        ItemStack ryba = stworzRybe(zlowiona, wagaDziesieteKg);
        var nieZmieszczone = player.getInventory().addItem(ryba);
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));

        player.sendMessage(Component.text("Złowiłeś: ", NamedTextColor.GREEN)
                .append(Component.text(zlowiona.nazwa(), zlowiona.kolor(), TextDecoration.BOLD))
                .append(Component.text(" (" + formatKg(wagaDziesieteKg) + " kg)", NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        rzucBonusowaSkrzynke(player);

        statystyki.zanotujPolow(player.getUniqueId(), player.getName(), zlowiona.customId(), zlowiona.nazwa(), wagaDziesieteKg);

        FishingStatsManager.NowyRekordGatunku rekord = statystyki.zanotujRekordGatunkuJesliNowy(zlowiona.customId(), zlowiona.nazwa(), wagaDziesieteKg, player.getUniqueId(), player.getName());
        if (rekord != null) ogloszRekordGatunku(player, zlowiona, wagaDziesieteKg, rekord);
    }

    /**
     * Publiczne ogloszenie na czacie CALEGO serwera (patrz Bukkit.broadcast - ten sam
     * wzorzec co CrateManager przy wygranej ze skrzynki) - WYLACZNIE gdy polow byl nowym
     * rekordem SWOJEGO GATUNKU (patrz zanotujRekordGatunkuJesliNowy), nie za kazdym razem -
     * zwykle polowy zostaja prywatne (patrz wiadomosc w nagrodaZaPolow wyzej). Inne
     * brzmienie dla pierwszego kiedykolwiek okazu danego gatunku (nie ma czego bic) vs
     * realnego pobicia czyjegos rekordu (dorzuca kto i ile trzymal poprzednio, dla
     * wiekszego "flexu" - patrz rozmowa z userem 2026-08-29).
     */
    private void ogloszRekordGatunku(Player player, RybaGatunek gatunek, int wagaDziesieteKg, FishingStatsManager.NowyRekordGatunku rekord) {
        Component naglowek = Component.text("🏆 ", NamedTextColor.GOLD);
        Component polow = Component.text(gatunek.nazwa() + " (" + formatKg(wagaDziesieteKg) + " kg)", gatunek.kolor(), TextDecoration.BOLD);

        if (rekord.pierwszy()) {
            Bukkit.broadcast(naglowek
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" złowił ", NamedTextColor.GOLD))
                    .append(polow)
                    .append(Component.text(" — rekord serwera na ten gatunek!", NamedTextColor.GOLD)));
        } else {
            Component poprzedni = rekord.poprzedniNick() != null
                    ? Component.text(" (poprzedni rekord: " + String.format(Locale.ROOT, "%.1f", rekord.poprzedniaWagaKg()) + " kg, " + rekord.poprzedniNick() + ")", NamedTextColor.GRAY)
                    : Component.text(" (poprzedni rekord: " + String.format(Locale.ROOT, "%.1f", rekord.poprzedniaWagaKg()) + " kg)", NamedTextColor.GRAY);
            Bukkit.broadcast(naglowek
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" POBIŁ REKORD SERWERA! ", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(polow)
                    .append(poprzedni));
        }
    }

    // ==================================================================== GUI: Indeks Rybacki ====

    // Rozpoznawcza fraza w tytule GUI (patrz onInventoryClick) - ten sam wzorzec co
    // MarketManager.onInventoryClick (title.contains(...), NIE startsWith - Component
    // zserializowany przez .toString() niesie ze soba dodatkowe metadane formatowania,
    // wiec dokladne dopasowanie prefiksu bywa zawodne, "contains" na charakterystycznym
    // kawalku tekstu jest niezawodne).
    private static final String TYTUL_INDEKSU = "Indeks Rybacki";
    private static final int SLOT_ODZNAKA_RZADKOSCI = 4;
    private static final int SLOT_POSTEP_OGOLNY = 47;
    private static final int SLOT_ZAMKNIJ_INDEKSU = 49;
    private static final int SLOT_POPRZEDNIA_RZADKOSC = 45;
    private static final int SLOT_NASTEPNA_RZADKOSC = 53;
    private static final int RZAD_RYB_START = 19; // srodkowy rzad (patrz otworzIndeks), 7 slotow: 19-25
    private static final int RZAD_RYB_KONIEC = 25;

    // Ktora rzadkosc dany gracz ogląda w GUI /rybindeks TERAZ - patrz otworzIndeks(player,
    // tier) i przyciski poprzednia/nastepna w onInventoryClick. Pamietane per-gracz (nie
    // globalnie), zeby ponowne otwarcie komenda wracalo tam gdzie gracz skonczyl.
    private final Map<UUID, RybaGatunek.Rzadkosc> tierIndeksuGracza = new HashMap<>();

    /** Rzadkosci, ktore maja CHOCIAZ JEDEN gatunek w ryby.yml, w kolejnosci enuma - patrz otworzIndeks (EPICKA/LEGENDARNA na razie puste, wiec sie nie pojawiaja w przelaczniku). */
    private List<RybaGatunek.Rzadkosc> rzadkosciZGatunkami() {
        List<RybaGatunek.Rzadkosc> wynik = new ArrayList<>();
        for (RybaGatunek.Rzadkosc tier : RybaGatunek.Rzadkosc.values()) {
            for (RybaGatunek g : gatunki) {
                if (g.rzadkosc() == tier) {
                    wynik.add(tier);
                    break;
                }
            }
        }
        return wynik;
    }

    /** Otwiera GUI "Indeks Rybacki" na rzadkości, na której gracz ostatnio skończył (albo pierwszej dostępnej) - patrz /rybindeks w MainpluginsFishing. */
    public void otworzIndeks(Player player) {
        List<RybaGatunek.Rzadkosc> tiery = rzadkosciZGatunkami();
        if (tiery.isEmpty()) {
            player.sendMessage(Component.text("Żadne gatunki ryb nie są jeszcze skonfigurowane.", NamedTextColor.RED));
            return;
        }
        RybaGatunek.Rzadkosc zapamietany = tierIndeksuGracza.get(player.getUniqueId());
        otworzIndeks(player, tiery.contains(zapamietany) ? zapamietany : tiery.get(0));
    }

    /**
     * Otwiera GUI "Indeks Rybacki" na KONKRETNEJ rzadkości - jedna sekcja na ekran (patrz
     * SLOT_ODZNAKA_RZADKOSCI/RZAD_RYB_*), z przyciskami "« poprzednia/następna »" do
     * przełączania między rzadkościami (patrz onInventoryClick) zamiast wszystkich
     * naraz w oddzielnych rzędach - user 2026-08-29 wolał to od poprzedniej wersji.
     * Gatunek JUŻ złowiony (patrz FishingStatsManager.getIndeks) pokazuje prawdziwą ikonę
     * z lore (ile sztuk + osobisty rekord wagi), a JESZCZE nieodkryty pokazuje WYŁĄCZNIE
     * czerwoną szybę - zero nazwy/lore zdradzającej co to za ryba.
     */
    private void otworzIndeks(Player player, RybaGatunek.Rzadkosc tier) {
        tierIndeksuGracza.put(player.getUniqueId(), tier);
        List<RybaGatunek.Rzadkosc> tiery = rzadkosciZGatunkami();
        int indeksTieru = tiery.indexOf(tier);

        Map<String, FishingStatsManager.WpisIndeksu> odkryte = new HashMap<>();
        for (FishingStatsManager.WpisIndeksu wpis : statystyki.getIndeks(player.getUniqueId())) {
            odkryte.put(wpis.customId(), wpis);
        }

        Inventory gui = Bukkit.createInventory(null, 54, Component.text(TYTUL_INDEKSU + " — " + nazwaTieru(tier), NamedTextColor.AQUA, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta mTlo = tlo.getItemMeta();
        mTlo.displayName(Component.empty());
        tlo.setItemMeta(mTlo);
        for (int i = 0; i < 54; i++) gui.setItem(i, tlo);

        List<RybaGatunek> gatunkiTieru = new ArrayList<>();
        for (RybaGatunek g : gatunki) if (g.rzadkosc() == tier) gatunkiTieru.add(g);

        int slot = RZAD_RYB_START;
        int znalezionychWTierze = 0;
        for (RybaGatunek gatunek : gatunkiTieru) {
            if (slot > RZAD_RYB_KONIEC) break; // bezpiecznik, gdyby kiedys jedna rzadkosc miala wiecej niz 7 gatunkow
            FishingStatsManager.WpisIndeksu wpis = odkryte.get(gatunek.customId());
            if (wpis != null) {
                znalezionychWTierze++;
                gui.setItem(slot, iconaIndeksu(gatunek, wpis));
            } else {
                gui.setItem(slot, nieodkrytaIkona());
            }
            slot++;
        }

        ItemStack odznaka = etykietaTieru(tier);
        ItemMeta mOdznaka = odznaka.getItemMeta();
        mOdznaka.lore(List.of(Component.text(znalezionychWTierze + " / " + gatunkiTieru.size() + " odkrytych w tej sekcji", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        odznaka.setItemMeta(mOdznaka);
        gui.setItem(SLOT_ODZNAKA_RZADKOSCI, odznaka);

        ItemStack postep = new ItemStack(Material.NAUTILUS_SHELL);
        ItemMeta mPostep = postep.getItemMeta();
        mPostep.displayName(Component.text("Odkryto łącznie: " + odkryte.size() + " / " + gatunki.size() + " gatunków", NamedTextColor.YELLOW, TextDecoration.BOLD));
        postep.setItemMeta(mPostep);
        gui.setItem(SLOT_POSTEP_OGOLNY, postep);

        if (indeksTieru > 0) gui.setItem(SLOT_POPRZEDNIA_RZADKOSC, strzalkaTieru(tiery.get(indeksTieru - 1), true));
        if (indeksTieru < tiery.size() - 1) gui.setItem(SLOT_NASTEPNA_RZADKOSC, strzalkaTieru(tiery.get(indeksTieru + 1), false));

        ItemStack zamknij = new ItemStack(Material.BARRIER);
        ItemMeta mZamknij = zamknij.getItemMeta();
        mZamknij.displayName(Component.text("Zamknij", NamedTextColor.RED, TextDecoration.BOLD));
        zamknij.setItemMeta(mZamknij);
        gui.setItem(SLOT_ZAMKNIJ_INDEKSU, zamknij);

        player.openInventory(gui);
    }

    /** Przycisk przełączający na sąsiednią rzadkość - patrz otworzIndeks/onInventoryClick. */
    private ItemStack strzalkaTieru(RybaGatunek.Rzadkosc docelowa, boolean poprzednia) {
        ItemStack item = new ItemStack(Material.SPECTRAL_ARROW);
        ItemMeta meta = item.getItemMeta();
        String nazwa = poprzednia ? "« " + nazwaTieru(docelowa) : nazwaTieru(docelowa) + " »";
        meta.displayName(Component.text(nazwa, NamedTextColor.YELLOW, TextDecoration.BOLD));
        item.setItemMeta(meta);
        return item;
    }

    private String nazwaTieru(RybaGatunek.Rzadkosc tier) {
        return switch (tier) {
            case ZWYKLA -> "Zwykłe";
            case NIEZWYKLA -> "Niezwykłe";
            case RZADKA -> "Rzadkie";
            case EPICKA -> "Epickie";
            case LEGENDARNA -> "Legendarne";
            case MITYCZNA -> "Mityczne";
        };
    }

    /** Odznaka aktualnie oglądanej rzadkości (patrz SLOT_ODZNAKA_RZADKOSCI) - lore z licznikiem dorzuca otworzIndeks. */
    private ItemStack etykietaTieru(RybaGatunek.Rzadkosc tier) {
        Material szklo = switch (tier) {
            case ZWYKLA -> Material.WHITE_STAINED_GLASS_PANE;
            case NIEZWYKLA -> Material.LIME_STAINED_GLASS_PANE;
            case RZADKA -> Material.MAGENTA_STAINED_GLASS_PANE;
            case EPICKA -> Material.PURPLE_STAINED_GLASS_PANE;
            case LEGENDARNA -> Material.ORANGE_STAINED_GLASS_PANE;
            case MITYCZNA -> Material.CYAN_STAINED_GLASS_PANE;
        };
        ItemStack item = new ItemStack(szklo);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwaTieru(tier), NamedTextColor.GRAY, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    /** Placeholder nieodkrytego gatunku - CELOWO tylko czerwona szyba, bez nazwy/lore, żeby niczego nie zdradzić (user 2026-08-29). */
    private ItemStack nieodkrytaIkona() {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    /** Ikona jednego odkrytego gatunku w GUI indeksu - bazowy item (patrz bazowyItemRyby) plus lore z liczbą sztuk i osobistym rekordem wagi TEGO gracza (nie mylić z wagą pojedynczej złowionej ryby w stworzRybe). */
    private ItemStack iconaIndeksu(RybaGatunek gatunek, FishingStatsManager.WpisIndeksu wpis) {
        ItemStack item = bazowyItemRyby(gatunek);

        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.text("Złowionych: " + wpis.zlowionychSztuk() + " szt.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Twój rekord: " + String.format(Locale.ROOT, "%.1f", wpis.rekordKg()) + " kg", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Obsługa GUI /rybindeks: "Zamknij" zamyka, strzałki poprzednia/następna przełączają
     * na sąsiednią rzadkość (patrz otworzIndeks(player, tier)) - ponowne otwarcie
     * inventory wewnątrz handlera klika, ten sam wzorzec co paginacja w
     * MarketManager.onInventoryClick. Klikanie samych rybek/odznaki nic nie robi, to
     * czysto podgląd.
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String tytul = event.getView().title().toString();
        if (!tytul.contains(TYTUL_INDEKSU)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        if (slot == SLOT_ZAMKNIJ_INDEKSU) {
            player.closeInventory();
            return;
        }

        List<RybaGatunek.Rzadkosc> tiery = rzadkosciZGatunkami();
        RybaGatunek.Rzadkosc aktualny = tierIndeksuGracza.get(player.getUniqueId());
        int indeks = tiery.indexOf(aktualny);
        if (indeks < 0) return;

        if (slot == SLOT_POPRZEDNIA_RZADKOSC && indeks > 0) {
            otworzIndeks(player, tiery.get(indeks - 1));
        } else if (slot == SLOT_NASTEPNA_RZADKOSC && indeks < tiery.size() - 1) {
            otworzIndeks(player, tiery.get(indeks + 1));
        }
    }

    /** Jedyna rola tego handlera: przekazać rytmiczne PPM gracza do jego aktywnej minigry (patrz FishingMinigame.kliknij). */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        FishingMinigame gra = aktywneMinigry.get(event.getPlayer().getUniqueId());
        if (gra == null) return;

        event.setCancelled(true);
        gra.kliknij();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        FishingMinigame gra = aktywneMinigry.remove(uuid);
        if (gra != null) gra.przerwij();

        zakonczEfektPolowu(uuid);
        oczekujaceGatunki.remove(uuid);
    }
}
