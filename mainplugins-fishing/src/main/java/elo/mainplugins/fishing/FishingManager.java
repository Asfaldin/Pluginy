package elo.mainplugins.fishing;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.util.CustomItemKeys;
import elo.mainplugins.fishing.config.FishingConfig;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
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
import org.bukkit.command.CommandSender;
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
 * ale gdy przychodzi BITE (branie) W OBRĘBIE łowiska (patrz LowiskoManager - WŁASNY,
 * niezależny system stref w tym module, /@lowisko - zastąpił dawną flagę ryby-dozwolone
 * z mainplugins-spawn, patrz jego javadoc),
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

    // Awaryjny wylacznik WSZYSTKICH publicznych ogloszen na czacie zwiazanych z rybami
    // (rekord gatunku/serwera, skompletowanie indeksu - patrz ogloszRekordGatunku/
    // ogloszRekordSerwera/ogloszUkonczenieIndeksu) - user 2026-08-30: "gdyby ktos znalazl
    // jakiegos buga" (np. zly polow spamujacy caly serwer ogloszeniami). Komenda
    // /@rybykomunikaty w MainpluginsFishing. CELOWO tylko w pamieci (nie w pliku) - to
    // doraznie narzedzie na czas trwania problemu, nie trwale ustawienie; restart serwera
    // sam w sobie odblokowuje. NIE wycisza prywatnej wiadomosci "Zlowiles: ..." (patrz
    // nagrodaZaPolow) - to nie jest ryzyko spamu calego serwera, tylko normalny feedback
    // dla samego lowiacego.
    private volatile boolean komunikatyZablokowane = false;

    // Bezdenne Wiaderko (patrz WiaderkoManager, user 2026-08-30) - ryby przy udanym
    // polowie leca NAJPIERW tutaj (patrz nagrodaZaPolow), zanim trafia do zwyklego
    // ekwipunku. Wstrzykiwane jak statystyki wyzej - MainpluginsFishing rejestruje je
    // jako osobny Listener (wlasna obsluga GUI/PPM), FishingManager tylko z niego korzysta.
    private final WiaderkoManager wiaderko;

    // Strefy łowisk (patrz LowiskoManager, /@lowisko) - WŁASNY system tego modułu, zastąpił
    // 2026-08-31c dawną flagę ryby-dozwolone z mainplugins-spawn (ObszarService, usunięty).
    // Wstrzykiwany jak wiaderko/statystyki wyzej - MainpluginsFishing rejestruje go jako
    // osobny Listener (własna obsługa różdżki), FishingManager tylko z niego korzysta (patrz onFish).
    private final LowiskoManager lowiska;

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

    // Tag na wędkach TESTOWYCH RZADKOŚCI (patrz stworzWedkeTestowa/@wedka<rzadkosc> w
    // MainpluginsFishing) - trzyma nazwę stałej RybaGatunek.Rzadkosc, żeby onFish mógł
    // wymusić LOSOWY gatunek z TEJ rzadkości zamiast losować spośród wszystkich, i
    // przyspieszyć branie (patrz FishHook.setWaitTime) do testów. WYŁĄCZNIE do testów na
    // permisji mainplugins.fishing.admin - normalna /wedka tego tagu nie ma.
    private final NamespacedKey tagWedkiTestowej;

    // Tag na wędkach TESTOWYCH PROFILU (patrz stworzWedkeProfilTestowa/@wedka1 @wedka2 @wedka3
    // w MainpluginsFishing) - trzyma nazwę stałej WedkaProfil. W odróżnieniu od tagu wyżej
    // NIE wymusza gatunku - normalne losowanie (patrz losujRybe) dalej działa, tylko z
    // biasem tego profilu, żeby dało się realnie przetestować wpływ wędki na szanse na
    // gatunek, a nie tylko na fizykę suwaka.
    private final NamespacedKey tagProfiluTestowego;

    // Tag na SAMYM GRACZU (nie na itemie) - preferencja gdzie ma się wyświetlać pasek
    // minigry (patrz PozycjaPaska, komenda /rybpasek w MainpluginsFishing). Trwały
    // (PersistentDataContainer gracza), więc pamiętany między sesjami bez osobnego pliku.
    private final NamespacedKey tagPozycjaPaska;

    // Tag na SAMYM GRACZU - styl paska (tekstowy vs graficzny, patrz StylPaska) - user
    // 2026-08-30. Rozłączny od tagPozycjaPaska/tagStronaPaska nizej: tekstowy uzywa
    // tagPozycjaPaska (gora/dol), graficzny uzywa tagStronaPaska (lewo/prawo).
    private final NamespacedKey tagStylPaska;
    private final NamespacedKey tagStronaPaska;

    public FishingManager(Plugin plugin, FishingConfig config, FishingStatsManager statystyki, WiaderkoManager wiaderko, LowiskoManager lowiska) {
        this.plugin = plugin;
        this.config = config;
        this.statystyki = statystyki;
        this.wiaderko = wiaderko;
        this.lowiska = lowiska;
        this.tagWedkiTestowej = new NamespacedKey(plugin, "wedka_test_indeks");
        this.tagProfiluTestowego = new NamespacedKey(plugin, "wedka_test_profil");
        this.tagPozycjaPaska = new NamespacedKey(plugin, "fishing_pozycja_paska");
        this.tagStylPaska = new NamespacedKey(plugin, "fishing_styl_paska");
        this.tagStronaPaska = new NamespacedKey(plugin, "fishing_strona_paska");
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

    /**
     * Patrz tagStylPaska - StylPaska.TEKSTOWY (dotychczasowe, jedyne zachowanie sprzed 2026-08-30)
     * jeśli gracz nigdy nic nie ustawiał.
     *
     * TYMCZASOWO ZAWSZE TEKSTOWY (user 2026-08-31b) - przełącznik w Ustawieniach schowany
     * (patrz otworzUstawienia), bo suwak tekstowy obok ładnego tła graficznego wygląda
     * niespójnie ("hujnia"), do ogarnięcia w przyszłości lepszą grafiką. Zapisany tag
     * gracza (jeśli ktoś zdążył ustawić GRAFICZNY zanim schowaliśmy przełącznik) jest
     * celowo ignorowany, żeby nikt nie utknął na wyłączonej opcji.
     */
    public StylPaska stylPaska(Player player) {
        return StylPaska.TEKSTOWY;
    }

    /** Patrz tagStylPaska - przełącznik w Ustawieniach (patrz otworzUstawienia). */
    public void ustawStylPaska(Player player, StylPaska styl) {
        player.getPersistentDataContainer().set(tagStylPaska, PersistentDataType.STRING, styl.name());
    }

    /** Patrz tagStronaPaska - StronaPaska.PRAWO domyślnie jeśli gracz nigdy nic nie ustawiał. */
    public StronaPaska stronaPaska(Player player) {
        String nazwa = player.getPersistentDataContainer().get(tagStronaPaska, PersistentDataType.STRING);
        if (nazwa == null) return StronaPaska.PRAWO;
        try {
            return StronaPaska.valueOf(nazwa);
        } catch (IllegalArgumentException e) {
            return StronaPaska.PRAWO;
        }
    }

    /** Patrz tagStronaPaska - przełącznik w Ustawieniach (patrz otworzUstawienia). */
    public void ustawStronePaska(Player player, StronaPaska strona) {
        player.getPersistentDataContainer().set(tagStronaPaska, PersistentDataType.STRING, strona.name());
    }

    /** Przeladowuje tuning minigry/bonusowej skrzynki (fishing-config.yml) i gatunki ryb (ryby.yml) - patrz komenda @reloadfishing. */
    public void aktualizujKonfiguracje(FishingConfig nowy) {
        this.config = nowy;
        wczytajGatunki();
    }

    /** Patrz komunikatyZablokowane - komenda /@rybykomunikaty w MainpluginsFishing. */
    public boolean czyKomunikatyZablokowane() {
        return komunikatyZablokowane;
    }

    /** Patrz komunikatyZablokowane - komenda /@rybykomunikaty w MainpluginsFishing. */
    public void ustawKomunikatyZablokowane(boolean zablokowane) {
        this.komunikatyZablokowane = zablokowane;
    }

    /** Wszystkie znane gatunki, w KANONICZNEJ kolejnosci z ryby.yml - patrz /rybiemenu w MainpluginsFishing (kolejnosc wyswietlania indeksu). Defensywna kopia. */
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
     * WYŁĄCZNIE do testów (patrz /wedka<rzadkosc> w MainpluginsFishing, za permisją
     * mainplugins.fishing.admin) - wędka otagowana daną RybaGatunek.Rzadkosc. W łowisku
     * onFish rozpoznaje tag i: (1) wymusza LOSOWY gatunek z TEJ rzadkości zamiast losować
     * spośród wszystkich, (2) ustawia FishHook.setWaitTime na prawie natychmiastowe branie
     * - żeby nie czekać za każdym razem na wanilijski losowy timer przy testowaniu
     * minigry/efektów danej rzadkości. Dodatkowo niesie też tag profilu TESTOWA_OP (patrz
     * WedkaProfil, tagProfiluTestowego, user 2026-08-30: "mega mocne, żeby było prosto
     * nimi wyłowić ryby") - fizyka minigry na najłatwiejszej granicy niezależnie od
     * rzadkości (maksymalnie szeroki suwak, prawie nieruchoma ryba), żeby połów był
     * banalny nawet dla MITYCZNEJ.
     */
    public ItemStack stworzWedkeTestowa(RybaGatunek.Rzadkosc rzadkosc) {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Wędka Testowa [" + rzadkosc + "]", NamedTextColor.YELLOW, TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Wymusza LOSOWY gatunek rzadkości " + rzadkosc + " z ryby.yml", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("i prawie natychmiastowe branie w łowisku.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Fizyka minigry: tryb OP (banalnie łatwe).", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(tagWedkiTestowej, PersistentDataType.STRING, rzadkosc.name());
        meta.getPersistentDataContainer().set(tagProfiluTestowego, PersistentDataType.STRING, WedkaProfil.TESTOWA_OP.name());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * WYŁĄCZNIE do testów (patrz /wedka1 /wedka2 /wedka3 w MainpluginsFishing, za
     * permisją mainplugins.fishing.admin) - wędka otagowana danym WedkaProfil. W
     * odróżnieniu od stworzWedkeTestowa wyżej NIE wymusza gatunku - onFish dalej losuje
     * normalnie (patrz losujRybe), tylko z biasem tego profilu, i minigra (patrz
     * FishingMinigame) dostaje jego mnożniki fizyki suwaka. Branie w łowisku i tak jest
     * prawie natychmiastowe (patrz onFish/trzymaWedkeTestowa) - user 2026-08-31b: "niech
     * do każdej od razu płynie ryba obojętnie jaka", żeby dało się szybko testować różnice
     * fizyki bez czekania na wanilijski timer.
     *
     * Każdy z 3 numerowanych profili dostaje własny przefarbowany wygląd z resourcepacka
     * (patrz assets/mainplugins/items/wedka1.json, wedka2.json, wedka3.json - zielona/
     * niebieska/czerwona) - user chciał "żeby był inny kolor", głównie żeby łatwiej
     * rozróżniać/budować docelowe custom wędki później.
     */
    public ItemStack stworzWedkeProfilTestowa(WedkaProfil profil) {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        Key model = switch (profil) {
            case ZROWNOWAZONA -> Key.key("mainplugins", "wedka1");
            case CIERPLIWA -> Key.key("mainplugins", "wedka2");
            case SZARPANA -> Key.key("mainplugins", "wedka3");
            default -> null; // TESTOWA_OP - tu nigdy nie trafia, nie ma wlasnej komendy/koloru
        };
        if (model != null) item.setData(DataComponentTypes.ITEM_MODEL, model);

        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Wędka " + profil.nazwa() + " [TEST]", profil.kolor(), TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Profil testowy: " + profil.nazwa() + ".", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Normalne losowanie gatunku, z biasem tego profilu,", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("i prawie natychmiastowe branie w łowisku.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
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
     * iconaIndeksu (GUI /rybiemenu), ktore dorzucaja WLASNE, rozne linie lore na tej samej bazie.
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
     * Waga bazowa - jeśli łowisko ma WŁASNĄ, niepustą listę gatunków (patrz Lowisko,
     * LowiskoManager), to ONA wygrywa (a gatunek spoza tej listy dostaje 0, patrz
     * losujRybe - filtrowany zanim tu w ogóle trafi, ale 0 jako bezpiecznik), w przeciwnym
     * razie (łowisko bez własnej listy, albo losowanie poza jakimkolwiek łowiskiem - patrz
     * wymuszonyGatunekZWedkiTestowej) leci zwykła waga z ryby.yml - user 2026-08-31c: "pula
     * domyślna", zeby nowe/nieskonfigurowane łowisko nie blokowało łowienia.
     *
     * Przemnożona przez profil.mnoznikRzadkosci() do potęgi rzadkości gatunku (patrz
     * WedkaProfil) - ZWYKLA (ordinal 0) zawsze wychodzi bez zmian, każdy kolejny stopień
     * rzadkości mnoży się profilem o kolejną potęgę. Stąd double zamiast int (mnożenie wag
     * przestaje dawać liczby całkowite).
     */
    private double wagaEfektywna(RybaGatunek g, WedkaProfil profil, Lowisko lowisko) {
        boolean maWlasnaListe = lowisko != null && !lowisko.gatunki.isEmpty();
        int bazowa = maWlasnaListe ? lowisko.gatunki.getOrDefault(g.customId(), 0) : g.waga();
        return bazowa * Math.pow(profil.mnoznikRzadkosci(), g.rzadkosc().ordinal());
    }

    /**
     * `lowisko` - patrz LowiskoManager.znajdzLowiskoPod (null jeśli losujemy poza
     * jakimkolwiek łowiskiem, np. wędka testowa rzadkości używana gdziekolwiek - wtedy
     * zawsze pełna pula, tak jak wcześniej).
     */
    private RybaGatunek losujRybe(WedkaProfil profil, Lowisko lowisko) {
        if (gatunki.isEmpty()) return null;
        boolean maWlasnaListe = lowisko != null && !lowisko.gatunki.isEmpty();

        double suma = 0;
        for (RybaGatunek g : gatunki) {
            if (maWlasnaListe && !lowisko.gatunki.containsKey(g.customId())) continue;
            suma += wagaEfektywna(g, profil, lowisko);
        }
        if (suma <= 0) return null; // lowisko ma liste, ale zaden wpis nie pasuje do zadnego gatunku w ryby.yml (literowka custom-id) - bezpiecznik

        double los = ThreadLocalRandom.current().nextDouble(suma);
        double akumulator = 0;
        RybaGatunek ostatniPasujacy = null;
        for (RybaGatunek g : gatunki) {
            if (maWlasnaListe && !lowisko.gatunki.containsKey(g.customId())) continue;
            ostatniPasujacy = g;
            akumulator += wagaEfektywna(g, profil, lowisko);
            if (los < akumulator) return g;
        }
        return ostatniPasujacy; // zabezpieczenie na blad zaokraglenia (patrz gatunki.getLast() sprzed filtra po lowiskach)
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

    /**
     * Czy gracz trzyma JAKĄKOLWIEK wędkę testową (rzadkości LUB profilu - obie niosą tag
     * tagProfiluTestowego, patrz stworzWedkeTestowa/stworzWedkeProfilTestowa) - patrz
     * onFish, przyspieszone branie dla wszystkich wędek testowych, nie tylko rzadkości.
     */
    private boolean trzymaWedkeTestowa(Player player) {
        ItemStack wRece = player.getInventory().getItemInMainHand();
        return wRece.hasItemMeta() && wRece.getItemMeta().getPersistentDataContainer().has(tagProfiluTestowego, PersistentDataType.STRING);
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

        Lowisko lowisko = lowiska.znajdzLowiskoPod(event.getHook().getLocation());
        if (lowisko == null) return;
        if (gatunki.isEmpty()) return; // ryby.yml bez gatunkow - zostaw wanilijskie lowienie

        if (event.getState() == PlayerFishEvent.State.FISHING) {
            WedkaProfil profil = profilZWedki(event.getPlayer());
            RybaGatunek wymuszony = wymuszonyGatunekZWedkiTestowej(event.getPlayer());
            RybaGatunek gatunek = wymuszony != null ? wymuszony : losujRybe(profil, lowisko);
            if (gatunek == null) return; // ryby.yml pusty/uszkodzony, albo lista gatunkow lowiska nie pasuje do zadnego - zostawiamy wanilijskie łowienie

            // Zapis PRZED próbą przyspieszenia brania - jeśli setWaitTime akurat rzuci
            // wyjątek (nieudokumentowane ograniczenie tej wersji MC), wymuszony gatunek
            // i tak zostaje zapamiętany, zamiast po cichu spaść do losowania na BITE.
            oczekujaceGatunki.put(uuid, new OczekujacyPolow(gatunek, profil));

            if (wymuszony != null) {
                event.getPlayer().sendMessage(Component.text("[TEST] Wymuszono: " + gatunek.nazwa(), NamedTextColor.YELLOW));
            }
            // Przyspieszone branie dla KAŻDEJ wędki testowej (rzadkości LUB profilu, patrz
            // trzymaWedkeTestowa) - user 2026-08-31b chciał tego też dla /wedka1 /wedka2
            // /wedka3 (profile), nie tylko dla wędek testowych rzadkości jak dotychczas.
            if (wymuszony != null || trzymaWedkeTestowa(event.getPlayer())) {
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
            RybaGatunek gatunek = wymuszony != null ? wymuszony : losujRybe(profil, lowisko);
            oczekujacy = gatunek != null ? new OczekujacyPolow(gatunek, profil) : null;
        }
        if (oczekujacy == null) return;

        event.setCancelled(true);
        event.getHook().remove();

        rozpocznijMinigre(event.getPlayer(), oczekujacy.gatunek(), oczekujacy.profil(), lokalizacjaHaka);
    }

    /**
     * Patrz stworzWedkeTestowa - null jeśli gracz nie trzyma wędki testowej rzadkości albo
     * ryby.yml akurat nie ma ŻADNEGO gatunku tej rzadkości (np. po edycji ryby.yml). Gdy
     * jest ich kilka (docelowo 5 na rzadkość, patrz ryby.yml), losuje jeden z nich - żeby
     * dało się przetestować całą pulę danej rzadkości, nie tylko pierwszy wpis.
     */
    private RybaGatunek wymuszonyGatunekZWedkiTestowej(Player player) {
        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (!wRece.hasItemMeta()) return null;

        String nazwaRzadkosci = wRece.getItemMeta().getPersistentDataContainer().get(tagWedkiTestowej, PersistentDataType.STRING);
        if (nazwaRzadkosci == null) return null;

        RybaGatunek.Rzadkosc rzadkosc;
        try {
            rzadkosc = RybaGatunek.Rzadkosc.valueOf(nazwaRzadkosci);
        } catch (IllegalArgumentException e) {
            return null;
        }

        List<RybaGatunek> pula = gatunki.stream().filter(g -> g.rzadkosc() == rzadkosc).toList();
        if (pula.isEmpty()) return null;

        return pula.get(ThreadLocalRandom.current().nextInt(pula.size()));
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

        FishingMinigame gra = new FishingMinigame(plugin, player, gatunek, profil, config.minigra(), pozycjaPaska(player), stylPaska(player), stronaPaska(player),
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

    /**
     * WYŁĄCZNIE stąd wolno wołać statystyki.zanotujPolow (patrz jedyne wywołanie niżej) -
     * ta metoda jest wołana TYLKO z callbacku sukcesu minigry (patrz rozpocznijMinigre),
     * czyli z realnego, ręcznego przejścia minigry po prawdziwym BITE w łowisku. To
     * CELOWE zabezpieczenie indeksu/statystyk (user 2026-08-30: gracze będą mogli
     * sprzedawać ryby na targu, więc samo POSIADANIE gatunku - kupno, prezent od gracza,
     * przejście przez dozownik/podajnik itp. - NIE MOŻE nigdy zaliczać go do indeksu).
     * Jeśli kiedyś powstanie sprzedaż ryb na targu (mainplugins-market) albo jakikolwiek
     * inny system czytający ten custom-item, NIE WOLNO mu wołać zanotujPolow - indeks ma
     * zostać zdobywany WYŁĄCZNIE przez realne złowienie.
     */
    private void nagrodaZaPolow(Player player, RybaGatunek zlowiona) {
        int wagaDziesieteKg = losujWageDziesieteKg(zlowiona);

        // Ryba leci NAJPIERW do Bezdennego Wiaderka, jesli gracz je gdziekolwiek ma (patrz
        // WiaderkoManager.sprobujWlozycRybe) - dopiero gdy wiaderko jest pelne (albo go nie
        // ma), normalna sciezka do ekwipunku/dropa jak dotychczas (user 2026-08-30).
        ItemStack ryba = stworzRybe(zlowiona, wagaDziesieteKg);
        ItemStack pozostalo = wiaderko.sprobujWlozycRybe(player, ryba);
        if (pozostalo != null) {
            var nieZmieszczone = player.getInventory().addItem(pozostalo);
            nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        }

        player.sendMessage(Component.text("Złowiłeś: ", NamedTextColor.GREEN)
                .append(Component.text(zlowiona.nazwa(), zlowiona.kolor(), TextDecoration.BOLD))
                .append(Component.text(" (" + formatKg(wagaDziesieteKg) + " kg)", NamedTextColor.GRAY)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        rzucBonusowaSkrzynke(player);

        // Sprawdzone PRZED zapisem polowu - czy TEN gatunek byl dla tego gracza jeszcze
        // nieodkryty (patrz ogloszUkonczenieIndeksu nizej: strzelamy ogloszeniem o
        // skompletowaniu WYLACZNIE w momencie zlowienia ostatniego brakujacego gatunku,
        // nie za kazdym kolejnym polowem po fakcie).
        boolean nowyGatunekWIndeksie = statystyki.getIndeks(player.getUniqueId()).stream()
                .noneMatch(wpis -> wpis.customId().equals(zlowiona.customId()));

        statystyki.zanotujPolow(player.getUniqueId(), player.getName(), zlowiona.customId(), zlowiona.nazwa(), wagaDziesieteKg);

        FishingStatsManager.NowyRekordGatunku rekord = statystyki.zanotujRekordGatunkuJesliNowy(zlowiona.customId(), zlowiona.nazwa(), wagaDziesieteKg, player.getUniqueId(), player.getName());
        if (rekord != null) ogloszRekordGatunku(player, zlowiona, wagaDziesieteKg, rekord);

        // Rekord SERWERA (dowolny gatunek, patrz FishingStatsManager.getRekordSerwera) -
        // osobny od rekordu gatunku wyzej, z WLASNYM ogloszeniem na czacie (user
        // 2026-08-30: chcial wyroznienie ze to "najwieksza ryba na serwerze", nie tylko
        // widoczne w /rybtop). Ta sama zasada remisu co rekord gatunku (scisle ">").
        FishingStatsManager.NowyRekordSerwera rekordSerwera = statystyki.zanotujRekordSerweraJesliNowy(player.getUniqueId(), player.getName(), zlowiona.nazwa(), wagaDziesieteKg);
        if (rekordSerwera != null) ogloszRekordSerwera(player, zlowiona, wagaDziesieteKg, rekordSerwera);

        if (nowyGatunekWIndeksie && !gatunki.isEmpty() && statystyki.getIndeks(player.getUniqueId()).size() == gatunki.size()) {
            ogloszUkonczenieIndeksu(player);
        }
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
        if (komunikatyZablokowane) return;
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

    /**
     * Ogłoszenie na czacie CAŁEGO serwera przy pobiciu OGÓLNEGO rekordu serwera - dowolny
     * gatunek, patrz FishingStatsManager.zanotujRekordSerweraJesliNowy (user 2026-08-30:
     * "wyróżnienie że to największa ryba na serwerze"). CELOWO inne hasło/emoji (👑, nie
     * 🏆) niż ogloszRekordGatunku wyżej - tamto ogłoszenie mówi "rekord serwera" ale ma na
     * myśli TYLKO dany gatunek, więc to musi się jednoznacznie odróżniać, żeby graczy nie
     * zmyliło który rekord właśnie padł. Ten sam wzorzec "pierwszy vs. pobił czyjś" co tam.
     */
    private void ogloszRekordSerwera(Player player, RybaGatunek gatunek, int wagaDziesieteKg, FishingStatsManager.NowyRekordSerwera rekord) {
        if (komunikatyZablokowane) return;
        Component naglowek = Component.text("👑 ", NamedTextColor.GOLD);
        Component polow = Component.text(gatunek.nazwa() + " (" + formatKg(wagaDziesieteKg) + " kg)", gatunek.kolor(), TextDecoration.BOLD);

        if (rekord.pierwszy()) {
            Bukkit.broadcast(naglowek
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" złowił ", NamedTextColor.GOLD))
                    .append(polow)
                    .append(Component.text(" — NAJWIĘKSZA ryba w historii serwera!", NamedTextColor.GOLD, TextDecoration.BOLD)));
        } else {
            Component poprzedni = Component.text(" (poprzedni rekord: " + rekord.poprzedniGatunek() + ", "
                    + String.format(Locale.ROOT, "%.1f", rekord.poprzedniaWagaKg()) + " kg"
                    + (rekord.poprzedniNick() != null ? ", " + rekord.poprzedniNick() : "") + ")", NamedTextColor.GRAY);
            Bukkit.broadcast(naglowek
                    .append(Component.text(player.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                    .append(Component.text(" POBIŁ REKORD CAŁEGO SERWERA! ", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(polow)
                    .append(poprzedni));
        }
    }

    /**
     * Ogłoszenie na czacie CAŁEGO serwera przy SKOMPLETOWANIU całego indeksu rybackiego -
     * gracz złowił przynajmniej raz KAŻDY znany gatunek (patrz gatunki) - user 2026-08-30:
     * "informacja na czacie, z takim wyróżnieniem". Ten sam "świąteczny"
     * pogrubiony/kolorowy styl co ogloszRekordGatunku, ale odrębne hasło/emoji, żeby
     * odróżnić od zwykłego rekordu gatunku - to rzadsze i większe osiągnięcie. Wywoływane
     * WYŁĄCZNIE raz (patrz nagrodaZaPolow: dokładnie w momencie złowienia ostatniego
     * brakującego gatunku), nie przy każdym kolejnym połowie po skompletowaniu.
     */
    private void ogloszUkonczenieIndeksu(Player player) {
        if (komunikatyZablokowane) return;
        Bukkit.broadcast(Component.text("🌊🏆🌊 ", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text(player.getName(), NamedTextColor.YELLOW, TextDecoration.BOLD))
                .append(Component.text(" SKOMPLETOWAŁ CAŁY INDEKS RYBACKI! ", NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("Wszystkie " + gatunki.size() + " gatunków złowione!", NamedTextColor.AQUA))
                .append(Component.text(" 🌊🏆🌊", NamedTextColor.AQUA, TextDecoration.BOLD)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
    }

    /**
     * Ranking wędkarski na czat - przeniesiony tu z MainpluginsFishing (user 2026-08-30:
     * chciał ten sam ranking dostępny też jako przycisk w Dzienniku Rybaka, patrz
     * SLOT_MENU_RYBTOP). Top 10 to NAJCIĘŻSZE POJEDYNCZE połowy na serwerze, dowolny
     * gracz/gatunek (patrz FishingStatsManager.getTopPolowy) - user 2026-08-30 świadomie
     * NIE chciał tu sumy złowionych kg (ta funkcja - getTop/TopRybak - zostaje w
     * FishingStatsManager nieużywana, ale zachowana). Rekord serwera to ten sam
     * najcięższy pojedynczy połów, tylko wyróżniony osobną linią na górze. Stopka -
     * osobisty najcięższy połów wywołującego, jeśli to gracz (konsola dostaje samą topkę).
     */
    public void wyslijRanking(CommandSender sender) {
        sender.sendMessage(Component.text("=== Ranking wędkarski ===", NamedTextColor.AQUA, TextDecoration.BOLD));

        FishingStatsManager.RekordSerwera rekord = statystyki.getRekordSerwera();
        if (rekord != null) {
            sender.sendMessage(Component.text("Rekord serwera: ", NamedTextColor.GOLD)
                    .append(Component.text(rekord.nick(), NamedTextColor.YELLOW))
                    .append(Component.text(" - " + String.format(Locale.ROOT, "%.1f", rekord.wagaKg()) + " kg (" + rekord.gatunek() + ")", NamedTextColor.GRAY)));
        }

        List<FishingStatsManager.TopPolow> top = statystyki.getTopPolowy(10);
        if (top.isEmpty()) {
            sender.sendMessage(Component.text("Nikt jeszcze nic nie złowił w łowisku.", NamedTextColor.GRAY));
        } else {
            int miejsce = 1;
            for (FishingStatsManager.TopPolow polow : top) {
                sender.sendMessage(Component.text(miejsce + ". ", NamedTextColor.GRAY)
                        .append(Component.text(polow.nick(), NamedTextColor.WHITE))
                        .append(Component.text(" - " + polow.gatunek() + " (" + String.format(Locale.ROOT, "%.1f", polow.kg()) + " kg)", NamedTextColor.GREEN)));
                miejsce++;
            }
        }

        if (sender instanceof Player player) {
            FishingStatsManager.OsobistyRekord osobisty = statystyki.getOsobistyRekord(player.getUniqueId());
            if (osobisty.kg() > 0) {
                sender.sendMessage(Component.text("Twój najcięższy połów: ", NamedTextColor.YELLOW)
                        .append(Component.text(String.format(Locale.ROOT, "%.1f", osobisty.kg()) + " kg (" + osobisty.gatunek() + ")", NamedTextColor.GRAY)));
            }
        }
    }

    // ==================================================================== GUI: Dziennik Rybaka (menu glowne) ====

    // Rozpoznawcza fraza w tytule GUI - ten sam wzorzec co TYTUL_INDEKSU nizej.
    private static final String TYTUL_MENU_GLOWNEGO = "Dziennik Rybaka";
    private static final int SLOT_MENU_DZIENNIK = 20; // dwa w lewo od Indeksu - user 2026-08-30
    private static final int SLOT_MENU_INDEKS = 22; // srodek planszy 6x9 (patrz otworzMenuGlowne) - user 2026-08-30: opcje maja byc "centralnie na srodku"
    private static final int SLOT_MENU_RYBTOP = 24; // dwa w prawo od Indeksu - user 2026-08-30 chcial tu tez /rybtop
    private static final int SLOT_MENU_USTAWIENIA = 31; // pod Indeksem - user 2026-08-30
    private static final int SLOT_MENU_ZAMKNIJ = 49; // pufferfish na dole - tu NIE ma dokad wracac, wiec calkiem zamyka

    /**
     * Otwiera "Dziennik Rybaka" - glowny hub /rybiemenu (patrz MainpluginsFishing).
     * Centralny "krzyz" opcji: Dziennik Polowow (patrz otworzDziennik), Indeks Rybacki
     * (patrz otworzIndeks), Ranking Wedkarski (patrz wyslijRanking) w srodkowym rzedzie,
     * Ustawienia (patrz otworzUstawienia) pod Indeksem - user 2026-08-30. Tlo "morskie" -
     * naprzemienne odcienie niebiesko-turkusowej szyby (patrz morskaSzyba) plus
     * rozrzucone NIE symetrycznie wodorosty/koralowce (patrz dekoracjeMenuGlownego) jako
     * czysto ozdobne itemy bez zadnej funkcji.
     */
    public void otworzMenuGlowne(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(TYTUL_MENU_GLOWNEGO, NamedTextColor.AQUA, TextDecoration.BOLD));

        for (int i = 0; i < 54; i++) gui.setItem(i, morskaSzyba(i));
        dekoracjeMenuGlownego().forEach((slot, material) -> gui.setItem(slot, ozdobaMorska(material)));

        ItemStack dziennik = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta mDziennik = dziennik.getItemMeta();
        mDziennik.displayName(Component.text("Dziennik Połowów", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        mDziennik.lore(List.of(
                Component.text("Chronologiczna lista Twoich ostatnich połowów.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Kliknij, aby otworzyć.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        dziennik.setItemMeta(mDziennik);
        gui.setItem(SLOT_MENU_DZIENNIK, dziennik);

        ItemStack indeks = new ItemStack(Material.NAUTILUS_SHELL);
        ItemMeta mIndeks = indeks.getItemMeta();
        mIndeks.displayName(Component.text("Indeks Rybacki", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        mIndeks.lore(List.of(
                Component.text("Wszystkie gatunki ryb, które już odkryłeś.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Kliknij, aby otworzyć.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        indeks.setItemMeta(mIndeks);
        gui.setItem(SLOT_MENU_INDEKS, indeks);

        ItemStack rybtop = new ItemStack(Material.GOLD_INGOT);
        ItemMeta mRybtop = rybtop.getItemMeta();
        mRybtop.displayName(Component.text("🏆 Ranking Wędkarski", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        mRybtop.lore(List.of(
                Component.text("Top 10 najcięższych pojedynczych połowów.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Kliknij, aby zobaczyć na czacie.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        rybtop.setItemMeta(mRybtop);
        gui.setItem(SLOT_MENU_RYBTOP, rybtop);

        ItemStack ustawienia = new ItemStack(Material.COMPASS);
        ItemMeta mUstawienia = ustawienia.getItemMeta();
        mUstawienia.displayName(Component.text("Ustawienia", NamedTextColor.YELLOW, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        mUstawienia.lore(List.of(
                Component.text("Preferencje łowienia.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Kliknij, aby otworzyć.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        ustawienia.setItemMeta(mUstawienia);
        gui.setItem(SLOT_MENU_USTAWIENIA, ustawienia);

        gui.setItem(SLOT_MENU_ZAMKNIJ, przyciskPufferfish("Zamknij"));

        player.openInventory(gui);
    }

    // ==================================================================== GUI: Dziennik Połowów ====

    private static final String TYTUL_DZIENNIK = "Dziennik Połowów";
    private static final int SLOT_DZIENNIK_ODZNAKA = 4;
    private static final int SLOT_WROC_Z_DZIENNIKA = 49;
    private static final int DZIENNIK_WPIS_START = 9; // 4 pelne rzedy (9-44) na wpisy, patrz otworzDziennik
    private static final int DZIENNIK_WPIS_KONIEC = 44;

    /**
     * Otwiera "Dziennik Połowów" - chronologiczna lista OSTATNICH połowów gracza (patrz
     * FishingStatsManager.getHistoria), NAJNOWSZY pierwszy - w odróżnieniu od Indeksu to
     * NIE kolekcja gatunków (jeden wpis na gatunek na zawsze), tylko log pojedynczych
     * połowów z wagą i czasem (user 2026-08-30). Do 36 najnowszych na ekranie (tyle ile
     * mieści środkowa część planszy) - jeśli historia jest krótsza, reszta zostaje tłem.
     */
    public void otworzDziennik(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(TYTUL_DZIENNIK, NamedTextColor.AQUA, TextDecoration.BOLD));

        for (int i = 0; i < 54; i++) gui.setItem(i, morskaSzyba(i));
        dekoracjeMenuGlownego().forEach((slot, material) -> {
            if (slot < DZIENNIK_WPIS_START || slot > DZIENNIK_WPIS_KONIEC) gui.setItem(slot, ozdobaMorska(material));
        });

        List<FishingStatsManager.WpisHistorii> historia = statystyki.getHistoria(player.getUniqueId(), DZIENNIK_WPIS_KONIEC - DZIENNIK_WPIS_START + 1);

        ItemStack odznaka = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta mOdznaka = odznaka.getItemMeta();
        mOdznaka.displayName(Component.text("Dziennik Połowów", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        mOdznaka.lore(List.of(Component.text("Ostatnie " + historia.size() + " połowów, najnowszy pierwszy.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        odznaka.setItemMeta(mOdznaka);
        gui.setItem(SLOT_DZIENNIK_ODZNAKA, odznaka);

        if (historia.isEmpty()) {
            ItemStack pusto = new ItemStack(Material.BARRIER);
            ItemMeta mPusto = pusto.getItemMeta();
            mPusto.displayName(Component.text("Jeszcze nic tu nie ma", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            mPusto.lore(List.of(Component.text("Złów pierwszą rybę w łowisku!", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            pusto.setItemMeta(mPusto);
            gui.setItem(27, pusto);
        } else {
            int slot = DZIENNIK_WPIS_START;
            for (FishingStatsManager.WpisHistorii wpis : historia) {
                if (slot > DZIENNIK_WPIS_KONIEC) break;
                gui.setItem(slot, ikonaHistorii(wpis));
                slot++;
            }
        }

        gui.setItem(SLOT_WROC_Z_DZIENNIKA, przyciskPufferfish("« Wróć do menu"));
        player.openInventory(gui);
    }

    /**
     * Ikona jednego wpisu w Dzienniku Połowów - PRAWDZIWY wygląd gatunku (patrz
     * bazowyItemRyby), jeśli ten gatunek dalej istnieje w ryby.yml (dopasowanie po
     * customId, patrz WpisHistorii) - w przeciwnym razie (gatunek usunięty/zmieniony od
     * czasu połowu) prosty szary placeholder z samą zapisaną nazwą, żeby nic nie rzuciło
     * wyjątkiem. Osobna linia lore z wagą TEGO konkretnego połowu i datą/godziną.
     */
    private ItemStack ikonaHistorii(FishingStatsManager.WpisHistorii wpis) {
        RybaGatunek gatunek = gatunki.stream().filter(g -> g.customId().equals(wpis.customId())).findFirst().orElse(null);

        ItemStack item;
        if (gatunek != null) {
            item = bazowyItemRyby(gatunek);
        } else {
            item = new ItemStack(Material.COD);
            ItemMeta mBrak = item.getItemMeta();
            mBrak.displayName(Component.text(wpis.nazwaGatunku(), NamedTextColor.GRAY, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(mBrak);
        }

        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.text("Waga: " + String.format(Locale.ROOT, "%.1f", wpis.kg()) + " kg", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Złowiono: " + wpis.czas(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ==================================================================== GUI: Ustawienia ====

    private static final String TYTUL_USTAWIENIA = "Ustawienia Rybackie";
    private static final int SLOT_USTAWIENIA_STYL = 22; // srodek - patrz otworzUstawienia
    private static final int SLOT_USTAWIENIA_POZYCJA = 31; // pod stylem - TYLKO gdy TEKSTOWY (gora/dol)
    private static final int SLOT_USTAWIENIA_STRONA = 31; // pod stylem - TYLKO gdy GRAFICZNY (lewo/prawo), ten sam slot co wyzej - rozlaczne
    private static final int SLOT_WROC_Z_USTAWIEN = 49;

    /**
     * Otwiera "Ustawienia Rybackie" - styl paska (Tekstowy/Graficzny, patrz StylPaska) na
     * środku, a pod nim pozycja (góra/dół, patrz PozycjaPaska) - OBA style dzielą TĘ SAMĄ
     * pozycję od 2026-08-31 (patrz PasekObrazkowy/FishingMinigame - "wersja A" graficznego
     * paska renderuje się w BossBarze/action barze, dokładnie tak jak tekstowy, więc lewo/
     * prawo (StronaPaska - "wersja B", patrz GraficznyPasek) na razie nieużywane w GUI,
     * zostaje w kodzie na wypadek powrotu do tamtego pomysłu). Na pewno dojdzie więcej
     * ustawień w przyszłości - stąd osobna, dedykowana strona.
     */
    public void otworzUstawienia(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(TYTUL_USTAWIENIA, NamedTextColor.AQUA, TextDecoration.BOLD));

        for (int i = 0; i < 54; i++) gui.setItem(i, morskaSzyba(i));
        dekoracjeMenuGlownego().forEach((slot, material) -> gui.setItem(slot, ozdobaMorska(material)));

        // Przełącznik "Styl paska" (Tekstowy/Graficzny) SCHOWANY (user 2026-08-31b) - patrz
        // javadoc stylPaska() - zostaje wyłączony do czasu lepszej grafiki, slot pozostaje
        // pod dekoracyjną szybą zamiast itemu.

        PozycjaPaska pozycjaAktualna = pozycjaPaska(player);
        ItemStack pozycja = new ItemStack(Material.COMPASS);
        ItemMeta mPozycja = pozycja.getItemMeta();
        mPozycja.displayName(Component.text("Pozycja paska minigry", NamedTextColor.YELLOW, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        mPozycja.lore(List.of(
                Component.text("Aktualnie: " + pozycjaAktualna.opis(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Kliknij, aby przełączyć.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        pozycja.setItemMeta(mPozycja);
        gui.setItem(SLOT_USTAWIENIA_POZYCJA, pozycja);

        gui.setItem(SLOT_WROC_Z_USTAWIEN, przyciskPufferfish("« Wróć do menu"));
        player.openInventory(gui);
    }

    /** "Morska" szyba tła - naprzemienne odcienie niebieskiego/turkusowego wg slotu, BEZ losowości (ten sam układ za każdym otwarciem, patrz otworzMenuGlowne). */
    private ItemStack morskaSzyba(int slot) {
        Material[] paleta = { Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.CYAN_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE };
        Material szklo = paleta[(slot + slot / 9) % paleta.length];
        ItemStack item = new ItemStack(szklo);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    /** Czysto ozdobny item (wodorosty/koralowce) w tle menu głównego - bez nazwy, kliknięcie nic nie robi (patrz onInventoryClick). */
    private ItemStack ozdobaMorska(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Ręcznie dobrane sloty ozdób na tle Dziennika Rybaka - CELOWO NIE symetrycznie (user
     * 2026-08-30: "nie musi być symetrycznie"), mieszanka wodorostów/koralowców w kilku
     * kolorach żeby przypominało dno morskie. Sloty spoza tej mapy zostają zwykłą
     * "morską" szybą (patrz morskaSzyba).
     */
    private Map<Integer, Material> dekoracjeMenuGlownego() {
        Map<Integer, Material> ozdoby = new HashMap<>();
        ozdoby.put(0, Material.KELP);
        ozdoby.put(2, Material.SEAGRASS);
        ozdoby.put(4, Material.TUBE_CORAL_FAN);
        ozdoby.put(5, Material.KELP);
        ozdoby.put(6, Material.BRAIN_CORAL_FAN);
        ozdoby.put(8, Material.SEAGRASS);
        ozdoby.put(9, Material.SEAGRASS);
        ozdoby.put(17, Material.FIRE_CORAL_FAN);
        ozdoby.put(26, Material.HORN_CORAL_FAN);
        ozdoby.put(36, Material.KELP);
        ozdoby.put(44, Material.BUBBLE_CORAL_FAN);
        ozdoby.put(45, Material.SEAGRASS);
        ozdoby.put(46, Material.TUBE_CORAL_FAN);
        ozdoby.put(48, Material.KELP);
        ozdoby.put(50, Material.BRAIN_CORAL_FAN);
        ozdoby.put(52, Material.SEAGRASS);
        ozdoby.put(53, Material.HORN_CORAL_FAN);
        return ozdoby;
    }

    /** Wspólny przycisk wyjścia/cofnięcia obu rybackich GUI (user 2026-08-30: "na dole przyciskiem wyjścia i cofnięcia niech będzie pufferfish") - znaczenie (zamknij vs. wróć) zależy od GUI, patrz onInventoryClick. */
    private ItemStack przyciskPufferfish(String nazwa) {
        ItemStack item = new ItemStack(Material.PUFFERFISH);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
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
    private static final int SLOT_SUMA_KG = 51; // "naprzeciwko" SLOT_POSTEP_OGOLNY (symetrycznie wzgledem SLOT_WROC_Z_INDEKSU=49) - user 2026-08-30
    private static final int SLOT_WROC_Z_INDEKSU = 49; // pufferfish - wraca do Dziennika Rybaka (patrz onInventoryClick), NIE zamyka calkiem
    private static final int SLOT_POPRZEDNIA_RZADKOSC = 45;
    private static final int SLOT_NASTEPNA_RZADKOSC = 53;
    private static final int RZAD_RYB_START = 19; // srodkowy rzad (patrz otworzIndeks), 7 slotow: 19-25
    private static final int RZAD_RYB_KONIEC = 25;

    // Ktora rzadkosc dany gracz ogląda w GUI /rybiemenu TERAZ - patrz otworzIndeks(player,
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

    /** Otwiera GUI "Indeks Rybacki" na rzadkości, na której gracz ostatnio skończył (albo pierwszej dostępnej) - patrz /rybiemenu w MainpluginsFishing. */
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
     * z lore (ile sztuk + rekordy, patrz iconaIndeksu), a JESZCZE nieodkryty pokazuje
     * czerwoną szybę z napisem "Nie odkryto" (patrz nieodkrytaIkona) - zero nazwy/lore
     * samej ryby, więc niczego nie zdradza.
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

        // Tło "morskie" i ozdoby - ten sam wzorzec co Dziennik Rybaka (patrz morskaSzyba/
        // ozdobaMorska), ale user 2026-08-30 chciał INNY układ ozdób na KAŻDEJ stronie
        // (rzadkości) - patrz dekoracjeIndeksu, gęstość/kolorystyka koralowców rośnie
        // razem z rzadkością (subtelne wzmocnienie "im rzadziej, tym bardziej żywa rafa").
        for (int i = 0; i < 54; i++) gui.setItem(i, morskaSzyba(i));
        dekoracjeIndeksu(tier).forEach((slot, material) -> gui.setItem(slot, ozdobaMorska(material)));

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

        gui.setItem(SLOT_POSTEP_OGOLNY, itemPostepuOgolnego(odkryte.size(), gatunki.size()));
        gui.setItem(SLOT_SUMA_KG, itemSumyKg(player));

        if (indeksTieru > 0) gui.setItem(SLOT_POPRZEDNIA_RZADKOSC, strzalkaTieru(tiery.get(indeksTieru - 1), true));
        if (indeksTieru < tiery.size() - 1) gui.setItem(SLOT_NASTEPNA_RZADKOSC, strzalkaTieru(tiery.get(indeksTieru + 1), false));

        gui.setItem(SLOT_WROC_Z_INDEKSU, przyciskPufferfish("« Wróć do menu"));

        player.openInventory(gui);
    }

    /**
     * Przycisk przełączający na sąsiednią rzadkość - patrz otworzIndeks/onInventoryClick.
     * Kryształ Pryzmarynu (poprzednia) / Odłamek Pryzmarynu (następna) zamiast strzały
     * widmowej (user 2026-08-30: "żeby wyglądało to ładniej") - oba pasują do morskiego
     * klimatu Dziennika Rybaka, a przy okazji same w sobie ładnie się odróżniają.
     */
    private ItemStack strzalkaTieru(RybaGatunek.Rzadkosc docelowa, boolean poprzednia) {
        ItemStack item = new ItemStack(poprzednia ? Material.PRISMARINE_CRYSTALS : Material.PRISMARINE_SHARD);
        ItemMeta meta = item.getItemMeta();
        String nazwa = poprzednia ? "« " + nazwaTieru(docelowa) : nazwaTieru(docelowa) + " »";
        meta.displayName(Component.text(nazwa, NamedTextColor.YELLOW, TextDecoration.BOLD));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Odznaka ogólnego postępu w SLOT_POSTEP_OGOLNY (user 2026-08-30) - normalnie Serce
     * Oceanu, a gdy gracz odkrył WSZYSTKIE gatunki (patrz FishingManager.gatunki) zamienia
     * się na Konduit - naturalny "upgrade" tematyczny (w Minecrafcie Konduit craftuje się
     * właśnie z Serca Oceanu + muszli), z blaskiem i osobnym, świątecznym lore.
     */
    private ItemStack itemPostepuOgolnego(int odkryte, int wszystkie) {
        boolean skompletowany = wszystkie > 0 && odkryte >= wszystkie;
        ItemStack item = new ItemStack(skompletowany ? Material.CONDUIT : Material.HEART_OF_THE_SEA);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Odkryto łącznie: " + odkryte + " / " + wszystkie + " gatunków", NamedTextColor.YELLOW, TextDecoration.BOLD));
        if (skompletowany) {
            meta.lore(List.of(Component.text("Skompletowałeś CAŁY indeks rybacki!", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)));
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Odznaka sumy złowionych kg gracza (na zawsze, patrz FishingStatsManager.sumaKg) w
     * SLOT_SUMA_KG - user 2026-08-30 chciał ją "naprzeciwko" SLOT_POSTEP_OGOLNY, czyli
     * symetrycznie po drugiej stronie SLOT_WROC_Z_INDEKSU. Ta sama liczba, która dawniej
     * napędzała stary ranking po sumie (patrz FishingStatsManager.getTop, dziś nieużywany
     * w /rybtop) - tutaj dostaje wreszcie miejsce w GUI.
     */
    private ItemStack itemSumyKg(Player player) {
        ItemStack item = new ItemStack(Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Suma złowionych kg: " + String.format(Locale.ROOT, "%.1f", statystyki.sumaKg(player.getUniqueId())) + " kg", NamedTextColor.YELLOW, TextDecoration.BOLD));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Ręcznie dobrane sloty ozdób w GUI indeksu (patrz otworzIndeks), INNE na KAŻDEJ
     * stronie/rzadkości (user 2026-08-30: "na każdej stronie inaczej poukładał wodorosty
     * koralowce itp." - ten sam ogólny wzorzec/tło co Dziennik Rybaka, patrz
     * dekoracjeMenuGlownego, ale osobny układ tutaj) - im wyższa rzadkość, tym więcej
     * ozdób i tym bardziej kolorowe koralowce zamiast samych wodorostów, subtelnie
     * wzmacniając wrażenie "rzadsza sekcja = żywsza rafa". Świadomie omija sloty
     * zajęte przez rybki/odznakę/przyciski (patrz stałe SLOT_ i RZAD_RYB_ powyżej).
     */
    private Map<Integer, Material> dekoracjeIndeksu(RybaGatunek.Rzadkosc tier) {
        Map<Integer, Material> ozdoby = new HashMap<>();
        switch (tier) {
            case ZWYKLA -> {
                ozdoby.put(0, Material.KELP);
                ozdoby.put(2, Material.SEAGRASS);
                ozdoby.put(6, Material.KELP);
                ozdoby.put(8, Material.SEAGRASS);
                ozdoby.put(17, Material.KELP);
                ozdoby.put(36, Material.SEAGRASS);
                ozdoby.put(44, Material.KELP);
                ozdoby.put(46, Material.SEAGRASS);
            }
            case NIEZWYKLA -> {
                ozdoby.put(1, Material.SEAGRASS);
                ozdoby.put(3, Material.KELP);
                ozdoby.put(7, Material.TUBE_CORAL_FAN);
                ozdoby.put(9, Material.SEAGRASS);
                ozdoby.put(16, Material.KELP);
                ozdoby.put(18, Material.TUBE_CORAL_FAN);
                ozdoby.put(35, Material.SEAGRASS);
                ozdoby.put(37, Material.KELP);
                ozdoby.put(43, Material.TUBE_CORAL_FAN);
                ozdoby.put(52, Material.SEAGRASS);
            }
            case RZADKA -> {
                ozdoby.put(0, Material.BRAIN_CORAL_FAN);
                ozdoby.put(5, Material.KELP);
                ozdoby.put(8, Material.HORN_CORAL_FAN);
                ozdoby.put(10, Material.SEAGRASS);
                ozdoby.put(15, Material.BRAIN_CORAL_FAN);
                ozdoby.put(17, Material.KELP);
                ozdoby.put(26, Material.HORN_CORAL_FAN);
                ozdoby.put(28, Material.SEAGRASS);
                ozdoby.put(34, Material.BRAIN_CORAL_FAN);
                ozdoby.put(36, Material.KELP);
                ozdoby.put(46, Material.HORN_CORAL_FAN);
                ozdoby.put(51, Material.SEAGRASS);
            }
            case EPICKA -> {
                ozdoby.put(1, Material.FIRE_CORAL_FAN);
                ozdoby.put(3, Material.BRAIN_CORAL_FAN);
                ozdoby.put(6, Material.SEAGRASS);
                ozdoby.put(9, Material.KELP);
                ozdoby.put(11, Material.FIRE_CORAL_FAN);
                ozdoby.put(16, Material.BRAIN_CORAL_FAN);
                ozdoby.put(18, Material.SEAGRASS);
                ozdoby.put(25, Material.FIRE_CORAL_FAN);
                ozdoby.put(27, Material.KELP);
                ozdoby.put(33, Material.BRAIN_CORAL_FAN);
                ozdoby.put(38, Material.FIRE_CORAL_FAN);
                ozdoby.put(42, Material.SEAGRASS);
                ozdoby.put(50, Material.FIRE_CORAL_FAN);
            }
            case LEGENDARNA -> {
                ozdoby.put(0, Material.BUBBLE_CORAL_FAN);
                ozdoby.put(2, Material.FIRE_CORAL_FAN);
                ozdoby.put(5, Material.HORN_CORAL_FAN);
                ozdoby.put(8, Material.BUBBLE_CORAL_FAN);
                ozdoby.put(13, Material.FIRE_CORAL_FAN);
                ozdoby.put(17, Material.BUBBLE_CORAL_FAN);
                ozdoby.put(24, Material.HORN_CORAL_FAN);
                ozdoby.put(26, Material.BUBBLE_CORAL_FAN);
                ozdoby.put(30, Material.FIRE_CORAL_FAN);
                ozdoby.put(36, Material.HORN_CORAL_FAN);
                ozdoby.put(39, Material.BUBBLE_CORAL_FAN);
                ozdoby.put(44, Material.FIRE_CORAL_FAN);
                ozdoby.put(46, Material.BUBBLE_CORAL_FAN);
                ozdoby.put(51, Material.HORN_CORAL_FAN);
            }
            case MITYCZNA -> {
                ozdoby.put(0, Material.BUBBLE_CORAL_FAN);
                ozdoby.put(1, Material.FIRE_CORAL_FAN);
                ozdoby.put(3, Material.HORN_CORAL_FAN);
                ozdoby.put(6, Material.TUBE_CORAL_FAN);
                ozdoby.put(8, Material.BRAIN_CORAL_FAN);
                ozdoby.put(10, Material.BUBBLE_CORAL_FAN);
                ozdoby.put(14, Material.FIRE_CORAL_FAN);
                ozdoby.put(16, Material.HORN_CORAL_FAN);
                ozdoby.put(18, Material.TUBE_CORAL_FAN);
                ozdoby.put(24, Material.BRAIN_CORAL_FAN);
                ozdoby.put(26, Material.BUBBLE_CORAL_FAN);
                ozdoby.put(32, Material.FIRE_CORAL_FAN);
                ozdoby.put(37, Material.HORN_CORAL_FAN);
                ozdoby.put(40, Material.TUBE_CORAL_FAN);
                ozdoby.put(44, Material.BRAIN_CORAL_FAN);
                ozdoby.put(51, Material.BUBBLE_CORAL_FAN);
            }
        }
        return ozdoby;
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

    /**
     * Odznaka aktualnie oglądanej rzadkości (patrz SLOT_ODZNAKA_RZADKOSCI) - lore z
     * licznikiem dorzuca otworzIndeks. Klasyczna RPG "drabinka" surowców zamiast
     * kolorowych szyb (user 2026-08-30: chciał czegoś innego niż szkła) - żelazo→
     * złoto→szmaragd→diament→netheryt→gwiazda otchłani, coś co każdy gracz od razu
     * czyta jako rosnący "poziom", niezależnie od tematyki rybackiej.
     */
    private ItemStack etykietaTieru(RybaGatunek.Rzadkosc tier) {
        Material material = switch (tier) {
            case ZWYKLA -> Material.IRON_INGOT;
            case NIEZWYKLA -> Material.GOLD_INGOT;
            case RZADKA -> Material.EMERALD;
            case EPICKA -> Material.DIAMOND;
            case LEGENDARNA -> Material.NETHERITE_INGOT;
            case MITYCZNA -> Material.NETHER_STAR;
        };
        NamedTextColor kolor = switch (tier) {
            case ZWYKLA -> NamedTextColor.WHITE;
            case NIEZWYKLA -> NamedTextColor.YELLOW;
            case RZADKA -> NamedTextColor.GREEN;
            case EPICKA -> NamedTextColor.AQUA;
            case LEGENDARNA -> NamedTextColor.DARK_PURPLE;
            case MITYCZNA -> NamedTextColor.LIGHT_PURPLE;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwaTieru(tier), kolor, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        if (tier.ordinal() >= RybaGatunek.Rzadkosc.EPICKA.ordinal()) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Placeholder nieodkrytego gatunku - czerwona szyba, ale w odróżnieniu od pierwszej
     * wersji (user 2026-08-29: zero nazwy) teraz ma jawny napis "Nie odkryto" na hover
     * (user 2026-08-30: "gdy się najedzie na czerwone tło niech pisze nie odkryto") -
     * CELOWO dalej bez nazwy/koloru/rzadkości samej ryby, żeby niczego nie zdradzić.
     */
    private ItemStack nieodkrytaIkona() {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Nie odkryto", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Złów rybę tej rzadkości, aby ją odkryć.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Ikona jednego odkrytego gatunku w GUI indeksu - bazowy item (patrz bazowyItemRyby)
     * plus lore ze statystykami (user 2026-08-30): liczba sztuk, najwięcej/najmniej
     * złowione PRZEZ TEGO GRACZA (patrz WpisIndeksu), rekord SERWERA na ten gatunek -
     * dowolny gracz (patrz FishingStatsManager.getRekordGatunku) - jeśli już ktoś go
     * ustanowił (zawsze powinien, skoro ten gatunek jest już odkryty w indeksie).
     */
    private ItemStack iconaIndeksu(RybaGatunek gatunek, FishingStatsManager.WpisIndeksu wpis) {
        ItemStack item = bazowyItemRyby(gatunek);

        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.text("Złowionych: " + wpis.zlowionychSztuk() + " szt.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Twój rekord: " + String.format(Locale.ROOT, "%.1f", wpis.rekordKg()) + " kg", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Najmniejsza złowiona: " + String.format(Locale.ROOT, "%.1f", wpis.minKg()) + " kg", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));

        FishingStatsManager.RekordGatunku rekordSerwera = statystyki.getRekordGatunku(gatunek.customId());
        if (rekordSerwera != null) {
            lore.add(Component.text("Rekord serwera: " + String.format(Locale.ROOT, "%.1f", rekordSerwera.wagaKg()) + " kg (" + rekordSerwera.nick() + ")", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Obsługa WSZYSTKICH rybackich GUI (rozpoznanych po tytule) w jednym handlerze - ten
     * sam wzorzec co paginacja w MarketManager.onInventoryClick (ponowne otwarcie
     * inventory wewnątrz handlera klika). W Dzienniku Rybaka: przyciski otwierają
     * odpowiednie podstrony, pufferfish zamyka całkiem. Na KAŻDEJ podstronie
     * (Indeks/Dziennik Połowów/Ustawienia) pufferfish WRACA do Dziennika Rybaka (nie
     * zamyka). Klikanie samych rybek/odznak/ozdób nic nie robi.
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String tytul = event.getView().title().toString();
        boolean menuGlowne = tytul.contains(TYTUL_MENU_GLOWNEGO);
        boolean indeks = tytul.contains(TYTUL_INDEKSU);
        boolean dziennik = tytul.contains(TYTUL_DZIENNIK);
        boolean ustawienia = tytul.contains(TYTUL_USTAWIENIA);
        if (!menuGlowne && !indeks && !dziennik && !ustawienia) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();

        if (menuGlowne) {
            if (slot == SLOT_MENU_DZIENNIK) {
                otworzDziennik(player);
            } else if (slot == SLOT_MENU_INDEKS) {
                otworzIndeks(player);
            } else if (slot == SLOT_MENU_RYBTOP) {
                player.closeInventory(); // zamykamy, zeby ranking na czacie bylo widac bez GUI na wierzchu
                wyslijRanking(player);
            } else if (slot == SLOT_MENU_USTAWIENIA) {
                otworzUstawienia(player);
            } else if (slot == SLOT_MENU_ZAMKNIJ) {
                player.closeInventory();
            }
            return;
        }

        if (dziennik) {
            if (slot == SLOT_WROC_Z_DZIENNIKA) otworzMenuGlowne(player);
            return;
        }

        if (ustawienia) {
            // SLOT_USTAWIENIA_STYL: przełącznik Tekstowy/Graficzny schowany, patrz otworzUstawienia.
            if (slot == SLOT_USTAWIENIA_POZYCJA) {
                // OBA style (patrz otworzUstawienia) dziela ta sama pozycje od 2026-08-31 -
                // zaden warunek na styl tutaj juz nie potrzebny.
                PozycjaPaska nowa = pozycjaPaska(player) == PozycjaPaska.GORA ? PozycjaPaska.DOL : PozycjaPaska.GORA;
                ustawPozycjePaska(player, nowa);
                otworzUstawienia(player);
            } else if (slot == SLOT_WROC_Z_USTAWIEN) {
                otworzMenuGlowne(player);
            }
            return;
        }

        if (slot == SLOT_WROC_Z_INDEKSU) {
            otworzMenuGlowne(player);
            return;
        }

        List<RybaGatunek.Rzadkosc> tiery = rzadkosciZGatunkami();
        RybaGatunek.Rzadkosc aktualny = tierIndeksuGracza.get(player.getUniqueId());
        int indeksTieru = tiery.indexOf(aktualny);
        if (indeksTieru < 0) return;

        if (slot == SLOT_POPRZEDNIA_RZADKOSC && indeksTieru > 0) {
            otworzIndeks(player, tiery.get(indeksTieru - 1));
        } else if (slot == SLOT_NASTEPNA_RZADKOSC && indeksTieru < tiery.size() - 1) {
            otworzIndeks(player, tiery.get(indeksTieru + 1));
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
