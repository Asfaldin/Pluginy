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
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * tiery wędek, łowienie w powietrzu/nad pustką, upgrade wędki w kowadle (receptury),
 * bonusowy drop skrzynki z mainplugins-crates po udanym połowie.
 *
 * Zero zależności od mainplugins-quests/mainplugins-shop: gatunki ryb są rozpoznawane
 * wyłącznie po współdzielonym CustomItemKeys.CUSTOM_ITEM_ID (patrz mainplugins-core) -
 * te moduły zgadzają się na te same stringi tylko przez konwencję, bez twardej zależności.
 */
public class FishingManager implements Listener {

    private final Plugin plugin;

    // Tuning minigry i bonusowej skrzynki - fishing-config.yml, przeladowywalny bez
    // restartu (patrz aktualizujKonfiguracje / @reloadfishing). Gatunki ryb NIE sa tutaj -
    // patrz gatunki nizej.
    private volatile FishingConfig config;

    // Gatunki ryb do losowania - wczytywane z ryby.yml (w folderze danych tego pluginu,
    // patrz wczytajGatunki) zamiast trzymane na sztywno w kodzie, zeby dalo sie je tuningowac
    // (wagi/rzadkosc/nazwy) bez rekompilacji. Tymczasowo tylko 3 gatunki w domyslnym pliku,
    // po prostu nazwane wg rzadkosci (na czas dopracowywania minigry) - dokladnie te sloty
    // co juz sa wymogami questow kategorii "Rybak" (patrz quests-content.yml), zeby nic nie
    // zepsuc. FISH_MISTYCZNA ma prawdziwy custom model z resourcepacka (patrz custom-items.yml
    // + stworzRybe nizej) - jedyny gatunek, ktory NIE jest budowany bezposrednio z golego
    // Materialu, jesli rejestr custom itemow go zna.
    private final List<RybaGatunek> gatunki = new ArrayList<>();

    // Aktywna minigra "pasek" - patrz rozpocznijMinigre.
    private final Map<UUID, FishingMinigame> aktywneMinigry = new HashMap<>();

    // Runnable zatrzymujący trwający efekt złowienia (świecące cząsteczki/laser, patrz
    // efektZlapania) - działa dopóki minigra trwa, gaszone na sukces/porażkę/rozłączenie
    // (patrz zakonczEfektPolowu).
    private final Map<UUID, Runnable> aktywneEfektyPolowu = new HashMap<>();

    // Gatunek wylosowany JUŻ przy rzucie (nie dopiero przy braniu) w łowisku - dzięki temu
    // przy BITE efekt (patrz efektZlapania) od razu wie jakim kolorem świecić, bez
    // dodatkowego losowania w tym momencie. Zdejmowane przy BITE (przechodzi do minigry)
    // albo przy dowolnym innym/końcowym stanie zdarzenia (patrz onFish) - żeby nic nie
    // zostawało "wiszące" po spudłowanym rzucie.
    private final Map<UUID, RybaGatunek> oczekujaceGatunki = new HashMap<>();

    // Tag na wędkach TESTOWYCH (patrz stworzWedkeTestowa/@wedka1-3 w MainpluginsFishing) -
    // trzyma 0-based indeks do gatunki, żeby onFish mógł wymusić konkretny gatunek zamiast
    // losować, i przyspieszyć branie (patrz FishHook.setWaitTime) do testów. WYŁĄCZNIE do
    // testów na permisji mainplugins.fishing.admin - normalna /wedka tego tagu nie ma.
    private final NamespacedKey tagWedkiTestowej;

    public FishingManager(Plugin plugin, FishingConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.tagWedkiTestowej = new NamespacedKey(plugin, "wedka_test_indeks");
        wczytajGatunki();
    }

