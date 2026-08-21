package elo.mainplugins.quests;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.MarketService;
import elo.mainplugins.core.api.QuestService;
import elo.mainplugins.core.api.ToolsService;
import elo.mainplugins.core.api.TytulService;
import elo.mainplugins.quests.model.CategoryDefinition;
import elo.mainplugins.quests.model.MaterialRequirement;
import elo.mainplugins.quests.model.QuestContent;
import elo.mainplugins.quests.model.QuestDefinition;
import elo.mainplugins.quests.model.Requirement;
import elo.mainplugins.quests.model.RewardEntry;
import elo.mainplugins.quests.model.SlotEntry;
import elo.mainplugins.quests.model.SlotRole;
import elo.mainplugins.quests.model.ToolKind;
import elo.mainplugins.quests.model.UnlockCondition;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
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
 * /quest w całości - CAŁA treść (kategorie, ich layout GUI i questy) żyje w
 * quests-content.yml (patrz {@link QuestContentLoader}), przeładowywana na żywo komendą
 * /@reloadquesty. Ta klasa to generyczny silnik: renderuje GUI z {@link SlotEntry} zamiast
 * ręcznie rysowanych tablic slotów, sprawdza wymogi i wręcza nagrody przez pattern-matching
 * switch po sealed {@link Requirement}/{@link RewardEntry} zamiast osobnych metod per-typ i
 * hardkodowanych gałęzi po ID questu - dodanie/usunięcie/przenumerowanie questu w configu
 * (albo w apce desktopowej edytującej ten config) nie wymaga już zmian w Javie.
 *
 * Jedyny wyjątek pozostały w kodzie: quest #1 (pierwszy quest kategorii z main-path: true)
 * ma specjalny moment powitania (zamknięcie ekwipunku, duży tytuł, fanfara) - to czysta
 * prezentacja przy STARCIE danej kategorii (pozycja na liście, nie sztywne ID), nie
 * hardkodowana nagroda, więc świadomie zostaje w Javie zamiast w configu.
 */
public class QuestManager implements Listener, TytulService, QuestService {

    private enum StanQuestu { ZABLOKOWANY, DOSTEPNY, UKONCZONY }
    private enum StanKategorii { DOSTEPNA, ZABLOKOWANA, W_BUDOWIE }

    /** Gramatyka narzędzia (mianownik/dopełniacz/zaimek dzierżawczy) do tekstów lore/odmów - patrz opisWymoguTekst/powodOdmowy. */
    private record GramatykaNarzedzia(String mianownik, String dopelniacz, String zaimek) {}

    private static final Map<ToolKind, GramatykaNarzedzia> GRAMATYKA_NARZEDZI = Map.of(
            ToolKind.PICKAXE, new GramatykaNarzedzia("kilof", "kilofa", "Twój"),
            ToolKind.AXE, new GramatykaNarzedzia("siekiera", "siekiery", "Twoja"),
            ToolKind.HOE, new GramatykaNarzedzia("motyka", "motyki", "Twoja"),
            ToolKind.SWORD, new GramatykaNarzedzia("miecz", "miecza", "Twój"),
            ToolKind.SHOVEL, new GramatykaNarzedzia("łopata", "łopaty", "Twoja")
    );

    /** Stare klucze kategorii w quests.yml (sprzed wprowadzenia stabilnych id) -> nowe id - patrz wczytajPostep(). */
    private static final Map<String, String> LEGACY_KATEGORIA_ID = Map.of(
            "Główna Ścieżka", "GLOWNA_SCIEZKA",
            "Górnictwo", "GORNICTWO",
            "Hodowla", "HODOWLA",
            "Łowca", "LOWCA",
            "Rybak", "RYBAK",
            "Questy Specjalne", "QUESTY_SPECJALNE"
    );

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Material DOMYSLNY_WYPELNIACZ = Material.BLACK_STAINED_GLASS_PANE;

