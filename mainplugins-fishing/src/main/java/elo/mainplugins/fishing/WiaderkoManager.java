package elo.mainplugins.fishing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bezdenne Wiaderko - przenośny "worek" na złowione ryby (user 2026-08-30). Zwykłe
 * wiaderko z wodą, świecące jak zaczarowane, wielkości podwójnej skrzyni (54 sloty).
 * Zawartość jest zapisana NA SAMYM ITEMIE (PersistentDataContainer, serializowane przez
 * BukkitObjectOutputStream - ten sam sprawdzony wzorzec co np. EssentialsX /vault) - nie
 * per-gracz w osobnym pliku, więc wiaderko można oddać/sprzedać/zgubić razem z rybami w
 * środku, dokładnie jak każdy inny przedmiot (user: "gdy się je kupi albo zdobędzie" -
 * docelowy sposób zdobycia jeszcze NIE ustalony, patrz /wiaderko w MainpluginsFishing -
 * na razie tymczasowa komenda admina).
 *
 * Ryby lecą do wiaderka NAJPIERW (patrz sprobujWlozycRybe, wołane z
 * FishingManager.nagrodaZaPolow) - jeśli gracz ma je GDZIEKOLWIEK w ekwipunku (nie musi
 * trzymać w ręce - to pasywne, user 2026-08-30), dopiero gdy wiaderko jest pełne (albo
 * gracz go nie ma), ryba trafia do zwykłego ekwipunku jak dotychczas. OTWIERANIE
 * (podgląd/wyjmowanie zawartości) wymaga trzymania go w ręce i kliknięcia PPM - to
 * osobna, świadoma czynność, w odróżnieniu od pasywnego zbierania.
 */
public final class WiaderkoManager implements Listener {

    private static final String TYTUL = "Bezdenne Wiaderko";
    private static final int ROZMIAR = 54; // "wielkościowo jak podwójna skrzynia" - user 2026-08-30

    private final Plugin plugin;
    private final NamespacedKey kluczZawartosci;

    // Slot (0-8, hotbar - patrz PlayerInventory.getHeldItemSlot) skąd otwarto GUI danego
    // gracza - zapamiętany PRZY OTWARCIU, żeby przy zamknięciu zapisać zawartość z
    // powrotem do TEGO KONKRETNEGO fizycznego itemu, niezależnie od tego czy gracz w
    // międzyczasie przełączył trzymany slot na coś innego (patrz onZamkniecie).
    private final Map<UUID, Integer> otwarteZeSlotu = new HashMap<>();

    public WiaderkoManager(Plugin plugin) {
        this.plugin = plugin;
        this.kluczZawartosci = new NamespacedKey(plugin, "wiaderko_zawartosc");
    }

