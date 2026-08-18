package elo.mainplugins.market;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.MarketService;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MarketManager implements Listener, MarketService {

    private final Plugin plugin;
    private final EconomyService economyManager;
    private final File plikRynku;
    private final FileConfiguration configRynku;

    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();
    private final Map<UUID, Integer> stronaGracza = new HashMap<>();

    // Klucz oferty oczekującej na potwierdzenie wycofania (drugi klik we własną,
    // niesprzedaną ofertę) - ten sam wzorzec "kliknij ponownie", co usuwanie wyspy.
    private final Map<UUID, String> pendingRetrieve = new HashMap<>();
    private static final long TIMEOUT_WYCOFANIA_TICKS = 15 * 20L;

    // Zapamiętuje który slot w GUI odpowiada za który przedmiot w pliku
    private final Map<UUID, Map<Integer, String>> slotyRynku = new HashMap<>();

    // Siatka slotów na przedmioty: 1 kratka odstępu od ścian i od siebie nawzajem
    // (rzędy 1 i 3 z 0-4, kolumny 1/3/5/7 z 9-szerokiej siatki - reszta to szkło).
    private static final int[] SLOTY_PRZEDMIOTOW = {
            10, 12, 14, 16,
            28, 30, 32, 34
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

    public void wystawPrzedmiot(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Poprawne użycie: /targ wystaw <cena>", NamedTextColor.RED));
            return;
        }

        double cena;
        try {
            cena = Double.parseDouble(args[1]);
            if (cena <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Cena musi być liczbą większą od zera!", NamedTextColor.RED));
            return;
        }

        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (wRece.getType() == Material.AIR) {
            player.sendMessage(Component.text("Musisz trzymać w ręce przedmiot, który chcesz wystawić!", NamedTextColor.RED));
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
        player.sendMessage(Component.text("Pomyślnie wystawiono przedmiot na targ za " + cena + " $!", NamedTextColor.GREEN));

        // Skok od razu na stronę, na której realnie wylądował nowy przedmiot -
        // jeśli poprzednia strona akurat się zapełniła, gracz od razu widzi nową.
        int strona = indeksNowegoPrzedmiotu / SLOTY_PRZEDMIOTOW.length;
        otworzTarg(player, strona, false);
    }

    public void otworzTarg(Player player, int strona) {
        otworzTarg(player, strona, false);
    }

    public void otworzTarg(Player player, int strona, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);

        List<String> wszystkieKlucze = new ArrayList<>();
        if (configRynku.contains("przedmioty")) {
            wszystkieKlucze.addAll(configRynku.getConfigurationSection("przedmioty").getKeys(false));
        }

        int maxStron = Math.max(1, (int) Math.ceil((double) wszystkieKlucze.size() / SLOTY_PRZEDMIOTOW.length));
        if (strona >= maxStron) strona = maxStron - 1;
        if (strona < 0) strona = 0;
        stronaGracza.put(player.getUniqueId(), strona);

        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Rynek (Str. " + (strona + 1) + ")", NamedTextColor.GOLD, TextDecoration.BOLD));

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

        // Wypełnianie przedmiotami - z odstępem od ścian i między sobą (SLOTY_PRZEDMIOTOW)
        int poczatek = strona * SLOTY_PRZEDMIOTOW.length;
        int koniec = Math.min(poczatek + SLOTY_PRZEDMIOTOW.length, wszystkieKlucze.size());

        for (int i = poczatek; i < koniec; i++) {
            String klucz = wszystkieKlucze.get(i);
            ItemStack oryginal = configRynku.getItemStack("przedmioty." + klucz + ".item");
            double cena = configRynku.getDouble("przedmioty." + klucz + ".cena");
            String nick = configRynku.getString("przedmioty." + klucz + ".nick_sprzedawcy");

            if (oryginal != null) {
                ItemStack wyswietlany = oryginal.clone();
                ItemMeta meta = wyswietlany.getItemMeta();
                if (meta != null) {
                    List<Component> lore = meta.hasLore() ? meta.lore() : new ArrayList<>();
                    lore.add(Component.empty());
                    lore.add(Component.text("Cena: " + cena + " $", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
                    lore.add(Component.text("Sprzedawca: " + nick, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                    lore.add(Component.text("LPM, aby kupić!", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                    meta.lore(lore);
                    wyswietlany.setItemMeta(meta);
                }
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
        if (!event.getView().title().toString().contains("Rynek (Str.")) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        int rawSlot = event.getRawSlot();

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
            }
            return;
        }

        // Kliknięcie w przedmiot na targu (sloty 0-44)
        Map<Integer, String> slotyGracza = slotyRynku.get(player.getUniqueId());
        if (slotyGracza != null && slotyGracza.containsKey(rawSlot)) {
            String klucz = slotyGracza.get(rawSlot);

            // Sprawdzamy czy przedmiot nadal istnieje w configu (ktoś mógł kupić w międzyczasie)
            if (!configRynku.contains("przedmioty." + klucz)) {
                player.sendMessage(Component.text("Ten przedmiot został już sprzedany lub usunięty!", NamedTextColor.RED));
                otworzTarg(player, stronaGracza.getOrDefault(player.getUniqueId(), 0), otwartoZMenu.getOrDefault(player.getUniqueId(), false));
                return;
            }

            double cena = configRynku.getDouble("przedmioty." + klucz + ".cena");
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

                // Dajemy przedmiot i usuwamy z targu
                player.getInventory().addItem(doKupienia);
                configRynku.set("przedmioty." + klucz, null);
                zapiszRynek();

                player.sendMessage(Component.text("Zakupiłeś przedmiot za " + cena + " $!", NamedTextColor.GREEN));

                // Odświeżamy targ
                otworzTarg(player, stronaGracza.getOrDefault(player.getUniqueId(), 0), otwartoZMenu.getOrDefault(player.getUniqueId(), false));

                Player sprzedawca = Bukkit.getPlayer(UUID.fromString(sprzedawcaUUID));
                if (sprzedawca != null && sprzedawca.isOnline()) {
                    sprzedawca.sendMessage(Component.text("Ktoś kupił twój przedmiot na targu za " + cena + " $!", NamedTextColor.GOLD));
                }
            } else {
                player.sendMessage(Component.text("Nie masz wystarczająco pieniędzy, aby to kupić!", NamedTextColor.RED));
            }
        }
    }
}