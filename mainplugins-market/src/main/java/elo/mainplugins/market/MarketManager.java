package elo.mainplugins.market;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.MarketService;
import elo.mainplugins.core.api.Rank;
import elo.mainplugins.core.api.RankService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MarketManager implements Listener, MarketService {

    private final Plugin plugin;
    private final EconomyService economyManager;
    private final File plikRynku;
    private final FileConfiguration configRynku;

    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();
    private final Map<UUID, Integer> stronaGracza = new HashMap<>();

    // Filtr "tylko moje oferty" (patrz przycisk w pasku nawigacyjnym) - żeby gracz
    // mógł znaleźć własne oferty do wycofania bez przeglądania całego Rynku.
    private final Map<UUID, Boolean> tylkoMojeOferty = new HashMap<>();

    // Sortowanie po cenie - true (domyślnie) = od najtańszych, false = od najdroższych.
    private final Map<UUID, Boolean> sortRosnaco = new HashMap<>();

    // Wyszukiwarka - ten sam wzorzec co w mainplugins-shop (patrz ShopManager):
    // gracz klika lupę, wpisuje frazę na czacie, dostaje osobne okno z wynikami.
    private final Set<UUID> czekaNaFraze = new HashSet<>();
    private final Map<UUID, Map<Integer, String>> slotyWynikow = new HashMap<>();
    private final Map<UUID, String> ostatniaFraza = new HashMap<>();
    private static final String TYTUL_WYNIKOW = "Wyniki targu: ";

    private static final int SLOT_SZUKAJ = 46;
    private static final int SLOT_SORTOWANIA = 51;

    // Klucz oferty oczekującej na potwierdzenie wycofania (drugi klik we własną,
    // niesprzedaną ofertę) - ten sam wzorzec "kliknij ponownie", co usuwanie wyspy.
    private final Map<UUID, String> pendingRetrieve = new HashMap<>();
    private static final long TIMEOUT_WYCOFANIA_TICKS = 15 * 20L;

    // Górny limit ceny na Rynku - bez tego gracz mógł wpisać dowolnie duże/małe
    // liczby (patrz też walidacja "liczba całkowita" w wystawPrzedmiot).
    private static final long MAX_CENA = 10_000_000L;

    // Limit aktywnych ofert na gracza - żeby jeden gracz nie zapchał całego Rynku.
    // VIP dostaje więcej miejsca jako przywilej rangi (patrz RankService).
    private static final int LIMIT_OFERT_GRACZ = 10;
    private static final int LIMIT_OFERT_VIP = 15;

    // Zapamiętuje który slot w GUI odpowiada za który przedmiot w pliku
    private final Map<UUID, Map<Integer, String>> slotyRynku = new HashMap<>();

    // Siatka slotów na przedmioty: rzędy 1-3 (z 0-4), kolumny 1-7 z 9-szerokiej
    // siatki - jeden zwarty blok bez przerw ani w poziomie, ani w pionie, tylko
    // kolumna 0/8 (ściany) i rzędy 0/4 zostają jako szklana ramka.
    private static final int[] SLOTY_PRZEDMIOTOW = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };

    public MarketManager(Plugin plugin, EconomyService economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;

        this.plikRynku = new File(plugin.getDataFolder(), "rynek.yml");
        if (!plikRynku.exists()) {
            plikRynku.getParentFile().mkdirs();
            try { plikRynku.createNewFile(); } catch (IOException ignored) {}
        }
        this.configRynku = YamlConfiguration.loadConfiguration(plikRynku);
    }

    /** {@inheritDoc} Skanuje wszystkie aktywne oferty po polu "sprzedawca" - pod QuestService/quest #19 Głównej Ścieżki. */
    @Override
    public boolean maAktywnaOferte(UUID uuid) {
        if (!configRynku.contains("przedmioty")) return false;
        String szukany = uuid.toString();
        for (String klucz : configRynku.getConfigurationSection("przedmioty").getKeys(false)) {
            if (szukany.equals(configRynku.getString("przedmioty." + klucz + ".sprzedawca"))) return true;
        }
        return false;
    }

    private void zapiszRynek() {
        try {
            configRynku.save(plikRynku);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** VIP i wyżej dostają wyższy limit ofert jako przywilej rangi - brak mainplugins-ranks = zwykły limit. */
    private int limitOfert(Player player) {
        RankService rankService = CoreAPI.getRankService();
        Rank ranga = rankService != null ? rankService.getRank(player.getUniqueId()) : Rank.GRACZ;
        return ranga == Rank.GRACZ ? LIMIT_OFERT_GRACZ : LIMIT_OFERT_VIP;
    }

    private int liczbaOfertGracza(UUID uuid) {
        if (!configRynku.contains("przedmioty")) return 0;

        String szukanyUuid = uuid.toString();
        int licznik = 0;
        for (String klucz : configRynku.getConfigurationSection("przedmioty").getKeys(false)) {
            if (szukanyUuid.equals(configRynku.getString("przedmioty." + klucz + ".sprzedawca"))) licznik++;
        }
        return licznik;
    }

    public void wystawPrzedmiot(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Poprawne użycie: /targ wystaw <cena>", NamedTextColor.RED));
            return;
        }

        long cena;
        try {
            // long zamiast double - odrzuca ułamki (0.001) i zapis wykładniczy (1e9) już
            // na etapie parsowania, bez osobnej walidacji "czy to liczba całkowita".
            cena = Long.parseLong(args[1]);
            if (cena <= 0 || cena > MAX_CENA) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Cena musi być liczbą całkowitą z zakresu 1-" + MAX_CENA + "!", NamedTextColor.RED));
            return;
        }

        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (wRece.getType() == Material.AIR) {
            player.sendMessage(Component.text("Musisz trzymać w ręce przedmiot, który chcesz wystawić!", NamedTextColor.RED));
            return;
        }

        int limit = limitOfert(player);
        int aktualneOferty = liczbaOfertGracza(player.getUniqueId());
        if (aktualneOferty >= limit) {
            player.sendMessage(Component.text(
                    "Masz już maksymalną liczbę ofert na Rynku (" + limit + ")! Wycofaj coś, żeby wystawić kolejny przedmiot.",
                    NamedTextColor.RED));
            return;
        }

        // Ile przedmiotów jest już wystawionych - nowy wyląduje na końcu listy,
        // więc to jest jednocześnie jego indeks (do wyliczenia strony niżej).
        int indeksNowegoPrzedmiotu = configRynku.contains("przedmioty")
                ? configRynku.getConfigurationSection("przedmioty").getKeys(false).size()
                : 0;

        // Klonujemy item, żeby zapisać go bez modyfikacji oryginału
        ItemStack doWystawienia = wRece.clone();

        // Generujemy unikalne ID dla przedmiotu
        String idPrzedmiotu = UUID.randomUUID().toString();

        configRynku.set("przedmioty." + idPrzedmiotu + ".item", doWystawienia);
        configRynku.set("przedmioty." + idPrzedmiotu + ".cena", cena);
        configRynku.set("przedmioty." + idPrzedmiotu + ".sprzedawca", player.getUniqueId().toString());
        configRynku.set("przedmioty." + idPrzedmiotu + ".nick_sprzedawcy", player.getName());

        zapiszRynek();

        player.getInventory().setItemInMainHand(null);
        player.sendMessage(Component.text("Pomyślnie wystawiono przedmiot na targ za " + cena + "$!", NamedTextColor.GREEN));

        // Skok od razu na stronę, na której realnie wylądował nowy przedmiot -
        // jeśli poprzednia strona akurat się zapełniła, gracz od razu widzi nową.
        int strona = indeksNowegoPrzedmiotu / SLOTY_PRZEDMIOTOW.length;
        otworzTarg(player, strona, false);
    }

    /** Buduje item do wyświetlenia w GUI (lore z ceną/sprzedawcą) dla danej oferty - używane
     *  zarówno przez główną siatkę Rynku, jak i okno wyników wyszukiwania. Null, jeśli
     *  oferta zniknęła z configu między odczytem klucza a zbudowaniem itemu. */
    private ItemStack zbudujItemDoWyswietlenia(String klucz) {
        ItemStack oryginal = configRynku.getItemStack("przedmioty." + klucz + ".item");
        if (oryginal == null) return null;

        long cena = configRynku.getLong("przedmioty." + klucz + ".cena");
        String nick = configRynku.getString("przedmioty." + klucz + ".nick_sprzedawcy");

        ItemStack wyswietlany = oryginal.clone();
        ItemMeta meta = wyswietlany.getItemMeta();
        if (meta != null) {
            List<Component> lore = meta.hasLore() ? meta.lore() : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(Component.text("Cena: " + cena + "$", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Sprzedawca: " + nick, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("LPM, aby kupić!", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            wyswietlany.setItemMeta(meta);
        }
        return wyswietlany;
    }

    /** Nazwa materiału i (jeśli jest) własna nazwa itemu - do dopasowania frazy wyszukiwania. */
    private boolean pasujeDoSzukania(ItemStack item, String szukane) {
        if (item.getType().name().toLowerCase().replace('_', ' ').contains(szukane)) return true;

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.displayName() != null) {
            String nazwa = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(meta.displayName()).toLowerCase();
            if (nazwa.contains(szukane)) return true;
        }
        return false;
    }

    public void otworzTarg(Player player, int strona) {
        otworzTarg(player, strona, false);
    }

    public void otworzTarg(Player player, int strona, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);

        boolean tylkoMoje = tylkoMojeOferty.getOrDefault(player.getUniqueId(), false);
        String mojeUuid = player.getUniqueId().toString();

        List<String> wszystkieKlucze = new ArrayList<>();
        if (configRynku.contains("przedmioty")) {
            for (String klucz : configRynku.getConfigurationSection("przedmioty").getKeys(false)) {
                if (!tylkoMoje || mojeUuid.equals(configRynku.getString("przedmioty." + klucz + ".sprzedawca"))) {
                    wszystkieKlucze.add(klucz);
                }
            }
        }

        boolean rosnaco = sortRosnaco.getOrDefault(player.getUniqueId(), true);
        Comparator<String> poCenie = Comparator.comparingLong(k -> configRynku.getLong("przedmioty." + k + ".cena"));
        wszystkieKlucze.sort(rosnaco ? poCenie : poCenie.reversed());

        int maxStron = Math.max(1, (int) Math.ceil((double) wszystkieKlucze.size() / SLOTY_PRZEDMIOTOW.length));
        if (strona >= maxStron) strona = maxStron - 1;
        if (strona < 0) strona = 0;
        stronaGracza.put(player.getUniqueId(), strona);

        String tytul = "Rynek (Str. " + (strona + 1) + ")" + (tylkoMoje ? " - Twoje oferty" : "");
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(tytul, NamedTextColor.GOLD, TextDecoration.BOLD));

        // Całe tło w szkle - dopiero na to nakładamy przedmioty i przyciski, więc
        // pusty slot (bez wystawionego przedmiotu) nigdy nie zostaje "goły".
        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta mTlo = tlo.getItemMeta();
        mTlo.displayName(Component.empty());
        tlo.setItemMeta(mTlo);
        for (int i = 0; i < 54; i++) {
            gui.setItem(i, tlo);
        }

        Map<Integer, String> mapaSlotow = new HashMap<>();

        // Wypełnianie przedmiotami - koło siebie, bez przerw (SLOTY_PRZEDMIOTOW)
        int poczatek = strona * SLOTY_PRZEDMIOTOW.length;
        int koniec = Math.min(poczatek + SLOTY_PRZEDMIOTOW.length, wszystkieKlucze.size());

        for (int i = poczatek; i < koniec; i++) {
            String klucz = wszystkieKlucze.get(i);
            ItemStack wyswietlany = zbudujItemDoWyswietlenia(klucz);
            if (wyswietlany != null) {
                int slot = SLOTY_PRZEDMIOTOW[i - poczatek];
                gui.setItem(slot, wyswietlany);
                mapaSlotow.put(slot, klucz);
            }
        }

        slotyRynku.put(player.getUniqueId(), mapaSlotow);

        // Strzałka w lewo (poprzednia strona)
        if (strona > 0) {
            ItemStack prev = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta mPrev = prev.getItemMeta();
            mPrev.displayName(Component.text("« Poprzednia Strona", NamedTextColor.YELLOW, TextDecoration.BOLD));
            prev.setItemMeta(mPrev);
            gui.setItem(45, prev);
        }

        // Strzałka w prawo (następna strona)
        if (strona < maxStron - 1) {
            ItemStack next = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta mNext = next.getItemMeta();
            mNext.displayName(Component.text("Następna Strona »", NamedTextColor.YELLOW, TextDecoration.BOLD));
            next.setItemMeta(mNext);
            gui.setItem(53, next);
        }

        // Przycisk filtra "tylko moje oferty" - żeby nie trzeba było przeszukiwać
        // całego Rynku, żeby znaleźć własną ofertę do wycofania.
        ItemStack filtrItem = new ItemStack(Material.HOPPER);
        ItemMeta mFiltr = filtrItem.getItemMeta();
        if (tylkoMoje) {
            mFiltr.displayName(Component.text("Pokazujesz: Twoje oferty", NamedTextColor.GREEN, TextDecoration.BOLD));
            mFiltr.lore(List.of(Component.text("Kliknij, aby pokazać cały Rynek", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            mFiltr.setEnchantmentGlintOverride(true);
        } else {
            mFiltr.displayName(Component.text("Pokaż tylko moje oferty", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }
        filtrItem.setItemMeta(mFiltr);
        gui.setItem(47, filtrItem);

        // Przycisk szukania - kliknij i wpisz nazwę przedmiotu na czacie (patrz onChat).
        ItemStack lupa = new ItemStack(Material.OAK_SIGN);
        ItemMeta mLupa = lupa.getItemMeta();
        mLupa.displayName(Component.text("Szukaj przedmiotu", NamedTextColor.AQUA, TextDecoration.BOLD));
        mLupa.lore(List.of(
                Component.text("Kliknij i wpisz nazwę na czacie", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("np. diament, kilof", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        lupa.setItemMeta(mLupa);
        gui.setItem(SLOT_SZUKAJ, lupa);

        // Przycisk sortowania po cenie - przełącznik rosnąco/malejąco.
        ItemStack sortowanie = new ItemStack(Material.COMPARATOR);
        ItemMeta mSort = sortowanie.getItemMeta();
        mSort.displayName(Component.text(rosnaco ? "Sortowanie: od najtańszych" : "Sortowanie: od najdroższych",
                NamedTextColor.AQUA, TextDecoration.BOLD));
        mSort.lore(List.of(Component.text("Kliknij, aby odwrócić kolejność", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        sortowanie.setItemMeta(mSort);
        gui.setItem(SLOT_SORTOWANIA, sortowanie);

        // Przycisk powrotu/zamknięcia
        ItemStack powrotItem = new ItemStack(zMenu ? Material.NETHER_STAR : Material.BARRIER);
        ItemMeta meta = powrotItem.getItemMeta();
        meta.displayName(Component.text(zMenu ? "« Wróć do Menu głównego" : "Zamknij Targ", NamedTextColor.RED, TextDecoration.BOLD));
        powrotItem.setItemMeta(meta);
        gui.setItem(49, powrotItem);

        player.openInventory(gui);
    }

    /** Wycofuje niesprzedaną ofertę i zwraca przedmiot sprzedawcy (drugi klik po potwierdzeniu). */
    private void wycofajOferte(Player player, String klucz, ItemStack item) {
        configRynku.set("przedmioty." + klucz, null);
        zapiszRynek();

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack lo : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), lo);
        }

        player.sendMessage(Component.text("Wycofano ofertę i odebrano przedmiot z targu.", NamedTextColor.GREEN));
        otworzTarg(player, stronaGracza.getOrDefault(player.getUniqueId(), 0), otwartoZMenu.getOrDefault(player.getUniqueId(), false));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().title().toString();
        boolean jestRynek = title.contains("Rynek (Str.");
        boolean jestWyniki = title.contains(TYTUL_WYNIKOW);
        if (!jestRynek && !jestWyniki) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        int rawSlot = event.getRawSlot();

        // OKNO WYNIKÓW WYSZUKIWANIA - osobna, dużo prostsza siatka: brak paginacji/
        // filtra/sortowania, tylko przycisk powrotu do normalnego Rynku.
        if (jestWyniki) {
            if (rawSlot == 49) {
                otworzTarg(player, stronaGracza.getOrDefault(player.getUniqueId(), 0), otwartoZMenu.getOrDefault(player.getUniqueId(), false));
                return;
            }
            Map<Integer, String> slotyGracza = slotyWynikow.get(player.getUniqueId());
            if (slotyGracza != null && slotyGracza.containsKey(rawSlot)) {
                String klucz = slotyGracza.get(rawSlot);
                obslugaKlikniecieWOferte(player, klucz, clickedItem,
                        () -> otworzWynikiSzukania(player, ostatniaFraza.getOrDefault(player.getUniqueId(), "")));
            }
            return;
        }

        // Pasek nawigacyjny na dole
        if (rawSlot >= 45 && rawSlot <= 53) {
            int obecnaStrona = stronaGracza.getOrDefault(player.getUniqueId(), 0);
            boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);

            if (clickedItem.getType() == Material.SPECTRAL_ARROW) {
                if (rawSlot == 45) {
                    otworzTarg(player, obecnaStrona - 1, zMenu);
                } else if (rawSlot == 53) {
                    otworzTarg(player, obecnaStrona + 1, zMenu);
                }
            } else if (clickedItem.getType() == Material.NETHER_STAR) {
                player.closeInventory();
                player.performCommand("menu");
            } else if (clickedItem.getType() == Material.BARRIER) {
                player.closeInventory();
            } else if (rawSlot == 47 && clickedItem.getType() == Material.HOPPER) {
                boolean nowyStan = !tylkoMojeOferty.getOrDefault(player.getUniqueId(), false);
                tylkoMojeOferty.put(player.getUniqueId(), nowyStan);
                otworzTarg(player, 0, zMenu);
            } else if (rawSlot == SLOT_SZUKAJ && clickedItem.getType() == Material.OAK_SIGN) {
                czekaNaFraze.add(player.getUniqueId());
                player.closeInventory();
                player.sendMessage(Component.text("Wpisz na czacie, czego szukasz.", NamedTextColor.AQUA));
                player.sendMessage(Component.text("Wpisz 'anuluj', żeby zrezygnować.", NamedTextColor.GRAY));
            } else if (rawSlot == SLOT_SORTOWANIA && clickedItem.getType() == Material.COMPARATOR) {
                boolean nowyStan = !sortRosnaco.getOrDefault(player.getUniqueId(), true);
                sortRosnaco.put(player.getUniqueId(), nowyStan);
                otworzTarg(player, 0, zMenu);
            }
            return;
        }

        // Kliknięcie w przedmiot na targu (sloty 0-44)
        Map<Integer, String> slotyGracza = slotyRynku.get(player.getUniqueId());
        if (slotyGracza != null && slotyGracza.containsKey(rawSlot)) {
            String klucz = slotyGracza.get(rawSlot);
            obslugaKlikniecieWOferte(player, klucz, clickedItem,
                    () -> otworzTarg(player, stronaGracza.getOrDefault(player.getUniqueId(), 0), otwartoZMenu.getOrDefault(player.getUniqueId(), false)));
        }
    }

    /**
     * Wspólna obsługa kliknięcia w jedną ofertę (kupno/wycofanie własnej) - używana
     * zarówno przez główną siatkę Rynku, jak i okno wyników wyszukiwania. Po
     * zakończeniu akcji (kupno, albo stwierdzenie że oferta już zniknęła) wywołuje
     * poAkcji, żeby każdy z tych dwóch ekranów mógł odświeżyć się po swojemu
     * (wycofanie własnej oferty zawsze wraca do normalnego Rynku - patrz wycofajOferte).
     */
    private void obslugaKlikniecieWOferte(Player player, String klucz, ItemStack clickedItem, Runnable poAkcji) {
        // Sprawdzamy czy przedmiot nadal istnieje w configu (ktoś mógł kupić w międzyczasie)
        if (!configRynku.contains("przedmioty." + klucz)) {
            player.sendMessage(Component.text("Ten przedmiot został już sprzedany lub usunięty!", NamedTextColor.RED));
            poAkcji.run();
            return;
        }

        long cena = configRynku.getLong("przedmioty." + klucz + ".cena");
        String sprzedawcaUUID = configRynku.getString("przedmioty." + klucz + ".sprzedawca");
        ItemStack doKupienia = configRynku.getItemStack("przedmioty." + klucz + ".item");

        if (player.getUniqueId().toString().equals(sprzedawcaUUID)) {
            if (klucz.equals(pendingRetrieve.get(player.getUniqueId()))) {
                pendingRetrieve.remove(player.getUniqueId());
                wycofajOferte(player, klucz, doKupienia);
            } else {
                pendingRetrieve.put(player.getUniqueId(), klucz);
                Bukkit.getScheduler().runTaskLater(plugin, () -> pendingRetrieve.remove(player.getUniqueId()), TIMEOUT_WYCOFANIA_TICKS);
                ItemMeta meta = clickedItem.getItemMeta();
                if (meta != null) {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.add(Component.text("Kliknij ponownie, aby wycofać ofertę i odebrać przedmiot!", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
                    meta.lore(lore);
                    clickedItem.setItemMeta(meta);
                }
            }
            return;
        }

        if (economyManager.maWystarczajaco(player.getUniqueId(), cena)) {
            // Transakcja
            economyManager.odejmijKase(player.getUniqueId(), cena);
            economyManager.dodajKase(UUID.fromString(sprzedawcaUUID), cena);

            // Dajemy przedmiot i usuwamy z targu (leftover na ziemię, jeśli plecak pełny - patrz wycofajOferte)
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(doKupienia);
            for (ItemStack lo : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), lo);
            }
            configRynku.set("przedmioty." + klucz, null);
            zapiszRynek();

            player.sendMessage(Component.text("Zakupiłeś przedmiot za " + cena + "$!", NamedTextColor.GREEN));
            poAkcji.run();

            Player sprzedawca = Bukkit.getPlayer(UUID.fromString(sprzedawcaUUID));
            if (sprzedawca != null && sprzedawca.isOnline()) {
                sprzedawca.sendMessage(Component.text("Ktoś kupił twój przedmiot na targu za " + cena + "$!", NamedTextColor.GOLD));
            }
        } else {
            player.sendMessage(Component.text("Nie masz wystarczająco pieniędzy, aby to kupić!", NamedTextColor.RED));
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
        if (fraza.isEmpty()) return;

        // Czat leci w wątku asynchronicznym, a GUI wolno otwierać TYLKO z głównego
        // wątku serwera - bez tego skoku Bukkit rzuci wyjątkiem.
        Bukkit.getScheduler().runTask(plugin, () -> otworzWynikiSzukania(player, fraza));
    }

    private void otworzWynikiSzukania(Player player, String fraza) {
        ostatniaFraza.put(player.getUniqueId(), fraza);
        String szukane = fraza.toLowerCase();

        List<String> trafienia = new ArrayList<>();
        if (configRynku.contains("przedmioty")) {
            for (String klucz : configRynku.getConfigurationSection("przedmioty").getKeys(false)) {
                ItemStack item = configRynku.getItemStack("przedmioty." + klucz + ".item");
                if (item != null && pasujeDoSzukania(item, szukane)) trafienia.add(klucz);
            }
        }

        if (trafienia.isEmpty()) {
            player.sendMessage(Component.text("Nic nie znaleziono dla: " + fraza, NamedTextColor.RED));
            otworzTarg(player, stronaGracza.getOrDefault(player.getUniqueId(), 0), otwartoZMenu.getOrDefault(player.getUniqueId(), false));
            return;
        }

        // Więcej wyników niż mieści siatka - obcinamy i mówimy o tym graczowi.
        boolean obciete = trafienia.size() > SLOTY_PRZEDMIOTOW.length;
        if (obciete) trafienia = trafienia.subList(0, SLOTY_PRZEDMIOTOW.length);

        Inventory gui = Bukkit.createInventory(null, 54,
                Component.text(TYTUL_WYNIKOW + fraza, NamedTextColor.DARK_AQUA, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta mTlo = tlo.getItemMeta();
        mTlo.displayName(Component.empty());
        tlo.setItemMeta(mTlo);
        for (int i = 0; i < 54; i++) gui.setItem(i, tlo);

        Map<Integer, String> mapaSlotow = new HashMap<>();
        for (int i = 0; i < trafienia.size(); i++) {
            String klucz = trafienia.get(i);
            ItemStack wyswietlany = zbudujItemDoWyswietlenia(klucz);
            if (wyswietlany != null) {
                int slot = SLOTY_PRZEDMIOTOW[i];
                gui.setItem(slot, wyswietlany);
                mapaSlotow.put(slot, klucz);
            }
        }
        slotyWynikow.put(player.getUniqueId(), mapaSlotow);

        ItemStack powrot = new ItemStack(Material.BARRIER);
        ItemMeta metaPowrot = powrot.getItemMeta();
        metaPowrot.displayName(Component.text("Powrót do Rynku", NamedTextColor.GOLD, TextDecoration.BOLD));
        powrot.setItemMeta(metaPowrot);
        gui.setItem(49, powrot);

        player.openInventory(gui);
        player.sendMessage(Component.text("Znaleziono: " + trafienia.size()
                        + (obciete ? " (pokazano pierwsze " + SLOTY_PRZEDMIOTOW.length + ")" : ""),
                NamedTextColor.AQUA));
    }
}