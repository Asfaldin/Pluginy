package elo.mainplugins.crates;

import elo.mainplugins.core.api.CrateService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Skrzynki w stylu CS: skrzynka + klucz to zwykłe przedmioty oznaczone tagiem
 * PersistentDataContainer (ten sam wzorzec, co narzędzia w mainplugins-tools).
 * PPM skrzynką w ręku (mając gdziekolwiek w ekwipunku klucz) zużywa oba i otwiera
 * GUI z animacją "ruletki" - jedyny sposób na coś, co wygląda jak płynne przewijanie
 * w standardowym inwentarzu Minecrafta: szybka podmiana przedmiotów w rzędzie 9
 * slotów, z narastającym opóźnieniem między klatkami (efekt zwalniania), aż
 * "wyląduje" na wcześniej wylosowanej nagrodzie na środkowym slocie.
 *
 * Trzy tiery skrzynek (1 = zwykła, 2/3 = coraz rzadsze) współdzielą klucz i całą
 * animację - różni je tylko wygląd przedmiotu (materiał/nazwa) i WŁASNA pula
 * nagród (osobny plik na tier), rozpoznawana po numerze zapisanym w PDC.
 */
public class CrateManager implements Listener, CrateService {

    private record TierWyglad(Material material, String nazwa, NamedTextColor kolor, String plikNagrod) {}

    private static final Map<Integer, TierWyglad> WYGLAD_TIERU = Map.of(
            1, new TierWyglad(Material.ENDER_CHEST, "Tajemnicza Skrzynka", NamedTextColor.GOLD, "crate-rewards.yml"),
            2, new TierWyglad(Material.SHULKER_BOX, "Otchłanna Skrzynka", NamedTextColor.LIGHT_PURPLE, "crate-rewards-2.yml"),
            3, new TierWyglad(Material.BEACON, "Skrzynka DARKSTAR", NamedTextColor.AQUA, "crate-rewards-3.yml")
    );

    private final Plugin plugin;
    private final NamespacedKey kluczTag;
    private final NamespacedKey skrzynkaTag;
    private final NamespacedKey tierTag;
    private final Map<Integer, List<Nagroda>> nagrodyTieru = new LinkedHashMap<>();
    private final Set<UUID> otwierajacy = new java.util.HashSet<>();

    // Harmonogram opóźnień (w tickach) między kolejnymi klatkami animacji -
    // rosnące wartości = wizualne zwalnianie "ruletki" pod koniec, jak w CS.
    private static final int[] OPOZNIENIA_TICK = {
            1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 11, 13, 15, 18, 21, 25
    };
    private static final int SZEROKOSC_OKNA = 9;
    private static final int WIERSZ_ANIMACJI = 9; // pierwszy slot środkowego rzędu w 27-slotowym GUI

    public CrateManager(Plugin plugin) {
        this.plugin = plugin;
        this.kluczTag = new NamespacedKey(plugin, "crate_key");
        this.skrzynkaTag = new NamespacedKey(plugin, "crate_box");
        this.tierTag = new NamespacedKey(plugin, "crate_tier");
        wczytajWszystkieNagrody();
    }

    public void przeladujNagrody() {
        wczytajWszystkieNagrody();
    }

    private void wczytajWszystkieNagrody() {
        for (int tier : WYGLAD_TIERU.keySet()) {
            wczytajNagrody(tier);
        }
    }

