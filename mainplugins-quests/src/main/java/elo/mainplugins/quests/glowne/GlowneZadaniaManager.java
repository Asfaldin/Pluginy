package elo.mainplugins.quests.glowne;

import elo.mainplugins.skyblock.event.IslandBankDepositEvent;
import elo.mainplugins.skyblock.event.IslandCreatedEvent;
import elo.mainplugins.skyblock.event.IslandUpgradeEvent;
import elo.mainplugins.skyblock.event.SnifferPlacedEvent;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kategoria "Główne Zadania" - osobny, AKCYJNY system questów (w przeciwieństwie
 * do reszty QuestManager, który jest "przynieś przedmiot i oddaj") - śledzi
 * REALNE akcje gracza na wyspie przez customowe eventy z mainplugins-skyblock
 * (elo.mainplugins.skyblock.event.*), a nie zawartość ekwipunku. To ma być
 * tutorial prowadzący nowego gracza za rękę przez podstawy automatyzacji wyspy.
 *
 * GUI: "wężyk" (SLOTY_WEZYK) - zigzag przez siatkę 7x5, rozłożony na 3 strony
 * (tylko strona 0 ma realną treść na start - "będziemy to balansować").
 *
 * Tutorialowy NPC (Citizens) jest PRZYPISANY DO WYSPY, nie wspólny dla serwera -
 * spawnuje się automatycznie przy tworzeniu KAŻDEJ wyspy (patrz onIslandCreated),
 * blisko jej środka, i jest TRWALE NISZCZONY (nie tylko chowany), gdy właściciel
 * tej konkretnej wyspy ukończy wszystkie zadania strony 0 (patrz
 * zniszczNpcJesliWlascicielUkonczyl). Wyspy założone PRZED wdrożeniem tego systemu
 * nie dostaną NPC-a wstecznie - to świadome ograniczenie zakresu, nie przeoczenie.
 */
public class GlowneZadaniaManager implements Listener {

    private record GlowneZadanie(int id, int page, String tytul, List<String> opis, String triggerKey, Material ikona, ItemStack nagroda, String nazwaNagrody) {}

    // Kolejność tworzy ciągły zygzak: koniec rzędu 0 (slot 7) jest bezpośrednio NAD
    // początkiem rzędu 1 (slot 16), koniec rzędu 1 (slot 10) NAD początkiem rzędu 2 (slot 19) itd.
    private static final int[] SLOTY_WEZYK = {
            1, 2, 3, 4, 5, 6, 7,
            16, 15, 14, 13, 12, 11, 10,
            19, 20, 21, 22, 23, 24, 25,
            34, 33, 32, 31, 30, 29, 28,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final int LICZBA_STRON = 3;
    private static final String TYTUL_PREFIX = "Główne Zadania";

    private final List<GlowneZadanie> zadania = new ArrayList<>();
    private final Set<Integer> zadaniaStrony0 = new HashSet<>(); // "tutorial" - komplet = NPC znika dla gracza

    private final Map<UUID, Set<Integer>> postepyGraczy = new HashMap<>();
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    private final Plugin plugin;
    private final File plikZadania;
    private final YamlConfiguration configZadania;

    // Właściciel wyspy -> ID (rejestr Citizens) jej tutorialowego NPC-a. Wpis znika
    // (mapa I plik), gdy NPC zostaje zniszczony po ukończeniu tutorialu.
    private final Map<UUID, Integer> npcPerWyspa = new HashMap<>();

    public GlowneZadaniaManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikZadania = new File(plugin.getDataFolder(), "glowne_zadania.yml");
        if (!plikZadania.exists()) {
            plikZadania.getParentFile().mkdirs();
            try { plikZadania.createNewFile(); } catch (IOException ignored) {}
        }
        this.configZadania = YamlConfiguration.loadConfiguration(plikZadania);

        zaladujZadania();
        wczytajPostep();
    }

