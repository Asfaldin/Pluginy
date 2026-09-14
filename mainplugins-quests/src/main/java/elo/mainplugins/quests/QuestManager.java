package elo.mainplugins.quests;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.Reward;
import elo.mainplugins.core.api.RewardService;
import elo.mainplugins.core.api.TytulService;
import elo.mainplugins.core.api.UnlockService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.core.util.MoneyFormat;
import elo.mainplugins.quests.QuestRules.CategoryState;
import elo.mainplugins.quests.QuestRules.QuestState;
import elo.mainplugins.quests.model.CategoryDef;
import elo.mainplugins.quests.model.ItemRef;
import elo.mainplugins.quests.model.QuestConfig;
import elo.mainplugins.quests.model.QuestDef;
import elo.mainplugins.quests.model.QuestSettings;
import elo.mainplugins.quests.model.Requirement;
import elo.mainplugins.quests.model.SlotEntry;
import elo.mainplugins.quests.model.SlotRole;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Menu questów, sprawdzanie wymogów, nagrody (RewardService), postęp graczy i tytuły na czacie. */
final class QuestManager implements Listener, TytulService {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    private final LangService lang;
    private final RewardService rewards;
    private final EconomyService economy;
    private final QuestItems items;
    private final YamlConfiguration progressYaml;
    private final AsyncConfigSaver saver;
    private final Map<UUID, ProgressStore.PlayerProgress> progress;
    /** Tytuł gotowy do odczytu z INNEGO wątku (mainplugins-ranks czyta go w AsyncChatEvent) -
     * ConcurrentHashMap, bo `progress` jest zwykłą HashMap i wolno ją ruszać tylko z głównego wątku. */
    private final Map<UUID, Component> titleCache = new ConcurrentHashMap<>();
    private QuestConfig config;

    QuestManager(JavaPlugin plugin, LangService lang, RewardService rewards, EconomyService economy, CustomItemService customItems) {
        this.plugin = plugin;
        this.lang = lang;
        this.rewards = rewards;
        this.economy = economy;
        this.items = new QuestItems(plugin, customItems);
        File progressFile = new File(plugin.getDataFolder(), "progress.yml");
        this.progressYaml = YamlConfiguration.loadConfiguration(progressFile);
        this.progress = new HashMap<>(ProgressStore.read(progressYaml, plugin.getLogger()::warning));
        this.saver = new AsyncConfigSaver(plugin, progressYaml, progressFile, 30);
        reload();
    }

    // ---- Treść ----

    void reload() {
        File file = new File(plugin.getDataFolder(), "quests.yml");
        moveOldProgressFile(file);
        if (!file.exists()) {
            String resource = QuestRules.defaultContentResource(lang.language());
            try (InputStream in = plugin.getResource(resource)) {
                if (in == null) throw new IOException("missing in the jar");
                file.getParentFile().mkdirs();
                Files.copy(in, file.toPath());
            } catch (IOException e) {
                plugin.getLogger().warning("Could not create quests.yml from " + resource + ": " + e.getMessage());
            }
        }
        config = QuestConfigParser.parse(YamlConfiguration.loadConfiguration(file), rewards::parse,
                m -> {
                    Material mat = Material.matchMaterial(m);
                    return mat != null && mat.isItem();
                }, plugin.getLogger()::warning);
        int quests = config.categories().values().stream().mapToInt(c -> c.quests().size()).sum();
        plugin.getLogger().info("quests.yml: " + config.categories().size() + " categories, " + quests + " quests.");
        refreshAllTitleCaches();
    }

    /**
     * Stary plugin trzymał POSTĘP w quests.yml (sekcja "gracze") - taki plik odkładamy na bok, żeby
     * nie udawał treści. Stary plugin zapisywał też CAŁKIEM PUSTY plik, gdy nikt jeszcze nie miał
     * postępu - bez "categories" i bez "gracze" - taki plik też nie jest treścią, więc traktujemy go
     * tak samo (inaczej /quests otwiera się z zerem kategorii, a domyślna treść nigdy się nie kopiuje,
     * bo plik przecież "istnieje").
     */
    private void moveOldProgressFile(File file) {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        if (y.contains("categories")) return;
        if (!y.getKeys(false).isEmpty() && !y.contains("gracze")) return;
        File old = new File(plugin.getDataFolder(), "quests-old-progress.yml");
        if (old.exists()) old = new File(plugin.getDataFolder(), "quests-old-progress-" + System.currentTimeMillis() + ".yml");
        if (file.renameTo(old)) {
            plugin.getLogger().warning("quests.yml did not look like quest content (empty or old player progress) - moved it to " + old.getName() + ".");
        } else {
            plugin.getLogger().warning("quests.yml did not look like quest content but could not be moved aside to " + old.getName() + " - check file permissions.");
        }
    }