    /** Przeladowuje tuning minigry/bonusowej skrzynki (fishing-config.yml) i gatunki ryb (ryby.yml) - patrz komenda @reloadfishing. */
    public void aktualizujKonfiguracje(FishingConfig nowy) {
        this.config = nowy;
        wczytajGatunki();
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
                wczytane.add(new RybaGatunek(customId, nazwa, material, kolor, rzadkosc, Math.max(1, waga)));
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
     * Jeśli gatunek ma wpis w rejestrze custom itemów (patrz mainplugins-core,
     * custom-items.yml) - np. FISH_MISTYCZNA z własnym modelem z resourcepacka -
     * wydajemy DOKŁADNIE ten item stamtąd (ten sam wzorzec co ShopManager.stworzBazowyItem).
     * W przeciwnym razie (reszta gatunków - zwykłe przefarbowane materiały) budujemy
     * item ręcznie, tak jak dotychczas.
     */
    private ItemStack stworzRybe(RybaGatunek gatunek) {
        CustomItemService rejestr = CoreAPI.getCustomItemService();
        if (rejestr != null && rejestr.exists(gatunek.customId())) {
            ItemStack custom = rejestr.create(gatunek.customId(), 1);
            if (custom != null) return custom;
        }

        ItemStack item = new ItemStack(gatunek.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(gatunek.nazwa(), gatunek.kolor(), TextDecoration.BOLD));
        meta.lore(List.of(Component.text(opisRzadkosci(gatunek.rzadkosc()), gatunek.kolor()).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, gatunek.customId());
        if (gatunek.rzadkosc().ordinal() >= RybaGatunek.Rzadkosc.RZADKA.ordinal()) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String opisRzadkosci(RybaGatunek.Rzadkosc r) {
        return switch (r) {
            case ZWYKLA -> "Zwykła ryba";
            case NIEZWYKLA -> "Niezwykła ryba";
            case RZADKA -> "Rzadka ryba";
            case EPICKA -> "Epicka ryba";
            case LEGENDARNA -> "Legendarna ryba";
        };
    }

    // ==================================================================== Losowanie ====

    private RybaGatunek losujRybe() {
        if (gatunki.isEmpty()) return null;

        int suma = 0;
        for (RybaGatunek g : gatunki) suma += g.waga();

        int los = ThreadLocalRandom.current().nextInt(suma);
        int akumulator = 0;
        for (RybaGatunek g : gatunki) {
            akumulator += g.waga();
            if (los < akumulator) return g;
        }
        return gatunki.getLast();
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
            RybaGatunek wymuszony = wymuszonyGatunekZWedkiTestowej(event.getPlayer());
            RybaGatunek gatunek = wymuszony != null ? wymuszony : losujRybe();
            if (gatunek == null) return; // ryby.yml pusty/uszkodzony - patrz wczytajGatunki, zostawiamy wanilijskie łowienie

            // Zapis PRZED próbą przyspieszenia brania - jeśli setWaitTime akurat rzuci
            // wyjątek (nieudokumentowane ograniczenie tej wersji MC), wymuszony gatunek
            // i tak zostaje zapamiętany, zamiast po cichu spaść do losowania na BITE.
            oczekujaceGatunki.put(uuid, gatunek);

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
        RybaGatunek gatunek = oczekujaceGatunki.remove(uuid);
        if (gatunek == null) {
            // Asekuracyjnie, gdyby FISHING nie doszedl do glosu (np. hak w locie byl jeszcze
            // poza granicami lowiska, a osiadl w nim dopiero pozniej) - sprawdzamy wedke
            // testowa TERAZ, zamiast od razu skakac do czystego losowania, zeby wymuszanie
            // gatunku dzialalo niezaleznie od tego kiedy dokladnie hak wpadl w granice.
            RybaGatunek wymuszony = wymuszonyGatunekZWedkiTestowej(event.getPlayer());
            gatunek = wymuszony != null ? wymuszony : losujRybe();
        }
        if (gatunek == null) return;

        event.setCancelled(true);
        event.getHook().remove();

        rozpocznijMinigre(event.getPlayer(), gatunek, lokalizacjaHaka);
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

    private void rozpocznijMinigre(Player player, RybaGatunek gatunek, Location lokalizacjaHaka) {
        UUID uuid = player.getUniqueId();
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f);

        aktywneEfektyPolowu.put(uuid, efektZlapania(player, gatunek, lokalizacjaHaka));

        FishingMinigame gra = new FishingMinigame(plugin, player, gatunek, config.minigra(),
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
        ItemStack ryba = stworzRybe(zlowiona);
        var nieZmieszczone = player.getInventory().addItem(ryba);
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));

        player.sendMessage(Component.text("Złowiłeś: ", NamedTextColor.GREEN)
                .append(Component.text(zlowiona.nazwa(), zlowiona.kolor(), TextDecoration.BOLD)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        rzucBonusowaSkrzynke(player);
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
