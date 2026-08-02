package elo.mainplugins;

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

public class MarketManager implements Listener {

    private final Plugin plugin;
    private final EconomyManager economyManager;
    private final File plikRynku;
    private final FileConfiguration configRynku;

    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();
    private final Map<UUID, Integer> stronaGracza = new HashMap<>();

    // Zapamiętuje który slot w GUI odpowiada za który przedmiot w pliku
    private final Map<UUID, Map<Integer, String>> slotyRynku = new HashMap<>();

    public MarketManager(Plugin plugin, EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;

        this.plikRynku = new File(plugin.getDataFolder(), "rynek.yml");
        if (!plikRynku.exists()) {
            plikRynku.getParentFile().mkdirs();
            try { plikRynku.createNewFile(); } catch (IOException ignored) {}
        }
        this.configRynku = YamlConfiguration.loadConfiguration(plikRynku);
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
    }

    public void otworzTarg(Player player, int strona) {
        otworzTarg(player, strona, false);
    }

    public void otworzTarg(Player player, int strona, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        stronaGracza.put(player.getUniqueId(), strona);

        List<String> wszystkieKlucze = new ArrayList<>();
        if (configRynku.contains("przedmioty")) {
            wszystkieKlucze.addAll(configRynku.getConfigurationSection("przedmioty").getKeys(false));
        }

        int maxStron = Math.max(1, (int) Math.ceil((double) wszystkieKlucze.size() / 45.0));
        if (strona >= maxStron) strona = maxStron - 1;
        if (strona < 0) strona = 0;

        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Rynek (Str. " + (strona + 1) + ")", NamedTextColor.GOLD, TextDecoration.BOLD));
        Map<Integer, String> mapaSlotow = new HashMap<>();

        // Wypełnianie przedmiotami
        int poczatek = strona * 45;
        int koniec = Math.min(poczatek + 45, wszystkieKlucze.size());
        int slot = 0;

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
                gui.setItem(slot, wyswietlany);
                mapaSlotow.put(slot, klucz);
                slot++;
            }
        }

        slotyRynku.put(player.getUniqueId(), mapaSlotow);

        // Tło paska dolnego (sloty 45-53)
        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta mTlo = tlo.getItemMeta();
        mTlo.displayName(Component.empty());
        tlo.setItemMeta(mTlo);
        for (int i = 45; i < 54; i++) {
            gui.setItem(i, tlo);
        }

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
                player.sendMessage(Component.text("Nie możesz kupić własnego przedmiotu!", NamedTextColor.RED));
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