    /**
     * Polskie nazwy materiałów do lore wymogów questa - bez tego "Wymaga: 8x OAK_LOG"
     * pokazywałoby surową nazwę enuma Bukkita zamiast "8x Kłoda Dębu".
     */
    private static final Map<Material, String> NAZWY_MATERIALOW = Map.ofEntries(
            Map.entry(Material.COBBLESTONE, "Brukowiec"),
            Map.entry(Material.OAK_LOG, "Kłoda Dębu"),
            Map.entry(Material.BIRCH_SAPLING, "Sadzonka Brzozy"),
            Map.entry(Material.COAL, "Węgiel"),
            Map.entry(Material.CHARCOAL, "Węgiel Drzewny"),
            Map.entry(Material.STONE_HOE, "Kamienna Motyka"),
            Map.entry(Material.MELON_SLICE, "Plasterek Arbuza"),
            Map.entry(Material.WHEAT, "Pszenica"),
            Map.entry(Material.CARROT, "Marchewka"),
            Map.entry(Material.COOKIE, "Ciastko"),
            Map.entry(Material.ROTTEN_FLESH, "Zgniłe Mięso"),
            Map.entry(Material.RAW_IRON, "Surowe Żelazo"),
            Map.entry(Material.SAND, "Piasek"),
            Map.entry(Material.GRAVEL, "Żwir"),
            Map.entry(Material.IRON_AXE, "Żelazna Siekiera"),
            Map.entry(Material.KELP, "Wodorosty"),
            Map.entry(Material.EMERALD, "Szmaragd"),
            Map.entry(Material.REDSTONE, "Redstone"),
            Map.entry(Material.LAPIS_LAZULI, "Lapis Lazuli"),
            Map.entry(Material.DIAMOND, "Diament"),
            Map.entry(Material.DIAMOND_PICKAXE, "Diamentowy Kilof"),
            Map.entry(Material.OBSIDIAN, "Obsydian"),
            Map.entry(Material.NETHERRACK, "Netherrack"),
            Map.entry(Material.BLAZE_ROD, "Różdżka Blaze'a"),
            Map.entry(Material.SOUL_SAND, "Duszowy Piasek"),
            Map.entry(Material.QUARTZ, "Kwarc Netherowy"),
            Map.entry(Material.GHAST_TEAR, "Łza Ghasta"),
            Map.entry(Material.NETHERITE_SCRAP, "Złom Netherytu"),
            Map.entry(Material.NETHERITE_INGOT, "Sztabka Netherytu"),
            Map.entry(Material.DIAMOND_SWORD, "Diamentowy Miecz"),
            Map.entry(Material.NETHERITE_PICKAXE, "Netherytowy Kilof"),
            Map.entry(Material.ENDER_PEARL, "Perła Endermana"),
            Map.entry(Material.PURPUR_BLOCK, "Blok Purpuru"),
            Map.entry(Material.CHORUS_FRUIT, "Owoc Chorusu"),
            Map.entry(Material.SHULKER_SHELL, "Skorupa Shulkera"),
            Map.entry(Material.PHANTOM_MEMBRANE, "Błona Fantoma"),
            Map.entry(Material.GOLDEN_CARROT, "Złota Marchewka"),
            Map.entry(Material.DRAGON_BREATH, "Oddech Smoka"),
            Map.entry(Material.NETHER_STAR, "Gwiazda Netheru"),
            Map.entry(Material.RAW_COPPER, "Surowa Miedź"),
            Map.entry(Material.RAW_GOLD, "Surowe Złoto"),
            Map.entry(Material.AMETHYST_SHARD, "Odłamek Ametystu"),
            Map.entry(Material.POTATO, "Ziemniak"),
            Map.entry(Material.BEETROOT, "Burak"),
            Map.entry(Material.PUMPKIN, "Dynia"),
            Map.entry(Material.BONE, "Kość"),
            Map.entry(Material.STRING, "Sznurek"),
            Map.entry(Material.GUNPOWDER, "Proch Strzelniczy"),
            Map.entry(Material.SPIDER_EYE, "Oko Pająka"),
            Map.entry(Material.WITHER_SKELETON_SKULL, "Czaszka Witherowego Szkieletu"),
            Map.entry(Material.HEART_OF_THE_SEA, "Serce Morza"),
            Map.entry(Material.COD, "Dorsz"),
            Map.entry(Material.SALMON, "Łosoś"),
            Map.entry(Material.TROPICAL_FISH, "Tropikalna Ryba")
    );

    private final Plugin plugin;
    private final File plikPostepow;
    private final FileConfiguration configPostepow;

    private final Map<UUID, Map<String, Set<Integer>>> postepyGraczy = new HashMap<>();
    private final Map<UUID, Set<String>> tytulyGraczy = new HashMap<>();
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    private QuestContent content;