    /**
     * Wyłącznie strona 0 ma na razie realną treść (4 zadania solo-do-zrobienia,
     * bez zależności od innych graczy) - reszta to placeholdery do wypełnienia
     * później ("będziemy to balansować"). Trigger keys MUSZĄ się zgadzać z tym,
     * co wysyłają onIslandCreated/onBankDeposit/onIslandUpgrade/onSnifferPlaced niżej.
     */
    private void zaladujZadania() {
        zadania.add(new GlowneZadanie(1, 0, "Załóż Wyspę",
                List.of("Wpisz /is, aby stworzyć swoją pierwszą wyspę."),
                "STWORZ_WYSPE", Material.GRASS_BLOCK,
                new ItemStack(Material.OAK_SAPLING, 16), "16x Sadzonka Dębu"));

        zadania.add(new GlowneZadanie(2, 0, "Zasil Bank Wyspy",
                List.of("Wpłać dowolną kwotę przez /is deposit <kwota>.", "Bank wyspy to jedyne źródło pieniędzy na ulepszenia!"),
                "WPLAC_DO_BANKU", Material.GOLD_INGOT,
                new ItemStack(Material.IRON_INGOT, 5), "5x Żelazo"));

        zadania.add(new GlowneZadanie(3, 0, "Powiększ Wyspę",
                List.of("Kup pierwsze powiększenie terenu w Ulepszeniach Wyspy."),
                "KUP_ULEPSZENIE", Material.BEACON,
                new ItemStack(Material.DIAMOND, 1), "1x Diament"));

        zadania.add(new GlowneZadanie(4, 0, "Zatrudnij Farmera",
                List.of("Kup Snifferaa Farmera w sklepie i postaw go na wyspie.", "Będzie automatycznie zbierał i sadził uprawy!"),
                "POSTAW_SNIFFERA", Material.SNIFFER_EGG,
                new ItemStack(Material.GOLDEN_HOE, 1), "1x Złota Motyka"));

        for (GlowneZadanie z : zadania) {
            if (z.page() == 0) zadaniaStrony0.add(z.id());
        }
        // Strony 1 i 2 celowo puste na razie - GUI renderuje dla nich placeholder "Wkrótce".
    }

    // ---- Persystencja (patrz ustalenie: zapis TYLKO dla Głównych Zadań, reszta questów bez zmian) ----