    private void wczytajNagrody(int tier) {
        File plikNagrod = new File(plugin.getDataFolder(), WYGLAD_TIERU.get(tier).plikNagrod());
        if (!plikNagrod.exists()) {
            plikNagrod.getParentFile().mkdirs();
            FileConfiguration domyslny = new YamlConfiguration();
            domyslny.set("rewards", domyslnaPula(tier));
            try {
                domyslny.save(plikNagrod);
            } catch (IOException e) {
                plugin.getLogger().warning("Nie można zapisać " + plikNagrod.getName() + ": " + e.getMessage());
            }
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(plikNagrod);
        List<Nagroda> nagrody = new ArrayList<>();
        for (Map<?, ?> wpis : config.getMapList("rewards")) {
            try {
                Material material = Material.valueOf(String.valueOf(wpis.get("material")));
                int amount = wpis.get("amount") != null ? ((Number) wpis.get("amount")).intValue() : 1;
                int waga = wpis.get("weight") != null ? ((Number) wpis.get("weight")).intValue() : 1;
                String nazwa = wpis.get("name") != null ? String.valueOf(wpis.get("name")) : material.name();
                NamedTextColor kolor = parsujKolor(wpis.get("color") != null ? String.valueOf(wpis.get("color")) : "white");
                Object broadcastRaw = wpis.get("broadcast");
                boolean broadcast = broadcastRaw != null && Boolean.parseBoolean(String.valueOf(broadcastRaw));
                nagrody.add(new Nagroda(material, Math.max(1, amount), Math.max(1, waga), nazwa, kolor, broadcast));
            } catch (Exception e) {
                plugin.getLogger().warning("Zły wpis w " + plikNagrod.getName() + ", pomijam: " + e.getMessage());
            }
        }

        if (nagrody.isEmpty()) {
            nagrody.add(new Nagroda(Material.DIRT, 1, 1, "Nagroda", NamedTextColor.WHITE, false));
        }
        nagrodyTieru.put(tier, nagrody);
    }

    private List<Map<String, Object>> domyslnaPula(int tier) {
        List<Map<String, Object>> lista = new ArrayList<>();
        switch (tier) {
            case 2 -> {
                lista.add(wpisDomyslny("IRON_BLOCK", 2, 30, "Żelazna Nagroda", "WHITE", false));
                lista.add(wpisDomyslny("DIAMOND", 4, 25, "Diamentowa Nagroda", "AQUA", false));
                lista.add(wpisDomyslny("EXPERIENCE_BOTTLE", 32, 20, "Butelki Doświadczenia", "GREEN", false));
                lista.add(wpisDomyslny("NETHERITE_SCRAP", 2, 15, "Złom Netherytu", "LIGHT_PURPLE", false));
                lista.add(wpisDomyslny("ELYTRA", 1, 6, "★ ELYTRA ★", "GOLD", true));
                lista.add(wpisDomyslny("NETHER_STAR", 1, 4, "★ GWIAZDA NETHERU ★", "GOLD", true));
            }
            case 3 -> {
                lista.add(wpisDomyslny("NETHERITE_INGOT", 2, 30, "Sztabki Netherytu", "LIGHT_PURPLE", false));
                lista.add(wpisDomyslny("ENCHANTED_GOLDEN_APPLE", 2, 20, "Złote Jabłka", "GOLD", false));
                lista.add(wpisDomyslny("TOTEM_OF_UNDYING", 1, 20, "★ TOTEM NIEŚMIERTELNOŚCI ★", "LIGHT_PURPLE", true));
                lista.add(wpisDomyslny("ELYTRA", 1, 15, "★ ELYTRA ★", "GOLD", true));
                lista.add(wpisDomyslny("NETHER_STAR", 2, 10, "★ GWIAZDY NETHERU ★", "GOLD", true));
                lista.add(wpisDomyslny("BEACON", 1, 5, "★★ BEACON ★★", "AQUA", true));
            }
            default -> {
                lista.add(wpisDomyslny("DIRT", 16, 40, "Zwykła Nagroda", "GRAY", false));
                lista.add(wpisDomyslny("IRON_INGOT", 8, 30, "Żelazna Nagroda", "WHITE", false));
                lista.add(wpisDomyslny("DIAMOND", 4, 20, "Diamentowa Nagroda", "AQUA", false));
                lista.add(wpisDomyslny("NETHERITE_INGOT", 1, 9, "Netherytowa Nagroda", "LIGHT_PURPLE", false));
                lista.add(wpisDomyslny("NETHER_STAR", 1, 1, "★ LEGENDARNA NAGRODA ★", "GOLD", true));
            }
        }
        return lista;
    }

    private Map<String, Object> wpisDomyslny(String material, int amount, int weight, String nazwa, String kolor, boolean broadcast) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("material", material);
        m.put("amount", amount);
        m.put("weight", weight);
        m.put("name", nazwa);
        m.put("color", kolor);
        m.put("broadcast", broadcast);
        return m;
    }