    /** Tag PDC z zawartością jest jednocześnie znacznikiem "to jest Bezdenne Wiaderko" - osobny marker niepotrzebny. */
    public boolean jestWiaderkiem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(kluczZawartosci, PersistentDataType.BYTE_ARRAY);
    }

    /** Nowe, puste Bezdenne Wiaderko - patrz /wiaderko w MainpluginsFishing. */
    public ItemStack stworzWiaderko() {
        ItemStack item = new ItemStack(Material.WATER_BUCKET);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Bezdenne Wiaderko", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Złowione ryby lecą tu najpierw,", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("zamiast zapełniać Twój ekwipunek.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1); // nie da sie stackowac - kazde wiaderko ma WLASNA, rozna zawartosc
        meta.getPersistentDataContainer().set(kluczZawartosci, PersistentDataType.BYTE_ARRAY, serializuj(new ItemStack[ROZMIAR]));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Próbuje wcisnąć złowioną rybę do PIERWSZEGO wiaderka z wolnym miejscem, GDZIEKOLWIEK
     * w ekwipunku gracza (user 2026-08-30: pasywne, nie trzeba trzymać w ręce) - patrz
     * FishingManager.nagrodaZaPolow. Zwraca null jeśli ryba w CAŁOŚCI trafiła do wiaderka
     * (nic więcej do zrobienia), albo TĘ SAMĄ rybę z powrotem jeśli gracz nie ma żadnego
     * wiaderka z miejscem - wtedy wołający ma iść normalną ścieżką (ekwipunek/drop).
     */
    public ItemStack sprobujWlozycRybe(Player player, ItemStack ryba) {
        ItemStack[] zawartoscEq = player.getInventory().getContents();
        for (int slot = 0; slot < zawartoscEq.length; slot++) {
            ItemStack kandydat = zawartoscEq[slot];
            if (!jestWiaderkiem(kandydat)) continue;

            Inventory tymczasowa = Bukkit.createInventory(null, ROZMIAR);
            tymczasowa.setContents(odczytajZawartosc(kandydat));
            Map<Integer, ItemStack> nieZmieszczone = tymczasowa.addItem(ryba);
            if (nieZmieszczone.isEmpty()) {
                zapiszZawartosc(kandydat, tymczasowa.getContents());
                player.getInventory().setItem(slot, kandydat);
                return null;
            }
            // To akurat wiaderko pelne (albo brak miejsca na TEN item) - probuj kolejnego,
            // jesli gracz ma wiecej niz jedno (petla leci dalej).
        }
        return ryba;
    }

    /** Otwiera GUI z zawartością wiaderka trzymanego w ręce - patrz onInteract. */
    private void otworz(Player player, ItemStack wiaderko, int slotZrodlowy) {
        Inventory gui = Bukkit.createInventory(null, ROZMIAR, Component.text(TYTUL, NamedTextColor.AQUA, TextDecoration.BOLD));
        gui.setContents(odczytajZawartosc(wiaderko));
        otwarteZeSlotu.put(player.getUniqueId(), slotZrodlowy);
        player.openInventory(gui);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (!jestWiaderkiem(wRece)) return;

        event.setCancelled(true);
        otworz(player, wRece, player.getInventory().getHeldItemSlot());
    }

    /**
     * Zapisuje zawartość GUI z powrotem NA FIZYCZNY item, w slocie zapamiętanym przy
     * otwarciu (patrz otwarteZeSlotu) - NIE "aktualnie trzymany item", żeby przełączenie
     * slotu w hotbarze w trakcie przeglądania nie zgubiło zmian. Jeśli w tym slocie nie ma
     * już (tego samego) wiaderka - np. gracz je wyrzucił w trakcie - po cichu nic nie robi,
     * zamiast nadpisać cokolwiek innego tam wylądowało.
     */
    @EventHandler
    public void onZamkniecie(InventoryCloseEvent event) {
        String tytul = event.getView().title().toString();
        if (!tytul.contains(TYTUL)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        Integer slotZrodlowy = otwarteZeSlotu.remove(player.getUniqueId());
        if (slotZrodlowy == null) return;

        ItemStack wciazTam = player.getInventory().getItem(slotZrodlowy);
        if (!jestWiaderkiem(wciazTam)) return;

        zapiszZawartosc(wciazTam, event.getInventory().getContents());
        player.getInventory().setItem(slotZrodlowy, wciazTam);
    }

    /**
     * Zabezpieczenie przed włożeniem wiaderka DO WNĘTRZA samego siebie (albo innego
     * wiaderka) - klasyczny problem "shulker w shulkerze", tu jeszcze bardziej krytyczny,
     * bo zagnieżdżona serializacja mogłaby popsuć/zdublować zawartość. Łapie zarówno
     * przeciąganie kursorem, jak i shift-klik oraz zamianę klawiszem numerycznym (1-9).
     */
    @EventHandler
    public void onKlik(InventoryClickEvent event) {
        String tytul = event.getView().title().toString();
        if (!tytul.contains(TYTUL)) return;

        int rozmiarGory = event.getView().getTopInventory().getSize();
        boolean docelowyGora = event.getRawSlot() < rozmiarGory;

        boolean zKursora = docelowyGora && jestWiaderkiem(event.getCursor());
        boolean shiftKlikiem = event.isShiftClick() && event.getClickedInventory() == event.getView().getBottomInventory()
                && jestWiaderkiem(event.getCurrentItem());
        boolean zamianaNumerkiem = event.getClick() == ClickType.NUMBER_KEY && docelowyGora && event.getHotbarButton() >= 0
                && jestWiaderkiem(player(event).getInventory().getItem(event.getHotbarButton()));

        if (zKursora || shiftKlikiem || zamianaNumerkiem) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPrzeciagniecie(InventoryDragEvent event) {
        String tytul = event.getView().title().toString();
        if (!tytul.contains(TYTUL)) return;
        if (!jestWiaderkiem(event.getOldCursor())) return;

        int rozmiarGory = event.getView().getTopInventory().getSize();
        boolean dotykaGory = event.getRawSlots().stream().anyMatch(slot -> slot < rozmiarGory);
        if (dotykaGory) event.setCancelled(true);
    }

    private Player player(InventoryClickEvent event) {
        return (Player) event.getWhoClicked();
    }

    private ItemStack[] odczytajZawartosc(ItemStack wiaderko) {
        byte[] dane = wiaderko.getItemMeta().getPersistentDataContainer().get(kluczZawartosci, PersistentDataType.BYTE_ARRAY);
        return dane != null ? deserializuj(dane) : new ItemStack[ROZMIAR];
    }

    private void zapiszZawartosc(ItemStack wiaderko, ItemStack[] zawartosc) {
        ItemMeta meta = wiaderko.getItemMeta();
        meta.getPersistentDataContainer().set(kluczZawartosci, PersistentDataType.BYTE_ARRAY, serializuj(zawartosc));
        wiaderko.setItemMeta(meta);
    }

    /** Standardowy wzorzec serializacji ItemStack[] przez Bukkita (ten sam co np. EssentialsX /vault) - odporny na null-e w tablicy (puste sloty). */
    private byte[] serializuj(ItemStack[] zawartosc) {
        try (ByteArrayOutputStream bajty = new ByteArrayOutputStream();
             BukkitObjectOutputStream strumien = new BukkitObjectOutputStream(bajty)) {
            strumien.writeInt(zawartosc.length);
            for (ItemStack item : zawartosc) strumien.writeObject(item);
            return bajty.toByteArray();
        } catch (IOException e) {
            plugin.getLogger().severe("Wiaderko: nie udalo sie zserializowac zawartosci - " + e);
            return new byte[0];
        }
    }

    private ItemStack[] deserializuj(byte[] dane) {
        try (ByteArrayInputStream bajty = new ByteArrayInputStream(dane);
             BukkitObjectInputStream strumien = new BukkitObjectInputStream(bajty)) {
            int rozmiar = strumien.readInt();
            ItemStack[] zawartosc = new ItemStack[rozmiar];
            for (int i = 0; i < rozmiar; i++) zawartosc[i] = (ItemStack) strumien.readObject();
            return zawartosc;
        } catch (IOException | ClassNotFoundException e) {
            plugin.getLogger().severe("Wiaderko: nie udalo sie odczytac zawartosci, zwracam puste - " + e);
            return new ItemStack[ROZMIAR];
        }
    }
}