    private void wczytajPostep() {
        org.bukkit.configuration.ConfigurationSection sekcja = configZadania.getConfigurationSection("gracze");
        if (sekcja != null) {
            for (String uuidStr : sekcja.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    Set<Integer> ukonczone = new HashSet<>(configZadania.getIntegerList("gracze." + uuidStr));
                    postepyGraczy.put(uuid, ukonczone);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        org.bukkit.configuration.ConfigurationSection npcSekcja = configZadania.getConfigurationSection("wyspoweNpc");
        if (npcSekcja != null) {
            for (String uuidStr : npcSekcja.getKeys(false)) {
                try {
                    npcPerWyspa.put(UUID.fromString(uuidStr), npcSekcja.getInt(uuidStr));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void zapiszPostep() {
        configZadania.set("gracze", null);
        for (Map.Entry<UUID, Set<Integer>> entry : postepyGraczy.entrySet()) {
            configZadania.set("gracze." + entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        configZadania.set("wyspoweNpc", null);
        for (Map.Entry<UUID, Integer> entry : npcPerWyspa.entrySet()) {
            configZadania.set("wyspoweNpc." + entry.getKey(), entry.getValue());
        }
        try {
            configZadania.save(plikZadania);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać glowne_zadania.yml: " + e.getMessage());
        }
    }

    // ---- NPC (Citizens) - przypisany do wyspy, patrz komentarz klasy ----

    // Przesunięcie NPC-a względem środka wyspy (ten sam punkt X/Z, w który wklejany
    // jest schemat startowy - patrz IslandManager.stworzWyspe) - lekko z boku, żeby
    // nie stał dokładnie na tym samym bloku co gracz przy pierwszym teleporcie.
    private static final double NPC_OFFSET_X = 2.5;
    private static final double NPC_OFFSET_Z = 0.5;
    private static final double NPC_Y = 101;

    /** Spawnuje tutorialowego NPC-a blisko środka NOWO założonej wyspy - patrz onIslandCreated. */
    private void zaspawnujNpcDlaWyspy(Player owner, elo.mainplugins.skyblock.IslandManager.IslandData island) {
        if (npcPerWyspa.containsKey(island.getOwnerUUID())) return; // zabezpieczenie - nie powinno się zdarzyć, ale nie dublujemy

        Location spawnLoc = new Location(owner.getWorld(),
                island.getCenterX() + NPC_OFFSET_X, NPC_Y, island.getCenterZ() + NPC_OFFSET_Z);

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.VILLAGER, "Przewodnik Wyspy");
        npc.spawn(spawnLoc);
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, true);

        npcPerWyspa.put(island.getOwnerUUID(), npc.getId());
        zapiszPostep();
    }

    @EventHandler
    public void onNpcClick(NPCRightClickEvent event) {
        if (!npcPerWyspa.containsValue(event.getNPC().getId())) return; // nie nasz NPC (np. inny plugin Citizens na serwerze)
        otworzMenuGlownychZadan(event.getClicker(), 0, false);
    }

    private boolean ukonczonoTutorial(UUID uuid) {
        Set<Integer> ukonczone = postepyGraczy.getOrDefault(uuid, Set.of());
        return ukonczone.containsAll(zadaniaStrony0);
    }

    /**
     * Wołane po KAŻDYM ukończonym zadaniu strony 0 - jeśli `player` jest właścicielem
     * wyspy z aktywnym NPC-em I ma już komplet strony 0, NPC zostaje TRWALE zniszczony
     * (nie chowany - to jego jedyna wyspa, nikt inny go nie potrzebuje).
     */
    private void zniszczNpcJesliWlascicielUkonczyl(Player player) {
        Integer npcId = npcPerWyspa.get(player.getUniqueId());
        if (npcId == null || !ukonczonoTutorial(player.getUniqueId())) return;

        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
        if (npc != null) npc.destroy();

        npcPerWyspa.remove(player.getUniqueId());
        zapiszPostep();

        player.sendMessage(Component.text("Ukończyłeś tutorial! Przewodnik Wyspy zniknął ze swojego miejsca.", NamedTextColor.GOLD, TextDecoration.BOLD));
    }

    // ---- Nasłuch akcji ze skyblocka ----

    @EventHandler
    public void onIslandCreated(IslandCreatedEvent event) {
        zaspawnujNpcDlaWyspy(event.getPlayer(), event.getIsland());
        oznaczUkonczone(event.getPlayer(), "STWORZ_WYSPE");
    }

    @EventHandler
    public void onBankDeposit(IslandBankDepositEvent event) {
        oznaczUkonczone(event.getPlayer(), "WPLAC_DO_BANKU");
    }

    @EventHandler
    public void onIslandUpgrade(IslandUpgradeEvent event) {
        oznaczUkonczone(event.getPlayer(), "KUP_ULEPSZENIE");
    }

    @EventHandler
    public void onSnifferPlaced(SnifferPlacedEvent event) {
        oznaczUkonczone(event.getPlayer(), "POSTAW_SNIFFERA");
    }

    private void oznaczUkonczone(Player player, String triggerKey) {
        GlowneZadanie zadanie = zadania.stream().filter(z -> z.triggerKey().equals(triggerKey)).findFirst().orElse(null);
        if (zadanie == null) return;

        Set<Integer> postepy = postepyGraczy.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        if (postepy.contains(zadanie.id())) return; // już ukończone

        postepy.add(zadanie.id());
        zapiszPostep();

        if (player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(zadanie.nagroda());
        } else {
            player.getWorld().dropItemNaturally(player.getLocation(), zadanie.nagroda());
        }

        player.sendMessage(Component.text("Główne Zadanie ukończone: ", NamedTextColor.GREEN)
                .append(Component.text(zadanie.tytul(), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("! Otrzymałeś: ", NamedTextColor.GREEN))
                .append(Component.text(zadanie.nazwaNagrody(), NamedTextColor.AQUA)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        zniszczNpcJesliWlascicielUkonczyl(player);
    }

    // ---- GUI ----

    public void otworzMenuGlownychZadan(Player player, int strona, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);

        Inventory gui = Bukkit.createInventory(null, 54, Component.text(TYTUL_PREFIX + " | Strona " + (strona + 1), NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);

        Set<Integer> postepy = postepyGraczy.getOrDefault(player.getUniqueId(), Set.of());
        List<GlowneZadanie> naStronie = zadania.stream().filter(z -> z.page() == strona).toList();

        if (naStronie.isEmpty()) {
            ItemStack wkrotce = new ItemStack(Material.CLOCK);
            ItemMeta meta = wkrotce.getItemMeta();
            meta.displayName(Component.text("Wkrótce...", NamedTextColor.GRAY, TextDecoration.BOLD));
            meta.lore(List.of(Component.text("Kolejne zadania w przygotowaniu.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
            wkrotce.setItemMeta(meta);
            gui.setItem(SLOTY_WEZYK[SLOTY_WEZYK.length / 2], wkrotce);
        } else {
            for (int i = 0; i < naStronie.size() && i < SLOTY_WEZYK.length; i++) {
                GlowneZadanie z = naStronie.get(i);
                gui.setItem(SLOTY_WEZYK[i], stworzIkoneZadania(z, postepy.contains(z.id())));
            }
        }

        gui.setItem(49, stworzPrzycisk(Material.DARK_OAK_DOOR, "Powrót do Kategorii", NamedTextColor.GOLD));
        if (strona > 0) gui.setItem(45, stworzPrzycisk(Material.ARROW, "Poprzednia Strona", NamedTextColor.YELLOW));
        if (strona < LICZBA_STRON - 1) gui.setItem(53, stworzPrzycisk(Material.ARROW, "Następna Strona", NamedTextColor.YELLOW));

        player.openInventory(gui);
    }

    private void wypelnijTlo(Inventory gui) {
        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        for (int i = 0; i < 54; i++) gui.setItem(i, tlo);
    }

    private ItemStack stworzPrzycisk(Material mat, String nazwa, NamedTextColor kolor) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, kolor, TextDecoration.BOLD));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack stworzIkoneZadania(GlowneZadanie z, boolean ukonczone) {
        ItemStack item = new ItemStack(ukonczone ? Material.LIME_DYE : z.ikona());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(z.tytul(), ukonczone ? NamedTextColor.GREEN : NamedTextColor.YELLOW, TextDecoration.BOLD));

        List<Component> lore = new ArrayList<>();
        for (String linia : z.opis()) {
            lore.add(Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(ukonczone
                ? Component.text("✔ UKOŃCZONE - " + z.nazwaNagrody(), NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
                : Component.text("Nagroda: " + z.nazwaNagrody(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.startsWith(TYTUL_PREFIX)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        int strona = Integer.parseInt(title.replace(TYTUL_PREFIX + " | Strona ", "").trim()) - 1;

        if (slot == 49) {
            player.closeInventory();
            if (otwartoZMenu.getOrDefault(player.getUniqueId(), false)) {
                player.performCommand("menu");
            }
        } else if (slot == 53) {
            otworzMenuGlownychZadan(player, strona + 1, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
        } else if (slot == 45) {
            otworzMenuGlownychZadan(player, strona - 1, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
        }
        // Kliknięcie w samo zadanie nic nie robi - są akcyjne (wykonywane na wyspie), nie ma tu "zdaj".
    }
}