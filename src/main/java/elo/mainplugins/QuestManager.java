package elo.mainplugins;

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

    // Siatka 4x7 = 28 slotów. Górny rząd (0-8) jest pusty dla idealnej symetrii.
    private final int[] slotySrodkowe = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    public QuestManager() {
        zaladujQuesty();
    }

    private void zaladujQuesty() {
        // GÓRNICTWO - 40 questów (pokazuje, jak działa przejście na 2 stronę przy 28 questach na stronę)
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

        // Puste listy dla kategorii narzędziowych
        questyKategorii.put("Mistrz Kilofa", new ArrayList<>());
        questyKategorii.put("Mistrz Siekiery", new ArrayList<>());
        questyKategorii.put("Mistrz Miecza", new ArrayList<>());
        questyKategorii.put("Mistrz Motyki", new ArrayList<>());
        questyKategorii.put("Mistrz Łopaty", new ArrayList<>());
    }

    public void otworzMenuQuestow(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Kategorie Zadań", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);

        gui.setItem(10, stworzIkoneKategorii(Material.IRON_PICKAXE, "Górnictwo", "Zadania w kopalni"));
        gui.setItem(11, stworzIkoneKategorii(Material.WHEAT, "Hodowla", "Zadania rolnicze"));
        gui.setItem(12, stworzIkoneKategorii(Material.BOW, "Łowca", "Zadania z potworami"));
        gui.setItem(13, stworzIkoneKategorii(Material.OAK_LOG, "Drwal", "Zadania z drewnem"));
        gui.setItem(14, stworzIkoneKategorii(Material.FISHING_ROD, "Rybak", "Zadania wędkarskie"));
        gui.setItem(15, stworzIkoneKategorii(Material.BREWING_STAND, "Alchemik", "Warzenie mikstur"));
        gui.setItem(16, stworzIkoneKategorii(Material.ANVIL, "Kowal", "Tworzenie narzędzi"));

        gui.setItem(19, stworzIkoneKategorii(Material.COOKED_BEEF, "Kucharz", "Zadania kulinarne"));
        gui.setItem(20, stworzIkoneKategorii(Material.BRICKS, "Budowniczy", "Budowa wyspy"));
        gui.setItem(21, stworzIkoneKategorii(Material.ENCHANTING_TABLE, "Mag", "Zaklęcia"));
        gui.setItem(22, stworzIkoneKategorii(Material.COMPASS, "Odkrywca", "Eksploracja mapy"));
        gui.setItem(23, stworzIkoneKategorii(Material.PORKCHOP, "Rzeźnik", "Zdobywanie mięsa"));
        gui.setItem(24, stworzIkoneKategorii(Material.OAK_SAPLING, "Ogrodnik", "Sadzenie drzew"));
        gui.setItem(25, stworzIkoneKategorii(Material.DIAMOND, "Jubiler", "Cenne kruszce"));

        gui.setItem(28, stworzIkoneKategorii(Material.GOLD_NUGGET, "Złodziej", "Kradzież (Zadania)"));
        gui.setItem(29, stworzIkoneKategorii(Material.DIAMOND_SWORD, "Wojownik", "Walka PvP/PvE"));
        gui.setItem(30, stworzIkoneKategorii(Material.EMERALD, "Handlarz", "Wymiana handlowa"));
        gui.setItem(31, stworzIkoneKategorii(Material.REDSTONE, "Inżynier", "Mechanizmy"));
        gui.setItem(32, stworzIkoneKategorii(Material.ZOMBIE_HEAD, "Zabójca", "Eliminacje"));
        gui.setItem(33, stworzIkoneKategorii(Material.BONE_MEAL, "Zbieracz", "Zbieranie surowców"));
        gui.setItem(34, stworzIkoneKategorii(Material.NETHER_STAR, "Mistrz", "Zadania elitarne"));

        // KATEGORIE NARZĘDZIOWE
        gui.setItem(38, stworzIkoneKategorii(Material.DIAMOND_PICKAXE, "Mistrz Kilofa", "Zadania dla kilofa"));
        gui.setItem(39, stworzIkoneKategorii(Material.DIAMOND_AXE, "Mistrz Siekiery", "Zadania dla siekiery"));
        gui.setItem(40, stworzIkoneKategorii(Material.DIAMOND_SWORD, "Mistrz Miecza", "Zadania dla miecza"));
        gui.setItem(41, stworzIkoneKategorii(Material.DIAMOND_HOE, "Mistrz Motyki", "Zadania dla motyki"));
        gui.setItem(42, stworzIkoneKategorii(Material.DIAMOND_SHOVEL, "Mistrz Łopaty", "Zadania dla łopaty"));

        // Przycisk wyjścia na środku dołu
        gui.setItem(49, stworzPrzycisk(Material.BARRIER, "Zamknij Menu", NamedTextColor.RED));

        player.openInventory(gui);
    }

    public void otworzKategorie(Player player, String nazwaKategorii, int strona) {
        List<Quest> questy = questyKategorii.getOrDefault(nazwaKategorii, new ArrayList<>());

        String tytulMenu = "Strona " + (strona + 1) + " | " + nazwaKategorii;
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(tytulMenu, NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(gui);

        Set<Integer> postepy = postepyGraczy.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(nazwaKategorii, k -> new HashSet<>());

        int startIndex = strona * 28;
        for (int i = 0; i < 28; i++) {
            if (startIndex + i < questy.size()) {
                Quest q = questy.get(startIndex + i);
                boolean ukonczony = postepy.contains(q.id);
                gui.setItem(slotySrodkowe[i], stworzIkoneQuesta(q, ukonczony));
            }
        }

        gui.setItem(49, stworzPrzycisk(Material.DARK_OAK_DOOR, "Powrót do Kategorii", NamedTextColor.GOLD));
        if (strona > 0) gui.setItem(45, stworzPrzycisk(Material.ARROW, "Poprzednia Strona", NamedTextColor.YELLOW));
        if (startIndex + 28 < questy.size()) gui.setItem(53, stworzPrzycisk(Material.ARROW, "Następna Strona", NamedTextColor.YELLOW));

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
            if (slot == 49) player.closeInventory();
            else if (event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.GRAY_STAINED_GLASS_PANE) {
                String kategoria = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.getCurrentItem().getItemMeta().displayName());
                otworzKategorie(player, kategoria, 0);
            }
        } else if (title.startsWith("Strona ")) {
            String[] parts = title.split("\\|");
            if (parts.length < 2) return;

            int strona = Integer.parseInt(parts[0].replace("Strona ", "").trim()) - 1;
            String kategoria = parts[1].trim();

            if (slot == 49) otworzMenuQuestow(player);
            else if (slot == 53 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona + 1);
            else if (slot == 45 && event.getCurrentItem() != null) otworzKategorie(player, kategoria, strona - 1);
            else {
                for (int i = 0; i < slotySrodkowe.length; i++) {
                    if (slot == slotySrodkowe[i]) {
                        List<Quest> questy = questyKategorii.get(kategoria);
                        int questIndex = (strona * 28) + i;
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