    private NamedTextColor parsujKolor(String nazwa) {
        NamedTextColor kolor = NamedTextColor.NAMES.value(nazwa.toLowerCase());
        return kolor != null ? kolor : NamedTextColor.WHITE;
    }

    private Nagroda losujNagrode(int tier) {
        List<Nagroda> nagrody = nagrodyTieru.getOrDefault(tier, nagrodyTieru.get(1));
        int sumaWag = nagrody.stream().mapToInt(Nagroda::waga).sum();
        int los = ThreadLocalRandom.current().nextInt(sumaWag);
        int akumulator = 0;
        for (Nagroda n : nagrody) {
            akumulator += n.waga();
            if (los < akumulator) return n;
        }
        return nagrody.getLast();
    }

    // ---- Przedmioty ----

    @Override
    public ItemStack stworzKlucz() {
        ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Klucz do Skrzynki", NamedTextColor.YELLOW, TextDecoration.BOLD));
        meta.lore(List.of(Component.text("Otwiera Skrzynkę dowolnego tieru", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(kluczTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public ItemStack stworzSkrzynke(int tier) {
        TierWyglad wyglad = WYGLAD_TIERU.getOrDefault(tier, WYGLAD_TIERU.get(1));
        int tierRzeczywisty = WYGLAD_TIERU.containsKey(tier) ? tier : 1;

        ItemStack item = new ItemStack(wyglad.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(wyglad.nazwa(), wyglad.kolor(), TextDecoration.BOLD));
        meta.lore(List.of(
                Component.text("Kliknij PPM trzymając ją w ręce,", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("mając klucz gdziekolwiek w ekwipunku!", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(skrzynkaTag, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(tierTag, PersistentDataType.INTEGER, tierRzeczywisty);
        item.setItemMeta(meta);
        return item;
    }

    /** Zwraca tier skrzynki (1-3) albo -1, jeśli item nie jest skrzynką. */
    private int pobierzTierSkrzynki(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return -1;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(skrzynkaTag, PersistentDataType.BYTE)) return -1;
        Integer tier = pdc.get(tierTag, PersistentDataType.INTEGER);
        return tier != null ? tier : 1; // skrzynki sprzed wprowadzenia tierów = tier 1
    }

    private boolean jestKluczem(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(kluczTag, PersistentDataType.BYTE);
    }

    // ---- Otwieranie ----

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack wRece = player.getInventory().getItemInMainHand();
        int tier = pobierzTierSkrzynki(wRece);
        if (tier == -1) return;

        // Zawsze anulujemy - inaczej ENDER_CHEST/SHULKER_BOX w ręku próbowałby się postawić jako blok.
        event.setCancelled(true);

        if (otwierajacy.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("Już otwierasz skrzynkę!", NamedTextColor.RED));
            return;
        }

        int slotKlucza = znajdzSlotKlucza(player);
        if (slotKlucza == -1) {
            player.sendMessage(Component.text("Potrzebujesz klucza, aby otworzyć tę skrzynkę!", NamedTextColor.RED));
            return;
        }

        zmniejszWGlownejRece(player, wRece);
        zmniejszWSlocie(player, slotKlucza);

        rozpocznijAnimacje(player, tier);
    }

    private int znajdzSlotKlucza(Player player) {
        ItemStack[] zawartosc = player.getInventory().getContents();
        for (int i = 0; i < zawartosc.length; i++) {
            if (jestKluczem(zawartosc[i])) return i;
        }
        return -1;
    }

    private void zmniejszWGlownejRece(Player player, ItemStack item) {
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private void zmniejszWSlocie(Player player, int slot) {
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null) return;
        if (item.getAmount() <= 1) {
            player.getInventory().setItem(slot, null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private void rozpocznijAnimacje(Player player, int tier) {
        otwierajacy.add(player.getUniqueId());
        Nagroda wygrana = losujNagrode(tier);

        int liczbaKlatek = OPOZNIENIA_TICK.length;
        List<ItemStack> pasek = new ArrayList<>(liczbaKlatek + SZEROKOSC_OKNA);
        for (int i = 0; i < liczbaKlatek + SZEROKOSC_OKNA; i++) {
            pasek.add(stworzWyswietlacz(losujNagrode(tier)));
        }
        int indeksWygranej = (liczbaKlatek - 1) + 4; // środkowy slot okna przy ostatniej klatce
        pasek.set(indeksWygranej, stworzWyswietlacz(wygrana));

        String tytul = WYGLAD_TIERU.getOrDefault(tier, WYGLAD_TIERU.get(1)).nazwa();
        Inventory gui = Bukkit.createInventory(null, 27, Component.text("Otwieranie: " + tytul, NamedTextColor.GOLD, TextDecoration.BOLD));
        wypelnijTlo(gui);
        gui.setItem(4, wskaznik("▼"));
        gui.setItem(22, wskaznik("▲"));
        player.openInventory(gui);

        animujKlatke(player, gui, pasek, 0, wygrana);
    }

    private void animujKlatke(Player player, Inventory gui, List<ItemStack> pasek, int krok, Nagroda wygrana) {
        if (!player.isOnline()) {
            otwierajacy.remove(player.getUniqueId());
            return;
        }

        for (int i = 0; i < SZEROKOSC_OKNA; i++) {
            gui.setItem(WIERSZ_ANIMACJI + i, pasek.get(krok + i));
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);

        if (krok < OPOZNIENIA_TICK.length - 1) {
            int nastepnyKrok = krok + 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> animujKlatke(player, gui, pasek, nastepnyKrok, wygrana), OPOZNIENIA_TICK[krok]);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> zakonczAnimacje(player, wygrana), 30L);
        }
    }

    private void zakonczAnimacje(Player player, Nagroda wygrana) {
        otwierajacy.remove(player.getUniqueId());
        player.closeInventory();

        ItemStack nagrodaItem = new ItemStack(wygrana.material(), wygrana.amount());
        Map<Integer, ItemStack> nieZmieszczone = player.getInventory().addItem(nagrodaItem);
        for (ItemStack niedopasowany : nieZmieszczone.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), niedopasowany);
        }

        Component nazwaNagrody = Component.text(wygrana.nazwa(), wygrana.kolor(), TextDecoration.BOLD);
        player.sendMessage(Component.text("Wylosowałeś: ", NamedTextColor.GREEN)
                .append(nazwaNagrody)
                .append(Component.text("!", NamedTextColor.GREEN)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        if (wygrana.broadcast()) {
            Bukkit.broadcast(Component.text(player.getName() + " wylosował ", NamedTextColor.GOLD)
                    .append(nazwaNagrody)
                    .append(Component.text(" ze Skrzynki! ✦", NamedTextColor.GOLD)));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().title().toString().contains("Otwieranie:")) {
            event.setCancelled(true);
        }
    }

    private ItemStack stworzWyswietlacz(Nagroda n) {
        ItemStack item = new ItemStack(n.material(), Math.min(64, n.amount()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(n.nazwa(), n.kolor(), TextDecoration.BOLD));
        item.setItemMeta(meta);
        return item;
    }

    private void wypelnijTlo(Inventory gui) {
        ItemStack tlo = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, tlo);
    }

    private ItemStack wskaznik(String strzalka) {
        ItemStack item = new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(strzalka, NamedTextColor.YELLOW, TextDecoration.BOLD));
        item.setItemMeta(meta);
        return item;
    }
}