    QuestConfig config() {
        return config;
    }

    // ---- Postęp ----

    private ProgressStore.PlayerProgress of(UUID uuid) {
        return progress.computeIfAbsent(uuid, k -> new ProgressStore.PlayerProgress());
    }

    private void saveProgress() {
        ProgressStore.write(progressYaml, progress);
        saver.oznaczZmiane();
    }

    void close() {
        saveProgress();
        saver.zamknij();
    }

    private boolean hasUnlock(Player player, String name) {
        UnlockService unlocks = CoreAPI.getUnlockService();
        return unlocks != null && unlocks.has(player.getUniqueId(), name);
    }

    private CategoryState stateOf(Player player, ProgressStore.PlayerProgress p, CategoryDef c) {
        return QuestRules.categoryState(c, p::doneView, name -> hasUnlock(player, name));
    }

    // ---- Menu główne ----

    void openMain(Player player) {
        QuestGuiHolder holder = new QuestGuiHolder(QuestGuiHolder.Kind.MAIN, null, 0);
        Inventory gui = holder.create(54, lang.msg(plugin, "menu.title"));
        fill(gui, config.mainMenu(), config.settings().filler());
        ProgressStore.PlayerProgress p = of(player.getUniqueId());
        Map<Integer, String> slots = QuestRules.categorySlots(config.mainMenu(), config.categoryOrder());
        slots.forEach((slot, id) -> gui.setItem(slot, categoryIcon(player, p, config.categories().get(id))));
        holder.categorySlots().putAll(slots);
        player.openInventory(gui);
    }