    public QuestManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikPostepow = new File(plugin.getDataFolder(), "quests.yml");
        if (!plikPostepow.exists()) {
            plikPostepow.getParentFile().mkdirs();
            try { plikPostepow.createNewFile(); } catch (IOException ignored) {}
        }
        this.configPostepow = YamlConfiguration.loadConfiguration(plikPostepow);
        this.content = QuestContentLoader.load(plugin);
        wczytajPostep();
    }

    /** Wywoływane przez /@reloadquesty - podmienia tylko treść, postęp graczy zostaje nietknięty. */
    public void przeladujTresc() {
        this.content = QuestContentLoader.load(plugin);
    }

    private CategoryDefinition kategoriaGlownaSciezki() {
        for (CategoryDefinition c : content.categories().values()) {
            if (c.mainPath()) return c;
        }
        return null;
    }

    private CategoryDefinition znajdzKategorieWgNazwy(String displayName) {
        for (CategoryDefinition c : content.categories().values()) {
            if (c.displayName().equals(displayName)) return c;
        }
        return null;
    }

    // ---- Persystencja postępu (per gracz, per kategoria) + tytuły ----

    private void wczytajPostep() {
        ConfigurationSection graczeSekcja = configPostepow.getConfigurationSection("gracze");
        if (graczeSekcja == null) return;

        for (String uuidStr : graczeSekcja.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(uuidStr); } catch (IllegalArgumentException e) { continue; }

            ConfigurationSection kategorieSekcja = graczeSekcja.getConfigurationSection(uuidStr);
            if (kategorieSekcja == null) continue;

            Map<String, Set<Integer>> mapaKategorii = new HashMap<>();
            Set<String> tytuly = new HashSet<>(kategorieSekcja.getStringList("tytuly"));
            for (String klucz : kategorieSekcja.getKeys(false)) {
                if (klucz.equals("tytuly")) continue;
                // Stare zapisy kluczowały kategorię jej nazwą wyświetlaną - zmapuj na stabilne id,
                // żeby zmiana nazwy kategorii w edytorze nie gubiła postępu (patrz LEGACY_KATEGORIA_ID).
                String docelowyId = LEGACY_KATEGORIA_ID.getOrDefault(klucz, klucz);
                mapaKategorii.computeIfAbsent(docelowyId, k -> new HashSet<>()).addAll(kategorieSekcja.getIntegerList(klucz));
            }

            // Bezstratna, jednorazowa migracja dawnej logiki TytulService (tytuł wyliczany w locie
            // z ukończenia questu 10 Głównej Ścieżki) na jawny, trwały zbiór tytułów - stare zapisy
            // sprzed wprowadzenia RewardEntry.TitleReward nie miały jeszcze wpisu "tytuly" wcale.
            Set<Integer> glowna = mapaKategorii.get("GLOWNA_SCIEZKA");
            if (glowna != null && glowna.contains(10)) tytuly.add("POCZATKUJACY");

            postepyGraczy.put(uuid, mapaKategorii);
            tytulyGraczy.put(uuid, tytuly);
        }
    }

    private void zapiszPostep() {
        configPostepow.set("gracze", null); // czyścimy stare wpisy, żeby nie zostawały śmieci po zmianach nazw/id kategorii
        Set<UUID> wszyscy = new HashSet<>(postepyGraczy.keySet());
        wszyscy.addAll(tytulyGraczy.keySet());

        for (UUID uuid : wszyscy) {
            for (Map.Entry<String, Set<Integer>> kategoria : postepyGraczy.getOrDefault(uuid, Map.of()).entrySet()) {
                configPostepow.set("gracze." + uuid + "." + kategoria.getKey(), new ArrayList<>(kategoria.getValue()));
            }
            Set<String> tytuly = tytulyGraczy.getOrDefault(uuid, Set.of());
            if (!tytuly.isEmpty()) {
                configPostepow.set("gracze." + uuid + ".tytuly", new ArrayList<>(tytuly));
            }
        }
        try {
            configPostepow.save(plikPostepow);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać quests.yml: " + e.getMessage());
        }
    }

    private Set<Integer> postepyDlaKategorii(Player player, String kategoriaId) {
        return postepyGraczy.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>())
                .computeIfAbsent(kategoriaId, k -> new HashSet<>());
    }

    private Set<String> tytulyDlaGracza(Player player) {
        return tytulyGraczy.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
    }

    /**
     * Stan pojedynczego zadania. Dla niesekwencyjnych kategorii to tylko DOSTEPNY/UKONCZONY -
     * ZABLOKOWANY istnieje wyłącznie przy sequential: true, gdzie zadanie N+1 czeka, aż
     * gracz ukończy zadanie N (index-1 na tej samej liście).
     */
    private StanQuestu ustalStan(CategoryDefinition kategoria, List<QuestDefinition> questy, int indexGlobalny, Set<Integer> postepy) {
        QuestDefinition q = questy.get(indexGlobalny);
        if (postepy.contains(q.id())) return StanQuestu.UKONCZONY;
        if (!kategoria.sequential() || indexGlobalny == 0) return StanQuestu.DOSTEPNY;

        QuestDefinition poprzedni = questy.get(indexGlobalny - 1);
        return postepy.contains(poprzedni.id()) ? StanQuestu.DOSTEPNY : StanQuestu.ZABLOKOWANY;
    }

    // ---- Odblokowanie kategorii (generyczne - unlock może wskazywać na DOWOLNĄ kategorię, nie tylko main-path) ----

    private boolean kategoriaOdblokowana(Player player, CategoryDefinition kategoria) {
        UnlockCondition u = kategoria.unlock();
        if (u == null) return true;
        return postepyDlaKategorii(player, u.categoryId()).contains(u.questId());
    }

    private String tekstWymoguOdblokowania(CategoryDefinition kategoria) {
        UnlockCondition u = kategoria.unlock();
        if (u == null) return "";
        CategoryDefinition zrodlo = content.categories().get(u.categoryId());
        String nazwaZrodla = zrodlo != null ? zrodlo.displayName() : u.categoryId();
        String tytulQuestu = zrodlo != null
                ? zrodlo.quests().stream().filter(q -> q.id() == u.questId()).findFirst().map(QuestDefinition::title).orElse("?")
                : "?";
        return "Ukończ \"" + tytulQuestu + "\" (#" + u.questId() + ") w " + nazwaZrodla + ".";
    }

    /** Czy gracz ma jeszcze niedokończone zadanie w danej kategorii, licząc od zera (wywoływane tylko dla main-path - patrz komentarz klasy). */
    private boolean maDostepnyQuest(Player player, CategoryDefinition kategoria) {
        if (kategoria.quests().isEmpty()) return false;
        int ukonczone = postepyDlaKategorii(player, kategoria.id()).size();
        return ukonczone < kategoria.quests().size();
    }

    // ---- GUI: menu główne (kategorie) ----

    /** Mapuje slot -> id kategorii dla main-menu.layout, w kolejności category-order (i-ty CATEGORY_SLOT -> categoryOrder[i]). */
    private Map<Integer, String> slotyMenuGlownego() {
        Map<Integer, String> mapa = new HashMap<>();
        int idx = 0;
        for (SlotEntry e : content.mainMenuLayout()) {
            if (e.role() == SlotRole.CATEGORY_SLOT) {
                if (idx < content.categoryOrder().size()) {
                    mapa.put(e.slot(), content.categoryOrder().get(idx));
                }
                idx++;
            }
        }
        return mapa;
    }

    public void otworzMenuQuestow(Player player, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Kategorie Zadań", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijDomyslnie(gui);

        int idx = 0;
        for (SlotEntry e : content.mainMenuLayout()) {
            switch (e.role()) {
                case CATEGORY_SLOT -> {
                    if (idx < content.categoryOrder().size()) {
                        CategoryDefinition kat = content.categories().get(content.categoryOrder().get(idx));
                        if (kat != null) gui.setItem(e.slot(), stworzIkoneKategorii(player, kat));
                    }
                    idx++;
                }
                case FILLER -> gui.setItem(e.slot(), panel(e.material() != null ? e.material() : DOMYSLNY_WYPELNIACZ));
                default -> { /* NAV_* i QUEST_SLOT nie mają zastosowania w menu głównym - ignorowane */ }
            }
        }

        player.openInventory(gui);
    }

    // ---- GUI: strona kategorii (questy) ----

    private int rozmiarStrony(CategoryDefinition kategoria) {
        int n = 0;
        for (SlotEntry e : kategoria.pageLayout()) if (e.role() == SlotRole.QUEST_SLOT) n++;
        return n;
    }

    public void otworzKategorie(Player player, String kategoriaId, int strona) {
        CategoryDefinition kategoria = content.categories().get(kategoriaId);
        if (kategoria == null) return;

        List<QuestDefinition> questy = kategoria.quests();
        int rozmiarStrony = rozmiarStrony(kategoria);
        int startIndex = strona * Math.max(rozmiarStrony, 1);

        String tytulMenu = "Strona " + (strona + 1) + " | " + kategoria.displayName();
        Inventory gui = Bukkit.createInventory(null, 54, Component.text(tytulMenu, NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijDomyslnie(gui);

        Set<Integer> postepy = postepyDlaKategorii(player, kategoriaId);

        int offsetQuestu = 0;
        for (SlotEntry e : kategoria.pageLayout()) {
            switch (e.role()) {
                case QUEST_SLOT -> {
                    int globalIndex = startIndex + offsetQuestu;
                    offsetQuestu++;
                    if (globalIndex < questy.size()) {
                        StanQuestu stan = ustalStan(kategoria, questy, globalIndex, postepy);
                        gui.setItem(e.slot(), stworzIkoneQuesta(questy.get(globalIndex), stan));
                    }
                }
                case NAV_BACK -> gui.setItem(e.slot(), stworzPrzycisk(Material.DARK_OAK_DOOR, "Powrót do Kategorii", NamedTextColor.GOLD));
                case NAV_PREV -> {
                    if (strona > 0) gui.setItem(e.slot(), stworzPrzycisk(Material.ARROW, "Poprzednia Strona", NamedTextColor.YELLOW));
                }
                case NAV_NEXT -> {
                    if (startIndex + rozmiarStrony < questy.size()) gui.setItem(e.slot(), stworzPrzycisk(Material.ARROW, "Następna Strona", NamedTextColor.YELLOW));
                }
                case FILLER -> gui.setItem(e.slot(), panel(e.material() != null ? e.material() : DOMYSLNY_WYPELNIACZ));
                default -> { /* CATEGORY_SLOT nie ma zastosowania tutaj */ }
            }
        }

        player.openInventory(gui);
    }

    private void wypelnijDomyslnie(Inventory gui) {
        ItemStack wypelniacz = panel(DOMYSLNY_WYPELNIACZ);
        for (int i = 0; i < 54; i++) gui.setItem(i, wypelniacz);
    }

    private ItemStack panel(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack stworzIkoneKategorii(Player player, CategoryDefinition kategoria) {
        StanKategorii stan;
        String dodatkowyTekst = null;
        if (kategoria.quests().isEmpty()) {
            stan = StanKategorii.W_BUDOWIE;
        } else if (!kategoriaOdblokowana(player, kategoria)) {
            stan = StanKategorii.ZABLOKOWANA;
            dodatkowyTekst = tekstWymoguOdblokowania(kategoria);
        } else {
            stan = StanKategorii.DOSTEPNA;
        }

        Material materialIkony = switch (stan) {
            case DOSTEPNA -> kategoria.icon();
            case ZABLOKOWANA -> Material.GRAY_DYE;
            case W_BUDOWIE -> Material.BARRIER;
        };
        NamedTextColor kolorNazwy = stan == StanKategorii.DOSTEPNA ? NamedTextColor.GOLD : NamedTextColor.DARK_GRAY;

        ItemStack item = new ItemStack(materialIkony);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(kategoria.displayName(), kolorNazwy, TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(kategoria.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (stan == StanKategorii.ZABLOKOWANA) {
            lore.add(Component.empty());
            lore.add(Component.text("🔒 Zablokowane", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(dodatkowyTekst, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else if (stan == StanKategorii.W_BUDOWIE) {
            lore.add(Component.empty());
            lore.add(Component.text("🚧 W budowie", NamedTextColor.YELLOW, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Wróć tu później - jeszcze nie ma tu zadań.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else if (kategoria.mainPath() && maDostepnyQuest(player, kategoria)) {
            lore.add(Component.text("🔔 Nowy quest dostępny!", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);

        // Delikatny blask na ikonie ścieżki startowej - czysta prezentacja (patrz komentarz klasy), nie wpływa na żaden mechanizm.
        if (kategoria.mainPath() && stan == StanKategorii.DOSTEPNA) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

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

    private static String nazwaMaterialu(Material material) {
        return NAZWY_MATERIALOW.getOrDefault(material, material.name());
    }

    /** "16x Kłoda Dębu, 16x Sadzonka Brzozy" - łączy kilka wymaganych materiałów w jeden czytelny tekst do lore. */
    private String opisWymogow(List<MaterialRequirement> wymogi) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wymogi.size(); i++) {
            if (i > 0) sb.append(", ");
            MaterialRequirement w = wymogi.get(i);
            sb.append(w.amount()).append("x ").append(w.displayName() != null ? w.displayName() : nazwaMaterialu(w.material()));
        }
        return sb.toString();
    }

    private String opisWymoguTekst(Requirement r) {
        return switch (r) {
            case Requirement.FreeRequirement fr -> "Wymaga: nic - kliknij, by odebrać!";
            case Requirement.MoneyRequirement mr -> "Wymaga: " + (int) mr.amount() + " monet";
            case Requirement.MarketOfferRequirement mo -> "Wymaga: aktywnej oferty na Targu (/targ wystaw)";
            case Requirement.ItemRequirement ir -> "Wymaga: " + opisWymogow(ir.materials());
            case Requirement.ToolPossessRequirement tp ->
                    "Wymaga: posiadania " + opisWymogow(List.of(MaterialRequirement.of(tp.material(), 1))) + " (zostaje przy Tobie)";
            case Requirement.ToolLevelRequirement tl ->
                    "Wymaga: " + GRAMATYKA_NARZEDZI.get(tl.tool()).dopelniacz() + " na poziomie " + tl.level();
        };
    }

    private String etykietaNagrody(QuestDefinition q) {
        if (q.rewardLabel() != null && !q.rewardLabel().isBlank()) return q.rewardLabel();
        List<String> czesci = new ArrayList<>();
        for (RewardEntry r : q.rewards()) {
            if (r.silent()) continue;
            czesci.add(switch (r) {
                case RewardEntry.ItemReward ir -> ir.amount() + "x " + nazwaMaterialu(ir.material());
                case RewardEntry.CustomItemReward cr -> cr.amount() + "x " + cr.id();
                case RewardEntry.MoneyReward mr -> (int) mr.amount() + " Monet";
                case RewardEntry.CrateReward cr -> "Skrzynka T" + cr.tier();
                case RewardEntry.ToolReward tr -> "Narzędzie: " + GRAMATYKA_NARZEDZI.get(tr.tool()).mianownik();
                case RewardEntry.TitleReward tr -> "Tytuł";
            });
        }
        return czesci.isEmpty() ? "???" : String.join(", ", czesci);
    }

    /** Czerwony barwnik = dostępne, zielony (lime) = ukończone, szary = zablokowane (tylko kategorie sequential). */
    private ItemStack stworzIkoneQuesta(QuestDefinition q, StanQuestu stan) {
        Material material = switch (stan) {
            case UKONCZONY -> Material.LIME_DYE;
            case ZABLOKOWANY -> Material.GRAY_DYE;
            case DOSTEPNY -> Material.RED_DYE;
        };
        NamedTextColor kolorTytulu = switch (stan) {
            case UKONCZONY -> NamedTextColor.GREEN;
            case ZABLOKOWANY -> NamedTextColor.DARK_GRAY;
            case DOSTEPNY -> NamedTextColor.RED;
        };

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(stan == StanQuestu.ZABLOKOWANY ? "??? (Zablokowane)" : q.title(), kolorTytulu, TextDecoration.BOLD));

        List<Component> lore = new ArrayList<>();
        if (stan == StanQuestu.ZABLOKOWANY) {
            lore.add(Component.text("Ukończ poprzednie zadanie ścieżki, aby odblokować.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.empty());
            for (String linia : q.description()) {
                lore.add(Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            lore.add(Component.text(opisWymoguTekst(q.requirement()), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            lore.add(stan == StanQuestu.UKONCZONY
                    ? Component.text("Nagroda: " + etykietaNagrody(q), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                    : Component.text("Nagroda: ???", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(stan == StanQuestu.UKONCZONY
                    ? Component.text("✔ UKOŃCZONE", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
                    : Component.text("❌ KLIKNIJ, ABY ZDAĆ", NamedTextColor.RED, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ---- Kliknięcia ----

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        boolean jestKategoriami = title.equals("Kategorie Zadań");
        if (!jestKategoriami && !title.startsWith("Strona ")) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();

        if (jestKategoriami) {
            String kategoriaId = slotyMenuGlownego().get(slot);
            if (kategoriaId == null) return;
            CategoryDefinition kategoria = content.categories().get(kategoriaId);
            if (kategoria == null) return;

            if (kategoria.mainPath()) {
                otworzKategorie(player, kategoriaId, 0);
                return;
            }
            if (kategoria.quests().isEmpty()) {
                player.sendMessage(Component.text("Ta kategoria jest jeszcze w budowie - wróć później!", NamedTextColor.YELLOW));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            if (!kategoriaOdblokowana(player, kategoria)) {
                player.sendMessage(Component.text("Ta kategoria jest jeszcze zablokowana!", NamedTextColor.RED));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return;
            }
            otworzKategorie(player, kategoriaId, 0);
            return;
        }

        String[] parts = title.split("\\|");
        if (parts.length < 2) return;
        int strona;
        try {
            strona = Integer.parseInt(parts[0].replace("Strona ", "").trim()) - 1;
        } catch (NumberFormatException e) {
            return;
        }
        CategoryDefinition kategoria = znajdzKategorieWgNazwy(parts[1].trim());
        if (kategoria == null) return;

        SlotEntry klikniety = null;
        for (SlotEntry e : kategoria.pageLayout()) {
            if (e.slot() == slot) { klikniety = e; break; }
        }
        if (klikniety == null) return;

        switch (klikniety.role()) {
            case NAV_BACK -> otworzMenuQuestow(player, otwartoZMenu.getOrDefault(player.getUniqueId(), false));
            case NAV_NEXT -> {
                if (strona * Math.max(rozmiarStrony(kategoria), 1) + rozmiarStrony(kategoria) < kategoria.quests().size()) {
                    otworzKategorie(player, kategoria.id(), strona + 1);
                }
            }
            case NAV_PREV -> {
                if (strona > 0) otworzKategorie(player, kategoria.id(), strona - 1);
            }
            case QUEST_SLOT -> {
                int rozmiarStrony = rozmiarStrony(kategoria);
                int startIndex = strona * Math.max(rozmiarStrony, 1);
                int offset = 0;
                for (SlotEntry e : kategoria.pageLayout()) {
                    if (e.role() != SlotRole.QUEST_SLOT) continue;
                    if (e.slot() == slot) break;
                    offset++;
                }
                int questIndex = startIndex + offset;
                if (questIndex < kategoria.quests().size()) {
                    zrealizujQuest(player, kategoria, questIndex, strona);
                }
            }
            default -> { /* FILLER/CATEGORY_SLOT - nic do zrobienia */ }
        }
    }

    // ---- Sprawdzanie wymogu / zabieranie kosztu ----

    /** Zwykły wymóg (customId null) -> zwykłe containsAtLeast po materiale. Custom (np. gatunek ryby) -> liczy tylko itemy z pasującym PDC tagiem. */
    private boolean posiadaWymaganaIlosc(Player player, MaterialRequirement w) {
        if (w.customId() == null) {
            return player.getInventory().containsAtLeast(new ItemStack(w.material()), w.amount());
        }
        int suma = 0;
        for (ItemStack is : player.getInventory().getContents()) {
            if (is == null || is.getType() != w.material() || !is.hasItemMeta()) continue;
            if (w.customId().equals(is.getItemMeta().getPersistentDataContainer().get(elo.mainplugins.core.util.CustomItemKeys.CUSTOM_ITEM_ID, org.bukkit.persistence.PersistentDataType.STRING))) {
                suma += is.getAmount();
            }
        }
        return suma >= w.amount();
    }

    private void zabierzWymog(Player player, MaterialRequirement w) {
        if (w.customId() == null) {
            player.getInventory().removeItem(new ItemStack(w.material(), w.amount()));
            return;
        }
        int doZabrania = w.amount();
        ItemStack[] zawartosc = player.getInventory().getContents();
        for (int i = 0; i < zawartosc.length && doZabrania > 0; i++) {
            ItemStack is = zawartosc[i];
            if (is == null || is.getType() != w.material() || !is.hasItemMeta()) continue;
            if (!w.customId().equals(is.getItemMeta().getPersistentDataContainer().get(elo.mainplugins.core.util.CustomItemKeys.CUSTOM_ITEM_ID, org.bukkit.persistence.PersistentDataType.STRING))) continue;

            int zabierz = Math.min(is.getAmount(), doZabrania);
            is.setAmount(is.getAmount() - zabierz);
            doZabrania -= zabierz;
            if (is.getAmount() <= 0) zawartosc[i] = null;
        }
        player.getInventory().setContents(zawartosc);
    }

    private boolean spelnionyWymog(Player player, Requirement r) {
        return switch (r) {
            case Requirement.FreeRequirement fr -> true;
            case Requirement.MoneyRequirement mr -> CoreAPI.getEconomyService().maWystarczajaco(player.getUniqueId(), mr.amount());
            case Requirement.MarketOfferRequirement mo -> {
                MarketService market = CoreAPI.getMarketService();
                yield market != null && market.maAktywnaOferte(player.getUniqueId());
            }
            case Requirement.ToolPossessRequirement tp -> posiadaWymaganaIlosc(player, MaterialRequirement.of(tp.material(), 1));
            case Requirement.ToolLevelRequirement tl -> {
                ToolsService tools = CoreAPI.getToolsService();
                if (tools == null) yield false;
                int poziom = switch (tl.tool()) {
                    case PICKAXE -> tools.poziomKilofa(player);
                    case AXE -> tools.poziomSiekiery(player);
                    case SWORD -> tools.poziomMiecza(player);
                    case HOE, SHOVEL -> throw new IllegalStateException("TOOL_LEVEL dla " + tl.tool() + " nie jest obsługiwany - QuestContentLoader powinien to odrzucić przy wczytaniu.");
                };
                yield poziom >= tl.level();
            }
            case Requirement.ItemRequirement ir -> ir.materials().stream().allMatch(w -> posiadaWymaganaIlosc(player, w));
        };
    }

    private void zabierzWymogJesliTrzeba(Player player, Requirement r) {
        switch (r) {
            case Requirement.MoneyRequirement mr -> CoreAPI.getEconomyService().odejmijKase(player.getUniqueId(), mr.amount());
            case Requirement.ItemRequirement ir -> ir.materials().forEach(w -> zabierzWymog(player, w));
            case Requirement.FreeRequirement fr -> { }
            case Requirement.ToolPossessRequirement tp -> { }
            case Requirement.ToolLevelRequirement tl -> { }
            case Requirement.MarketOfferRequirement mo -> { }
        }
    }

    private String powodOdmowy(Requirement r) {
        return switch (r) {
            case Requirement.MoneyRequirement mr -> "Nie masz wystarczająco monet!";
            case Requirement.ToolPossessRequirement tp -> "Nie masz wymaganego narzędzia!";
            case Requirement.ToolLevelRequirement tl -> {
                GramatykaNarzedzia g = GRAMATYKA_NARZEDZI.get(tl.tool());
                yield g.zaimek() + " " + g.mianownik() + " nie jest jeszcze na wymaganym poziomie!";
            }
            case Requirement.MarketOfferRequirement mo -> "Nie masz jeszcze żadnej aktywnej oferty na Targu! Wpisz /targ wystaw <cena>.";
            case Requirement.ItemRequirement ir -> "Nie masz wymaganych przedmiotów!";
            case Requirement.FreeRequirement fr -> "Nie masz wymaganych przedmiotów!";
        };
    }

    // ---- Realizacja questu + nagrody ----

    private void zrealizujQuest(Player player, CategoryDefinition kategoria, int questIndex, int strona) {
        QuestDefinition q = kategoria.quests().get(questIndex);
        Set<Integer> postepy = postepyDlaKategorii(player, kategoria.id());

        StanQuestu stan = ustalStan(kategoria, kategoria.quests(), questIndex, postepy);
        if (stan == StanQuestu.ZABLOKOWANY) {
            player.sendMessage(Component.text("Najpierw ukończ poprzednie zadanie tej ścieżki!", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        if (stan == StanQuestu.UKONCZONY) {
            player.sendMessage(Component.text("Zrobiłeś już to zadanie!", NamedTextColor.RED));
            return;
        }
        if (player.getInventory().firstEmpty() == -1) {
            player.sendMessage(Component.text("Twój ekwipunek jest pełny! Zrób miejsce, aby odebrać nagrodę.", NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        if (!spelnionyWymog(player, q.requirement())) {
            player.sendMessage(Component.text(powodOdmowy(q.requirement()), NamedTextColor.RED));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }

        zabierzWymogJesliTrzeba(player, q.requirement());
        postepy.add(q.id());
        wreczNagrody(player, q);
        zapiszPostep();

        player.sendMessage(Component.text("Ukończyłeś zadanie: ", NamedTextColor.GREEN)
                .append(Component.text(q.title(), NamedTextColor.GOLD, TextDecoration.BOLD))
                .append(Component.text("! Otrzymałeś: ", NamedTextColor.GREEN))
                .append(Component.text(etykietaNagrody(q), NamedTextColor.AQUA)));

        // Powitanie nowego gracza - pierwszy quest kategorii startowej (patrz komentarz klasy), nie sztywne ID.
        if (kategoria.mainPath() && questIndex == 0) {
            player.closeInventory();
            player.showTitle(Title.title(
                    Component.text("Witaj na Wyspie", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("Twoja opowieść zaczyna się teraz.", NamedTextColor.YELLOW)
            ));
            player.playSound(player.getLocation(), "mainplugins:quest_welcome", 1.0f, 1.0f);
            return;
        }

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        otworzKategorie(player, kategoria.id(), strona);
    }

    private void dajLubUpusc(Player player, ItemStack item) {
        var nieZmieszczone = player.getInventory().addItem(item);
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
    }

    private void wreczNagrody(Player player, QuestDefinition q) {
        for (RewardEntry r : q.rewards()) wreczJednaNagrode(player, r);
    }

    private void wreczJednaNagrode(Player player, RewardEntry r) {
        switch (r) {
            case RewardEntry.ItemReward ir -> dajLubUpusc(player, new ItemStack(ir.material(), ir.amount()));

            case RewardEntry.CustomItemReward cr -> {
                CustomItemService svc = CoreAPI.getCustomItemService();
                if (svc == null || !svc.exists(cr.id())) {
                    plugin.getLogger().warning("Nagroda questu: custom item '" + cr.id() + "' niedostępny (CustomItemService null lub brak wpisu w rejestrze) - pomijam.");
                    return;
                }
                ItemStack item = svc.create(cr.id(), cr.amount());
                if (item != null) dajLubUpusc(player, item);
            }

            case RewardEntry.MoneyReward mr -> CoreAPI.getEconomyService().dodajKase(player.getUniqueId(), mr.amount());

            case RewardEntry.CrateReward cr -> {
                CrateService svc = CoreAPI.getCrateService();
                if (svc != null) {
                    dajLubUpusc(player, svc.stworzSkrzynke(cr.tier()));
                    dajLubUpusc(player, svc.stworzKlucz());
                } else {
                    cr.fallback().forEach(fb -> wreczJednaNagrode(player, fb));
                }
            }

            case RewardEntry.ToolReward tr -> {
                ToolsService svc = CoreAPI.getToolsService();
                if (svc == null) {
                    plugin.getLogger().warning("Nagroda questu: ToolsService niedostępny, narzędzie (" + tr.tool() + ") nie zostało wręczone.");
                    return;
                }
                switch (tr.tool()) {
                    case PICKAXE -> svc.dajEwoluujacyKilof(player);
                    case AXE -> svc.dajEwoluujacaSiekiere(player);
                    case HOE -> svc.dajEwoluujacaMotyke(player);
                    case SWORD -> svc.dajEwoluujacyMiecz(player);
                    case SHOVEL -> svc.dajEwoluujacaLopate(player);
                }
            }

            case RewardEntry.TitleReward tr -> tytulyDlaGracza(player).add(tr.titleId());
        }
    }

    /** Przypomnienie na ekranie (bossbar, znika samo po chwili) - nie wychodzi poza ekran jak hologram w świecie. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        CategoryDefinition glowna = kategoriaGlownaSciezki();
        if (glowna == null || !maDostepnyQuest(player, glowna)) return;

        BossBar pasek = BossBar.bossBar(
                Component.text("📜 Masz dostępny quest w " + glowna.displayName() + "! Wpisz /zadania", NamedTextColor.GOLD, TextDecoration.BOLD),
                1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        player.showBossBar(pasek);
        Bukkit.getScheduler().runTaskLater(plugin, () -> player.hideBossBar(pasek), 20L * 6);
    }

    // ---- TytulService: tytuł na czacie (dowolny tytuł zdobyty przez RewardEntry.TitleReward) ----

    /** {@inheritDoc} Jeśli gracz ma kilka tytułów naraz, bierze pierwszy z brzegu - dziś zawsze jest tylko jeden możliwy ("Początkujący"). */
    @Override
    public Component tytulGracza(UUID uuid) {
        Set<String> tytuly = tytulyGraczy.get(uuid);
        if (tytuly == null || tytuly.isEmpty()) return null;
        String id = tytuly.iterator().next();
        String tekst = content.titles().get(id);
        if (tekst == null) return null;
        return LEGACY.deserialize(tekst).decoration(TextDecoration.ITALIC, false);
    }

    // ---- QuestService: postęp Głównej Ścieżki dla innych modułów (np. mainplugins-spawn, /warp kowal) ----

    /** {@inheritDoc} Zero mutacji stanu - czysty odczyt pod bramki innych modułów. Kategoria "główna" to ta z main-path: true. */
    @Override
    public boolean ukonczylGlownaSciezke(UUID uuid, int questId) {
        CategoryDefinition glowna = kategoriaGlownaSciezki();
        if (glowna == null) return false;
        Map<String, Set<Integer>> kategorie = postepyGraczy.get(uuid);
        if (kategorie == null) return false;
        Set<Integer> postepy = kategorie.get(glowna.id());
        return postepy != null && postepy.contains(questId);
    }
}
