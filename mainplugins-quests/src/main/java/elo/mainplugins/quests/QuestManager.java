package elo.mainplugins.quests;

import elo.mainplugins.quests.glowne.GlowneZadaniaManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class QuestManager implements Listener {

    private final Map<UUID, Map<String, Set<Integer>>> postepyGraczy = new HashMap<>();
    private final Map<String, List<Quest>> questyKategorii = new HashMap<>();

    // Zmienna zapamiętująca czy gracz wszedł z poziomu /menu
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    // Definiujemy 35 slotów na środku (7 kolumn x 5 rzędów)
    private final int[] slotySrodkowe = {
            1, 2, 3, 4, 5, 6, 7,
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private static final int SLOT_CENTRUM_GWIAZDY = 22; // "Główne Zadania" - środek gwiazdy
    private static final int SLOT_POWROT = 45; // wolny róg, poza wszystkimi ramionami gwiazdy

    // 8 ramion od SLOT_CENTRUM_GWIAZDY na zewnątrz (N,S,E,W,NE,NW,SE,SW) - różnej długości,
    // ograniczone geometrią 54-slotowego (6x9) GUI: pion mieści tylko 2 kroki w każdą stronę
    // od centralnego rzędu bez wchodzenia na SLOT_POWROT, poziom/przekątne w dół mieszczą 3-4.
    // Kolejność w tablicy = kolejność przypisywania kategorii z KATEGORIE_GWIAZDY niżej.
    private static final int[] SLOTY_GWIAZDY = {
            13, 4,           // N
            31, 40, 49,      // S
            23, 24, 25, 26,  // E
            21, 20, 19, 18,  // W
            14, 6,           // NE
            12, 2,           // NW
            32, 42, 52,      // SE
            30, 38, 46       // SW
    };

    private final GlowneZadaniaManager glowneZadania;

    public QuestManager(GlowneZadaniaManager glowneZadania) {
        this.glowneZadania = glowneZadania;
        zaladujQuesty();
    }

    private void zaladujQuesty() {
        // GÓRNICTWO - 40 questów (pokazuje paginację)
        List<Quest> gornictwo = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            gornictwo.add(new Quest(i, "Górnik " + i, Material.COBBLESTONE, 64, new ItemStack(Material.DIAMOND, 1), "1x Diament"));
        }
        questyKategorii.put("Górnictwo", gornictwo);

        // HODOWLA
        questyKategorii.put("Hodowla", List.of(
                new Quest(1, "Zbiory 1", Material.CARROT, 32, new ItemStack(Material.EMERALD, 5), "5x Szmaragd"),
                new Quest(2, "Zbiory 2", Material.WHEAT, 64, new ItemStack(Material.GOLD_INGOT, 10), "10x Złoto"),
                new Quest(3, "Zbiory 3", Material.POTATO, 64, new ItemStack(Material.IRON_INGOT, 32), "32x Żelazo")
        ));

        // ŁOWCA
        questyKategorii.put("Łowca", List.of(
                new Quest(1, "Początkujący", Material.ROTTEN_FLESH, 32, new ItemStack(Material.COOKED_BEEF, 16), "16x Pieczona Wołowina"),
                new Quest(2, "Strzelec", Material.BONE, 16, new ItemStack(Material.BOW, 1), "1x Łuk"),
                new Quest(3, "Nocny Marek", Material.STRING, 10, new ItemStack(Material.EXPERIENCE_BOTTLE, 16), "16x Butelka EXP")
        ));

        // Inicjalizacja pustych list dla kategorii narzędziowych, aby nie rzucały błędem.
        // "Mistrz Siekiery/Motyki/Łopaty" celowo usunięte z listy - to były czyste,
        // nierozróżnialne puste duplikaty (patrz KATEGORIE_GWIAZDY: gwiazda w 54-slotowym
        // GUI ma twardy geometryczny limit ~23 ramion bez zachodzenia na siebie/przycisk
        // powrotu, więc trzeba było skonsolidować najbardziej redundantne puste kategorie).
        questyKategorii.put("Mistrz Kilofa", new ArrayList<>());
        questyKategorii.put("Mistrz Miecza", new ArrayList<>());
    }

    /** Ikona/nazwa/opis kategorii z siatki gwiazdy - kolejność MUSI się zgadzać z SLOTY_GWIAZDY. */
    private record KategoriaGwiazdy(Material ikona, String nazwa, String opis) {}

    private static final List<KategoriaGwiazdy> KATEGORIE_GWIAZDY = List.of(
            // N
            new KategoriaGwiazdy(Material.IRON_PICKAXE, "Górnictwo", "Zadania w kopalni"),
            new KategoriaGwiazdy(Material.WHEAT, "Hodowla", "Zadania rolnicze"),
            // S
            new KategoriaGwiazdy(Material.BOW, "Łowca", "Zadania z potworami"),
            new KategoriaGwiazdy(Material.OAK_LOG, "Drwal", "Zadania z drewnem"),
            new KategoriaGwiazdy(Material.FISHING_ROD, "Rybak", "Zadania wędkarskie"),
            // E
            new KategoriaGwiazdy(Material.BREWING_STAND, "Alchemik", "Warzenie mikstur"),
            new KategoriaGwiazdy(Material.ANVIL, "Kowal", "Tworzenie narzędzi"),
            new KategoriaGwiazdy(Material.COOKED_BEEF, "Kucharz", "Zadania kulinarne"),
            new KategoriaGwiazdy(Material.BRICKS, "Budowniczy", "Budowa wyspy"),
            // W
            new KategoriaGwiazdy(Material.ENCHANTING_TABLE, "Mag", "Zaklęcia"),
            new KategoriaGwiazdy(Material.COMPASS, "Odkrywca", "Eksploracja mapy"),
            new KategoriaGwiazdy(Material.PORKCHOP, "Rzeźnik", "Zdobywanie mięsa"),
            new KategoriaGwiazdy(Material.OAK_SAPLING, "Ogrodnik", "Sadzenie drzew"),
            // NE
            new KategoriaGwiazdy(Material.DIAMOND, "Jubiler", "Cenne kruszce"),
            new KategoriaGwiazdy(Material.GOLD_NUGGET, "Złodziej", "Kradzież (Zadania)"),
            // NW
            new KategoriaGwiazdy(Material.DIAMOND_SWORD, "Wojownik", "Walka PvP/PvE"),
            new KategoriaGwiazdy(Material.EMERALD, "Handlarz", "Wymiana handlowa"),
            // SE
            new KategoriaGwiazdy(Material.REDSTONE, "Inżynier", "Mechanizmy"),
            new KategoriaGwiazdy(Material.ZOMBIE_HEAD, "Zabójca", "Eliminacje"),
            new KategoriaGwiazdy(Material.BONE_MEAL, "Zbieracz", "Zbieranie surowców"),
            // SW
            new KategoriaGwiazdy(Material.NETHER_STAR, "Mistrz", "Zadania elitarne"),
            new KategoriaGwiazdy(Material.DIAMOND_PICKAXE, "Mistrz Kilofa", "Zadania dla kilofa"),
            new KategoriaGwiazdy(Material.DIAMOND_SWORD, "Mistrz Miecza", "Zadania dla miecza")
    );

    // Dodano obsługę argumentu zMenu
    public void otworzMenuQuestow(Player player, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Kategorie Zadań", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);

        // "Główne Zadania" na SAMYM ŚRODKU gwiazdy - to punkt startowy dla nowych graczy,
        // reszta kategorii promieniście dookoła (patrz SLOTY_GWIAZDY/KATEGORIE_GWIAZDY).
        ItemStack glowneZadaniaIkona = stworzIkoneKategorii(Material.KNOWLEDGE_BOOK, "Główne Zadania", "Tutorial - zacznij tutaj!");
        ItemMeta metaGlowne = glowneZadaniaIkona.getItemMeta();
        metaGlowne.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        metaGlowne.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        glowneZadaniaIkona.setItemMeta(metaGlowne);
        gui.setItem(SLOT_CENTRUM_GWIAZDY, glowneZadaniaIkona);

        for (int i = 0; i < SLOTY_GWIAZDY.length && i < KATEGORIE_GWIAZDY.size(); i++) {
            KategoriaGwiazdy k = KATEGORIE_GWIAZDY.get(i);
            gui.setItem(SLOTY_GWIAZDY[i], stworzIkoneKategorii(k.ikona(), k.nazwa(), k.opis()));
        }

        if (zMenu) {
            gui.setItem(SLOT_POWROT, stworzPrzycisk(Material.NETHER_STAR, "« Wróć do Menu głównego", NamedTextColor.RED));
        } else {
            gui.setItem(SLOT_POWROT, stworzPrzycisk(Material.BARRIER, "Zamknij Menu", NamedTextColor.RED));
        }

        player.openInventory(gui);
    }

    public void otworzKategorie(Player player, String nazwaKategorii, int strona) {
        List<Quest> questy = questyKategorii.getOrDefault(nazwaKategorii, new ArrayList<>());

        String tytulMenu = "Strona " + (strona + 1) + " | " + nazwaKategorii;
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(tytulMenu, NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);

        Set<Integer> postepy = postepyGraczy.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(nazwaKategorii, k -> new HashSet<>());

        int startIndex = strona * 35;
        for (int i = 0; i < 35; i++) {
            if (startIndex + i < questy.size()) {
                Quest q = questy.get(startIndex + i);
                boolean ukonczony = postepy.contains(q.id);
                gui.setItem(slotySrodkowe[i], stworzIkoneQuesta(q, ukonczony));
            }
        }

        gui.setItem(49, stworzPrzycisk(Material.DARK_OAK_DOOR, "Powrót do Kategorii", NamedTextColor.GOLD));
        if (strona > 0) gui.setItem(45, stworzPrzycisk(Material.ARROW, "Poprzednia Strona", NamedTextColor.YELLOW));
        if (startIndex + 35 < questy.size()) gui.setItem(53, stworzPrzycisk(Material.ARROW, "Następna Strona", NamedTextColor.YELLOW));

        player.openInventory(gui);
    }

    private void wypelnijTlo(Inventory gui) {
        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        for (int i = 0; i < 54; i++) gui.setItem(i, tlo);
    }

    private ItemStack stworzIkoneKategorii(Material material, String nazwa, String opis) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, NamedTextColor.GOLD, TextDecoration.BOLD));
        meta.lore(List.of(Component.text(opis, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
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

    private ItemStack stworzIkoneQuesta(Quest q, boolean ukonczone) {
        ItemStack item = new ItemStack(ukonczone ? Material.LIME_DYE : Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text(q.tytul, ukonczone ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD));

        meta.lore(List.of(
                Component.empty(),
                Component.text("Wymaga: " + q.wymaganaIlosc + "x " + q.wymaganyMaterial.name(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                ukonczone
                        ? Component.text("Nagroda: " + q.nazwaNagrody, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                        : Component.text("Nagroda: ???", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                ukonczone
                        ? Component.text("✔ UKOŃCZONE", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
                        : Component.text("❌ KLIKNIJ, ABY ZDAĆ", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (!title.equals("Kategorie Zadań") && !title.startsWith("Strona ")) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        if (title.equals("Kategorie Zadań")) {
            if (slot == SLOT_POWROT) {
                player.closeInventory();
                // Sprawdzamy czy gracz wszedł z menu, jeśli tak - cofamy go tam
                if (otwartoZMenu.getOrDefault(player.getUniqueId(), false)) {
                    player.performCommand("menu");
                }
            }
            else if (slot == SLOT_CENTRUM_GWIAZDY) {
                // "Główne Zadania" (środek gwiazdy) - osobny, akcyjny system w GlowneZadaniaManager, nie w questyKategorii.
                glowneZadania.otworzMenuGlownychZadan(player, 0, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
            }
            else if (event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.GRAY_STAINED_GLASS_PANE) {
                String kategoria = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getCurrentItem().getItemMeta().displayName());
                otworzKategorie(player, kategoria, 0);
            }
        } else if (title.startsWith("Strona ")) {
            String[] parts = title.split("\\|");
            if (parts.length < 2) return;

            int strona = Integer.parseInt(parts[0].replace("Strona ", "").trim()) - 1;
            String kategoria = parts[1].trim();

            if (slot == 49) {
                // Powrót do głównych kategorii, przekazując pamięć o zMenu
                otworzMenuQuestow(player, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
            }
            else if (slot == 53 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona + 1);
            else if (slot == 45 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona - 1);
            else {
                for (int i = 0; i < slotySrodkowe.length; i++) {
                    if (slot == slotySrodkowe[i]) {
                        List<Quest> questy = questyKategorii.get(kategoria);
                        int questIndex = (strona * 35) + i;
                        if (questIndex < questy.size()) {
                            zrealizujQuest(player, kategoria, questy.get(questIndex), strona);
                        }
                        break;
                    }
                }
            }
        }
    }

    private void zrealizujQuest(Player player, String kategoria, Quest q, int strona) {
        Set<Integer> postepy = postepyGraczy.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(kategoria, k -> new HashSet<>());

        if (postepy.contains(q.id)) {
            player.sendMessage(Component.text("Zrobiłeś już to zadanie!", NamedTextColor.RED));
            return;
        }

        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(Component.text("Twój ekwipunek jest pełny! Zrób miejsce, aby odebrać nagrodę.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (player.getInventory().containsAtLeast(new ItemStack(q.wymaganyMaterial), q.wymaganaIlosc)) {
            player.getInventory().removeItem(new ItemStack(q.wymaganyMaterial, q.wymaganaIlosc));
            postepy.add(q.id);

            player.getInventory().addItem(q.nagroda);

            player.sendMessage(Component.text("Ukończyłeś zadanie: ", NamedTextColor.GREEN)
                    .append(Component.text(q.tytul, NamedTextColor.GOLD, TextDecoration.BOLD))
                    .append(Component.text("! Otrzymałeś: ", NamedTextColor.GREEN))
                    .append(Component.text(q.nazwaNagrody, NamedTextColor.AQUA)));

            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            otworzKategorie(player, kategoria, strona);
        } else {
            player.sendMessage(Component.text("Nie masz wymaganych przedmiotów!", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        }
    }

    private record Quest(int id, String tytul, Material wymaganyMaterial, int wymaganaIlosc, ItemStack nagroda, String nazwaNagrody) {}
}