    private void fill(Inventory gui, List<SlotEntry> layout, ItemRef background) {
        ItemStack def = items.filler(null, background);
        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, def);
        for (SlotEntry e : layout) {
            if (e.role() == SlotRole.FILLER && e.material() != null) gui.setItem(e.slot(), items.filler(e.material(), background));
        }
    }

    private ItemStack categoryIcon(Player player, ProgressStore.PlayerProgress p, CategoryDef c) {
        CategoryState state = stateOf(player, p, c);
        QuestSettings s = c.look();
        List<Component> lore = new ArrayList<>();
        if (!c.description().isBlank()) lore.add(QuestItems.text("&7" + c.description()));
        ItemRef icon = c.icon();
        switch (state) {
            case LOCKED -> {
                icon = s.categoryLocked();
                lore.add(Component.empty());
                lore.add(lang.msg(plugin, "category.locked"));
                lore.add(lockReason(p, c));
            }
            case EMPTY -> {
                icon = s.categoryEmpty();
                lore.add(Component.empty());
                lore.add(lang.msg(plugin, "category.empty"));
                lore.add(lang.msg(plugin, "category.empty-hint"));
            }
            case AVAILABLE -> { }
        }
        boolean available = state == CategoryState.AVAILABLE;
        return items.icon(icon, QuestItems.text((available ? "&6&l" : "&8&l") + c.name()), lore, c.glow() && available);
    }

    private Component lockReason(ProgressStore.PlayerProgress p, CategoryDef c) {
        if (!QuestRules.afterDone(c, p::doneView)) {
            CategoryDef src = config.categories().get(c.after().category());
            String quest = src.quests().stream().filter(q -> q.id() == c.after().quest())
                    .map(QuestDef::title).findFirst().orElse("#" + c.after().quest());
            return lang.msg(plugin, "category.locked-after", Map.of("quest", quest, "category", src.name()));
        }
        return lang.msg(plugin, "category.locked-unlock");
    }

    // ---- Strona kategorii ----

    void openCategory(Player player, String categoryId, int page) {
        CategoryDef c = config.categories().get(categoryId);
        if (c == null) return;
        int pages = QuestRules.pageCount(c.pageLayout(), c.quests().size());
        int shown = Math.max(0, Math.min(page, pages - 1));
        QuestGuiHolder holder = new QuestGuiHolder(QuestGuiHolder.Kind.CATEGORY, categoryId, shown);
        Inventory gui = holder.create(54, lang.msg(plugin, "menu.category-title",
                Map.of("category", c.name(), "page", String.valueOf(shown + 1))));
        fill(gui, c.pageLayout(), c.look().filler());
        Set<Integer> done = of(player.getUniqueId()).doneView(categoryId);
        Map<Integer, Integer> questSlots = QuestRules.questSlots(c.pageLayout(), shown, c.quests().size());
        questSlots.forEach((slot, index) -> gui.setItem(slot, questIcon(c, index, QuestRules.state(c, index, done))));
        questSlots.forEach((slot, index) -> holder.questSlots().put(slot, c.quests().get(index).id()));
        QuestSettings s = c.look();
        for (SlotEntry e : c.pageLayout()) {
            switch (e.role()) {
                case NAV_BACK -> nav(gui, holder, e, s.back(), "menu.back");
                case NAV_PREV -> {
                    if (shown > 0) nav(gui, holder, e, s.prev(), "menu.prev");
                }
                case NAV_NEXT -> {
                    if (shown + 1 < pages) nav(gui, holder, e, s.next(), "menu.next");
                }
                default -> { }
            }
        }
        player.openInventory(gui);
    }

    private void nav(Inventory gui, QuestGuiHolder holder, SlotEntry e, ItemRef icon, String key) {
        gui.setItem(e.slot(), items.icon(icon, lang.msg(plugin, key), List.of(), false));
        holder.navSlots().put(e.slot(), e.role());
    }

    private static TextReplacementConfig placeholder(String key, Component value) {
        return TextReplacementConfig.builder().matchLiteral("{" + key + "}").replacement(value).build();
    }

    private ItemStack questIcon(CategoryDef c, int index, QuestState state) {
        QuestDef q = c.quests().get(index);
        QuestSettings s = c.look();
        if (state == QuestState.LOCKED) {
            return items.icon(s.locked(), lang.msg(plugin, "quest.locked-name"), List.of(lang.msg(plugin, "quest.locked-hint")), false);
        }
        boolean done = state == QuestState.DONE;
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        for (String line : q.description()) lore.add(QuestItems.text("&7" + line));
        lore.add(requirementLine(q.requirement()));
        // Nagroda widoczna dopiero po zrobieniu zadania (niespodzianka - tak jak dotąd).
        lore.add(done ? lang.msg(plugin, "quest.reward").replaceText(placeholder("reward", rewardLabel(q)))
                : lang.msg(plugin, "quest.reward-hidden"));
        lore.add(Component.empty());
        lore.add(lang.msg(plugin, done ? "quest.done" : "quest.click"));
        return items.icon(done ? s.completed() : s.available(), QuestItems.text((done ? "&a&l" : "&c&l") + q.title()), lore, false);
    }

    private Component requirementLine(Requirement r) {
        return switch (r) {
            case Requirement.Free f -> lang.msg(plugin, "quest.requirement.free");
            case Requirement.Money m -> lang.msg(plugin, "quest.requirement.money", Map.of("amount", MoneyFormat.pelna(m.amount())));
            case Requirement.Items i -> lang.msg(plugin, "quest.requirement.items").replaceText(placeholder("items", items.list(i.items())));
            case Requirement.HaveItem h -> lang.msg(plugin, "quest.requirement.have-item").replaceText(placeholder("items", items.list(List.of(h.item()))));
        };
    }

    private Component rewardLabel(QuestDef q) {
        if (q.rewardLabel() != null) return SER.deserialize(q.rewardLabel());
        List<Component> parts = new ArrayList<>();
        for (Reward r : q.rewards()) {
            if (r.silent()) continue;
            String v = String.valueOf(r.value());
            switch (r.type()) {
                case RewardService.MONEY -> parts.add(lang.msg(plugin, "label.money",
                        Map.of("amount", MoneyFormat.pelna(((Number) r.value()).doubleValue()))));
                case RewardService.ITEM -> parts.add(Component.text(r.amount() + "x ").append(items.name(new ItemRef(v, null, 1))));
                case RewardService.CUSTOM -> parts.add(Component.text(r.amount() + "x ").append(items.name(new ItemRef(null, v, 1))));
                case "crate", "key" -> parts.add(lang.msg(plugin, "label." + r.type(), Map.of("id", v)));
                case "title" -> parts.add(lang.msg(plugin, "label.title"));
                case RewardService.UNLOCK -> parts.add(lang.msg(plugin, "label.unlock"));
                default -> { } // komenda: bez opisu
            }
        }
        return parts.isEmpty() ? Component.text("-") : Component.join(JoinConfiguration.separator(Component.text(", ")), parts);
    }

    // ---- Kliknięcia ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof QuestGuiHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (holder.kind() == QuestGuiHolder.Kind.MAIN) {
            String id = holder.categorySlots().get(slot);
            if (id != null) clickCategory(player, id);
            return;
        }
        SlotRole nav = holder.navSlots().get(slot);
        if (nav != null) {
            switch (nav) {
                case NAV_BACK -> openMain(player);
                case NAV_PREV -> openCategory(player, holder.categoryId(), holder.page() - 1);
                case NAV_NEXT -> openCategory(player, holder.categoryId(), holder.page() + 1);
                default -> { }
            }
            return;
        }
        Integer questId = holder.questSlots().get(slot);
        if (questId == null) return;
        // Szukamy po id: jeśli admin w międzyczasie przeładował questy, klik trafia w zadanie,
        // które gracz widzi; gdy tego zadania już nie ma - odświeżamy menu.
        CategoryDef c = config.categories().get(holder.categoryId());
        int index = c == null ? -1 : indexOfQuest(c, questId);
        if (index < 0) openCategory(player, holder.categoryId(), holder.page());
        else complete(player, holder.categoryId(), index, holder.page());
    }

    private static int indexOfQuest(CategoryDef c, int questId) {
        for (int i = 0; i < c.quests().size(); i++) if (c.quests().get(i).id() == questId) return i;
        return -1;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof QuestGuiHolder) event.setCancelled(true);
    }

    private static void deny(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    private void clickCategory(Player player, String id) {
        CategoryDef c = config.categories().get(id);
        if (c == null) return;
        switch (stateOf(player, of(player.getUniqueId()), c)) {
            case EMPTY -> {
                lang.send(player, plugin, "category.empty-message");
                deny(player);
            }
            case LOCKED -> {
                lang.send(player, plugin, "category.locked-message");
                deny(player);
            }
            case AVAILABLE -> openCategory(player, id, 0);
        }
    }

    private void complete(Player player, String categoryId, int index, int page) {
        CategoryDef c = config.categories().get(categoryId);
        if (c == null || index >= c.quests().size()) return;
        QuestDef q = c.quests().get(index);
        ProgressStore.PlayerProgress p = of(player.getUniqueId());
        QuestState state = QuestRules.state(c, index, p.doneView(categoryId));
        if (state == QuestState.DONE) {
            lang.send(player, plugin, "quest.already-done");
            return;
        }
        if (state == QuestState.LOCKED) {
            lang.send(player, plugin, "quest.locked-message");
            deny(player);
            return;
        }
        // Pełny ekwipunek blokuje tylko zadania, które dają przedmioty (kasa, tytuł itp. zawsze wchodzą).
        if (QuestRules.givesItems(q.rewards()) && player.getInventory().firstEmpty() == -1) {
            lang.send(player, plugin, "quest.inventory-full");
            deny(player);
            return;
        }
        if (!takeRequirement(player, q.requirement())) {
            lang.send(player, plugin, missingKey(q.requirement()));
            deny(player);
            return;
        }
        p.doneIn(categoryId).add(q.id());
        saveProgress();
        lang.send(player, plugin, "quest.completed", Map.of("quest", q.title()));
        rewards.give(player, q.rewards());
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        openCategory(player, categoryId, page);
    }

    /** Sprawdza wymóg i od razu zabiera koszt; false = gracz go nie spełnia (nic nie zabrano). */
    private boolean takeRequirement(Player player, Requirement r) {
        return switch (r) {
            case Requirement.Free f -> true;
            case Requirement.Money m -> economy.pobierzGrosze(player.getUniqueId(), Math.round(m.amount() * 100));
            case Requirement.HaveItem h -> items.count(player, h.item()) >= h.item().amount();
            case Requirement.Items i -> takeItems(player, i.items());
        };
    }

    /**
     * Ten sam przedmiot może wystąpić w wymogu dwa razy (np. dwa osobne wpisy OAK_LOG) - sumujemy
     * żądaną ilość na przedmiot, ZANIM cokolwiek sprawdzimy albo zabierzemy. Bez tego każdy wpis
     * liczony byłby osobno - gracz z 16 kłodami przeszedłby wymóg "16x + 8x", mimo że łącznie
     * potrzeba 24.
     */
    private boolean takeItems(Player player, List<ItemRef> refs) {
        Map<String, ItemRef> byKey = new LinkedHashMap<>();
        Map<String, Integer> needed = new LinkedHashMap<>();
        for (ItemRef ref : refs) {
            String key = itemKey(ref);
            byKey.putIfAbsent(key, ref);
            needed.merge(key, ref.amount(), Integer::sum);
        }
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            if (items.count(player, byKey.get(e.getKey())) < e.getValue()) return false;
        }
        for (Map.Entry<String, Integer> e : needed.entrySet()) {
            items.take(player, byKey.get(e.getKey()), e.getValue());
        }
        return true;
    }

    private static String itemKey(ItemRef ref) {
        return ref.isCustom() ? "custom:" + ref.customId().toLowerCase(Locale.ROOT) : "material:" + ref.material();
    }

    private static String missingKey(Requirement r) {
        return switch (r) {
            case Requirement.Money m -> "quest.missing.money";
            case Requirement.HaveItem h -> "quest.missing.have-item";
            default -> "quest.missing.items";
        };
    }

    // ---- Tytuły ----

    /** Handler nagrody "title: id" - false = nieznany tytuł (RewardService użyje fallback). */
    boolean giveTitle(Player player, String id, boolean silent) {
        String text = config.titles().get(id);
        if (text == null) return false;
        if (of(player.getUniqueId()).titles().add(id)) {
            saveProgress();
            refreshTitleCache(player.getUniqueId());
        }
        if (!silent) lang.send(player, plugin, "reward.title", Map.of("title", text));
        return true;
    }

    /**
     * {@inheritDoc} Pierwszy zdobyty tytuł, który nadal jest w quests.yml.
     *
     * Czyta WYŁĄCZNIE {@link #titleCache} - mainplugins-ranks woła to z AsyncChatEvent (nie z
     * głównego wątku), a `progress` to zwykła HashMap, którą główny wątek stale mutuje (nowe
     * wpisy, zbiory tytułów) - równoległy odczyt/zapis tej samej HashMap groziłby
     * ConcurrentModificationException albo gorzej. titleCache jest ConcurrentHashMap i jest
     * przeliczany na głównym wątku (reload, giveTitle, reset) - ten odczyt jest więc bezpieczny
     * z dowolnego wątku bez żadnej synchronizacji.
     */
    @Override
    public Component tytulGracza(UUID uuid) {
        return titleCache.get(uuid);
    }

    /** Przelicza cache tytułu jednego gracza - wołać tylko z głównego wątku, po zmianie jego postępu albo treści. */
    private void refreshTitleCache(UUID uuid) {
        ProgressStore.PlayerProgress p = progress.get(uuid);
        Component title = null;
        if (p != null) {
            for (String id : p.titles()) {
                String text = config.titles().get(id);
                if (text != null) {
                    title = SER.deserialize(text).decoration(TextDecoration.ITALIC, false);
                    break;
                }
            }
        }
        if (title != null) titleCache.put(uuid, title);
        else titleCache.remove(uuid);
    }

    /** Wołać po każdym reload() - lista tytułów w quests.yml mogła się zmienić. */
    private void refreshAllTitleCaches() {
        for (UUID uuid : progress.keySet()) refreshTitleCache(uuid);
    }

    // ---- Admin ----

    /** categoryId null = cały postęp (razem z tytułami). */
    void reset(UUID uuid, String categoryId) {
        ProgressStore.PlayerProgress p = progress.get(uuid);
        if (p == null) return;
        if (categoryId == null) progress.remove(uuid);
        else p.done().remove(categoryId);
        saveProgress();
        refreshTitleCache(uuid);
    }

    /** Zalicza zadanie bez wymogu i daje nagrody; false = już było zrobione. */
    boolean forceComplete(Player player, CategoryDef c, QuestDef q) {
        if (!of(player.getUniqueId()).doneIn(c.id()).add(q.id())) return false;
        saveProgress();
        rewards.give(player, q.rewards());
        return true;
    }

    /** Cofa jedno zadanie (nagród nie zabiera); false = gracz go nie miał zrobionego. */
    boolean undo(UUID uuid, String categoryId, int questId) {
        ProgressStore.PlayerProgress p = progress.get(uuid);
        if (p == null || !p.doneView(categoryId).contains(questId)) return false;
        p.doneIn(categoryId).remove(questId);
        saveProgress();
        return true;
    }
}
