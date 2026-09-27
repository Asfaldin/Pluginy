package elo.mainplugins.shop;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.util.GuiUtils;
import elo.mainplugins.core.util.MenuBridge;
import elo.mainplugins.core.util.MoneyFormat;
import elo.mainplugins.shop.model.Category;
import elo.mainplugins.shop.model.MenuScreen;
import elo.mainplugins.shop.model.ShopConfig;
import elo.mainplugins.shop.model.ShopItem;
import elo.mainplugins.shop.model.ShopSettings;
import elo.mainplugins.shop.model.SlotEntry;
import elo.mainplugins.shop.model.SlotRole;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Menu sklepu, kupno (wybór ilości, "kup ile się da"), sprzedaż (/sell, /sellall, PPM w menu) i wyszukiwarka.
 * Treść i ceny z ShopConfig (shop.yml + categories/), liczenie w ShopRules, teksty w lang.
 */
public final class ShopManager implements Listener {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final LangService lang;
    private final EconomyService economy;
    private final ShopItems items;
    private final Supplier<ShopConfig> config;
    private final DynamicPriceManager prices;
    private final RotationManager rotation;
    private final ShopStats stats;
    private final ShopDeals deals;
    private final ShopHistory history;

    /** true = sortowanie po najwyższym skupie, false = po najtańszym kupnie. */
    /** Sortowanie wybrane lejkiem przez gracza; brak wpisu = domyślne z shop.yml (category-page-sort). */
    private final Map<UUID, ShopSettings.CategorySort> sortChoice = new HashMap<>();
    private final Set<UUID> awaitingSearch = new HashSet<>();
    private final Map<UUID, Boolean> fromMenu = new HashMap<>();

    public ShopManager(Plugin plugin, LangService lang, EconomyService economy, ShopItems items, Supplier<ShopConfig> config,
                       DynamicPriceManager prices, RotationManager rotation, ShopStats stats, ShopDeals deals, ShopHistory history) {
        this.plugin = plugin;
        this.lang = lang;
        this.economy = economy;
        this.items = items;
        this.config = config;
        this.prices = prices;
        this.rotation = rotation;
        this.stats = stats;
        this.deals = deals;
        this.history = history;
    }

    // ---------- teksty ----------

    private Component t(String key, Map<String, String> ph) {
        return lang.msg(plugin, key, ph).decoration(TextDecoration.ITALIC, false);
    }

    private Component t(String key) {
        return t(key, Map.of());
    }

    private void send(Player p, String key, Map<String, String> ph) {
        lang.send(p, plugin, key, ph);
    }

    private void send(Player p, String key) {
        lang.send(p, plugin, key);
    }

    /** Wiadomość z nazwą przedmiotu w języku gracza ({item} jako komponent, nie tekst). */
    private void sendWithItem(Player p, String key, Map<String, String> ph, ShopItem item) {
        Component name = items.name(item);
        p.sendMessage(lang.msg(plugin, key, ph).replaceText(TextReplacementConfig.builder().matchLiteral("{item}").replacement(name).build()));
    }

    static String money(double amount) {
        return MoneyFormat.pelna(amount);
    }

    private static Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m != null ? m : fallback;
    }

    private MenuScreen screen(String id) {
        return config.get().settings().menu(id);
    }

    private ItemStack button(String buttonId, Material fallback, Component name, Component... lore) {
        return GuiUtils.namedItem(material(config.get().settings().button(buttonId), fallback), name, lore);
    }

    /** Tło okna + ewentualne pola FILLER z własnym materiałem. */
    private void background(Inventory gui, MenuScreen s) {
        GuiUtils.fillBackground(gui, Material.GRAY_STAINED_GLASS_PANE);
        for (SlotEntry e : s.withRole(SlotRole.FILLER)) {
            if (e.material() != null && e.slot() < gui.getSize()) gui.setItem(e.slot(), GuiUtils.namedItem(material(e.material(), Material.GRAY_STAINED_GLASS_PANE), Component.empty()));
        }
    }

    // ---------- pozycje ----------

    /** Pozycje kategorii: stałe + aktualnie rotujące (z oznaczeniem). */
    private List<ShopGuiHolder.Ref> refsOf(Category c) {
        List<ShopGuiHolder.Ref> out = fixedRefsOf(c);
        out.addAll(rotatingRefsOf(c));
        return out;
    }

    private List<ShopGuiHolder.Ref> fixedRefsOf(Category c) {
        List<ShopGuiHolder.Ref> out = new ArrayList<>();
        for (int i = 0; i < c.items().size(); i++) out.add(new ShopGuiHolder.Ref(c.id(), false, i, c.items().get(i).key()));
        return out;
    }

    private List<ShopGuiHolder.Ref> rotatingRefsOf(Category c) {
        List<ShopGuiHolder.Ref> out = new ArrayList<>();
        List<ShopItem> active = rotation.active(c);
        for (int i = 0; i < active.size(); i++) out.add(new ShopGuiHolder.Ref(c.id(), true, i, active.get(i).key()));
        return out;
    }

    /** Pozycja dla odwołania albo null, gdy sklep się zmienił (reload, nowa rotacja). */
    private ShopItem resolve(ShopGuiHolder.Ref ref) {
        Category c = config.get().categories().get(ref.category());
        if (c == null) return null;
        List<ShopItem> list = ref.rotating() ? rotation.active(c) : c.items();
        if (ref.index() < 0 || ref.index() >= list.size()) return null;
        ShopItem it = list.get(ref.index());
        return it.key().equals(ref.key()) ? it : null;
    }

    /** Skup jednej paczki dla gracza (z premią rangi; null = bez premii, np. do sortowania). */
    private double sellPerLot(ShopItem it, Player player) {
        double factor = player == null ? 1.0 : deals.sellFactor(player);
        return ShopRules.sellPerLot(it, prices.getMnoznik(it.key()) * factor, sellShare(player, it.key()), config.get().settings().rounding());
    }

    /**
     * Sufit skupu jako część ZWYKŁEJ ceny kupna: max-sell-share razy najniższy mnożnik kupna, jaki ten gracz
     * ma teraz na ten przedmiot (promocja w którejś kategorii, rabat rangi). Bez tego event + promocja
     * pozwalały kupić za 8 i sprzedać za 9 w kółko - skup ma być zawsze poniżej tego, za ile da się kupić.
     */
    private double sellShare(Player player, String key) {
        double lowest = 1.0;
        for (Category c : config.get().categories().values()) {
            boolean here = false;
            for (ShopItem i : c.items()) if (i.buyable() && i.key().equals(key)) here = true;
            for (ShopItem i : rotation.active(c)) if (i.buyable() && i.key().equals(key)) here = true;
            if (here) lowest = Math.min(lowest, deals.buyFactor(player, key, c.id()));
        }
        return config.get().settings().dynamic().maxSellShare() * lowest;
    }

    /** Cena kupna `pieces` sztuk dla gracza: z promocją (przedmiot/kategoria/sklep) i rabatem rangi. */
    private double buyPriceFor(Player player, ShopItem it, String categoryId, int pieces) {
        return ShopRules.buyPrice(it, pieces, config.get().settings().rounding(), deals.buyFactor(player, it.key(), categoryId));
    }

    /** Linijki promocji i rabatu rangi pod ceną kupna (puste, gdy nic nie obniża ceny). */
    private List<Component> dealLines(Player player, ShopItem it, String categoryId) {
        List<Component> out = new ArrayList<>();
        double sale = deals.salePercent(it.key(), categoryId);
        if (sale > 0) {
            Long left = deals.saleTimeLeft(it.key(), categoryId);
            out.add(left == null ? t("item.sale", Map.of("percent", pct(sale)))
                    : t("item.sale-timed", Map.of("percent", pct(sale), "time", ShopRules.formatDuration(left))));
        }
        double rank = deals.rankDiscount(player);
        if (rank > 0) out.add(t("item.rank-discount", Map.of("percent", pct(rank))));
        return out;
    }

    /** 20.0 -> "20", 2.5 -> "2.5". */
    static String pct(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }

    /** Ikona pozycji w siatce: sam przedmiot (1 szt.) + ceny i podpowiedzi - dla konkretnego gracza. */
    private ItemStack itemIcon(Player player, ShopGuiHolder.Ref ref, ShopItem it, boolean withCategory) {
        ItemStack icon = items.icon(it);
        if (icon == null) return null;
        icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        if (!lore.isEmpty()) lore.add(Component.empty());
        if (withCategory) {
            Category c = config.get().categories().get(ref.category());
            lore.add(t("item.category", Map.of("category", c == null ? ref.category() : c.name())));
        }
        if (ref.rotating()) lore.add(t("item.rotating", Map.of("days", String.valueOf(rotation.daysLeft(ref.category())))));
        var rounding = config.get().settings().rounding();
        if (it.buyable()) {
            double base = ShopRules.buyPrice(it, 1, rounding);
            double mine = buyPriceFor(player, it, ref.category(), 1);
            // Taniej niż w cenniku - stara cena przekreślona obok nowej.
            lore.add(mine < base ? t("item.buy-discounted", Map.of("old", money(base), "price", money(mine)))
                    : t("item.buy", Map.of("price", money(mine))));
            lore.addAll(dealLines(player, it, ref.category()));
        }
        if (it.sellable()) {
            Component line = t("item.sell", Map.of("price", money(sellPerLot(it, player)), "amount", String.valueOf(it.sellAmount())));
            if (prices.enabled()) {
                if (prices.czyZablokowany(it.key())) {
                    Long left = prices.zostaloEventu(it.key());
                    line = line.append(left == null ? t("item.event")
                            : t("item.event-timed", Map.of("time", ShopRules.formatDuration(left))));
                }
                else if (prices.kierunekZmiany(it.key()) > 0) line = line.append(t("item.trend-up"));
                else if (prices.kierunekZmiany(it.key()) < 0) line = line.append(t("item.trend-down"));
            }
            lore.add(line);
        }
        lore.add(Component.empty());
        lore.add(t(it.buyable() ? "item.lmb-buy" : "item.cannot-buy"));
        if (it.sellable()) {
            lore.add(t("item.rmb-sell", Map.of("amount", String.valueOf(it.sellAmount()))));
            lore.add(t("item.shift-rmb-sell"));
        } else {
            lore.add(t("item.cannot-sell"));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    // ---------- menu główne ----------

    public void openMain(Player player, boolean menu) {
        fromMenu.put(player.getUniqueId(), menu);
        MenuScreen s = screen("main-menu");
        ShopGuiHolder holder = new ShopGuiHolder(ShopGuiHolder.Kind.MAIN, null, 0, menu, null);
        Inventory gui = holder.create(s.size(), lang.msg(plugin, "menu.main-title"));
        background(gui, s);

        Integer search = s.first(SlotRole.SEARCH);
        if (search != null) {
            gui.setItem(search, button("search", Material.OAK_SIGN, t("menu.search"), t("menu.search-hint-1"), t("menu.search-hint-2")));
            holder.buttons().put(search, "SEARCH");
        }
        List<SlotEntry> catSlots = s.withRole(SlotRole.CATEGORY_SLOT);
        List<String> order = config.get().settings().categoryOrder();
        for (int i = 0; i < catSlots.size() && i < order.size(); i++) {
            Category c = config.get().categories().get(order.get(i));
            if (c == null) continue;
            ItemStack icon = c.iconCustom() != null ? items.create(new ShopItem(null, c.iconCustom(), 0.0, null, 1, 1, null, List.of(), null, true), 1, null) : null;
            if (icon == null) icon = new ItemStack(material(c.iconMaterial(), Material.CHEST));
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(SER.deserialize(c.name()).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(t("menu.category-hint")));
            meta.setEnchantmentGlintOverride(true);
            icon.setItemMeta(meta);
            gui.setItem(catSlots.get(i).slot(), icon);
            holder.categories().put(catSlots.get(i).slot(), c.id());
        }
        exitButton(holder, gui, s, menu);
        player.openInventory(gui);
    }

    private void exitButton(ShopGuiHolder holder, Inventory gui, MenuScreen s, boolean menu) {
        Integer exit = s.first(SlotRole.EXIT);
        if (exit == null) return;
        gui.setItem(exit, menu ? GuiUtils.namedItem(Material.NETHER_STAR, t("menu.exit-menu")) : button("exit", Material.BARRIER, t("menu.exit-close")));
        holder.buttons().put(exit, "EXIT");
    }

    // ---------- strona kategorii ----------

    public void openCategory(Player player, String categoryId, int page) {
        Category c = config.get().categories().get(categoryId);
        if (c == null) {
            openMain(player, fromMenu.getOrDefault(player.getUniqueId(), false));
            return;
        }
        boolean menu = fromMenu.getOrDefault(player.getUniqueId(), false);
        MenuScreen s = c.layout() != null ? c.layout() : screen("category-page");
        List<SlotEntry> itemSlots = s.withRole(SlotRole.ITEM_SLOT);
        // Są pola rotacji = rotacja stoi na nich (na każdej stronie, po kolei od pierwszego), osobno od stałych.
        // Brak = jak dawniej: rotacja za stałymi przedmiotami.
        List<SlotEntry> rotationSlots = s.withRole(SlotRole.ROTATION_SLOT).stream().sorted(Comparator.comparingInt(SlotEntry::slot)).toList();
        ShopSettings.CategorySort sortMode = sortChoice.getOrDefault(player.getUniqueId(), config.get().settings().categorySort());
        List<ShopGuiHolder.Ref> refs = sorted(rotationSlots.isEmpty() ? refsOf(c) : fixedRefsOf(c), sortMode);

        int per = Math.max(1, itemSlots.size());
        int pages = Math.max(1, (refs.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));

        ShopGuiHolder holder = new ShopGuiHolder(ShopGuiHolder.Kind.CATEGORY, c.id(), page, menu, null);
        Inventory gui = holder.create(s.size(), lang.msg(plugin, "menu.category-title", Map.of("category", c.name())));
        background(gui, s);

        int start = page * per;
        int end = Math.min(start + per, refs.size());
        List<Integer> slots = pageSlots(itemSlots.stream().map(SlotEntry::slot).toList(), refs.size(),
                pages == 1 && config.get().settings().centerSmallCategories());
        for (int i = start; i < end && i - start < slots.size(); i++) {
            ShopGuiHolder.Ref ref = refs.get(i);
            ShopItem it = resolve(ref);
            ItemStack icon = it == null ? null : itemIcon(player, ref, it, false);
            if (icon == null) continue;
            int slot = slots.get(i - start);
            gui.setItem(slot, icon);
            holder.items().put(slot, ref);
        }
        if (!rotationSlots.isEmpty()) {
            List<ShopGuiHolder.Ref> rotating = rotatingRefsOf(c);
            for (int i = 0; i < rotating.size() && i < rotationSlots.size(); i++) {
                ShopGuiHolder.Ref ref = rotating.get(i);
                ShopItem it = resolve(ref);
                ItemStack icon = it == null ? null : itemIcon(player, ref, it, false);
                if (icon == null) continue;
                int slot = rotationSlots.get(i).slot();
                gui.setItem(slot, icon);
                holder.items().put(slot, ref);
            }
        }

        Map<String, String> pageInfo = Map.of("pages", String.valueOf(pages));
        Integer prev = s.first(SlotRole.NAV_PREV);
        if (prev != null && page > 0) {
            gui.setItem(prev, button("prev", Material.SPECTRAL_ARROW, t("menu.prev"), t("menu.page", with(pageInfo, "page", String.valueOf(page)))));
            holder.buttons().put(prev, "NAV_PREV");
        }
        Integer next = s.first(SlotRole.NAV_NEXT);
        if (next != null && page < pages - 1) {
            gui.setItem(next, button("next", Material.SPECTRAL_ARROW, t("menu.next"), t("menu.page", with(pageInfo, "page", String.valueOf(page + 2)))));
            holder.buttons().put(next, "NAV_NEXT");
        }
        Integer sort = s.first(SlotRole.SORT);
        if (sort != null) {
            boolean bySell = sortMode == ShopSettings.CategorySort.SELL;
            boolean byBuy = sortMode == ShopSettings.CategorySort.BUY;
            String now = bySell ? "menu.sort-now-sell" : byBuy ? "menu.sort-now-buy" : "menu.sort-now-order";
            gui.setItem(sort, button(bySell ? "sort-sell" : "sort", bySell ? Material.GOLD_INGOT : Material.HOPPER, t("menu.sort"),
                    t(now), Component.empty(),
                    SER.deserialize((byBuy ? "&e" : "&7") + PlainTextComponentSerializer.plainText().serialize(t("menu.sort-lmb"))).decoration(TextDecoration.ITALIC, false),
                    SER.deserialize((bySell ? "&e" : "&7") + PlainTextComponentSerializer.plainText().serialize(t("menu.sort-rmb"))).decoration(TextDecoration.ITALIC, false),
                    t("menu.sort-again")));
            holder.buttons().put(sort, "SORT");
        }
        Integer back = s.first(SlotRole.NAV_BACK);
        if (back != null) {
            gui.setItem(back, button("back", Material.COMPASS, t("menu.back-categories")));
            holder.buttons().put(back, "NAV_BACK");
        }
        exitButton(holder, gui, s, menu);
        player.openInventory(gui);
    }

    private static Map<String, String> with(Map<String, String> base, String k, String v) {
        Map<String, String> m = new HashMap<>(base);
        m.put(k, v);
        return m;
    }

    private List<ShopGuiHolder.Ref> sorted(List<ShopGuiHolder.Ref> refs, ShopSettings.CategorySort mode) {
        // Kolejność sklepu: dokładnie tak, jak ułożył właściciel (np. szachownica z pól na przedmioty).
        if (mode == ShopSettings.CategorySort.ORDER) return refs;
        boolean bySell = mode == ShopSettings.CategorySort.SELL;
        var rounding = config.get().settings().rounding();
        Map<ShopGuiHolder.Ref, Double> value = new LinkedHashMap<>();
        for (ShopGuiHolder.Ref r : refs) {
            ShopItem it = resolve(r);
            double v;
            if (it == null) v = Double.MAX_VALUE;
            else if (bySell) v = it.sellable() ? -(sellPerLot(it, null) / it.sellAmount()) : Double.MAX_VALUE;
            else v = it.buyable() ? ShopRules.buyPrice(it, 1, rounding) : Double.MAX_VALUE;
            value.put(r, v);
        }
        List<ShopGuiHolder.Ref> out = new ArrayList<>(refs);
        out.sort(Comparator.comparingDouble(value::get));
        return out;
    }

    /**
     * Pola na stronie. Gdy wszystko mieści się w jednym rzędzie na jednej stronie (np. 5 pozycji
     * rotacji), pozycje stoją wyśrodkowane w środkowym rzędzie - jak dawniej.
     */
    static List<Integer> pageSlots(List<Integer> full, int count, boolean onePage) {
        if (!onePage || count >= full.size()) return full;
        LinkedHashMap<Integer, List<Integer>> rows = new LinkedHashMap<>();
        for (int slot : full) rows.computeIfAbsent(slot / 9, k -> new ArrayList<>()).add(slot);
        List<List<Integer>> list = new ArrayList<>(rows.values());
        int widest = list.stream().mapToInt(List::size).max().orElse(full.size());
        if (count > widest) return full;
        List<Integer> row = list.get((list.size() - 1) / 2);
        int indent = Math.max(0, (row.size() - count) / 2);
        return new ArrayList<>(row.subList(indent, Math.min(row.size(), indent + count)));
    }

    // ---------- wybór ilości ----------

    private void openPicker(Player player, ShopGuiHolder.Ref ref, int returnPage) {
        ShopItem it = resolve(ref);
        if (it == null) {
            send(player, "buy.gone");
            player.closeInventory();
            return;
        }
        if (!it.buyable()) {
            send(player, "buy.cannot-buy");
            return;
        }
        ItemStack sample = items.create(it, 1, player);
        if (sample == null) {
            send(player, "buy.unavailable");
            return;
        }
        MenuScreen s = screen("buy-picker");
        ShopGuiHolder holder = new ShopGuiHolder(ShopGuiHolder.Kind.PICKER, ref.category(), returnPage, fromMenu.getOrDefault(player.getUniqueId(), false), ref);
        Inventory gui = holder.create(s.size(), lang.msg(plugin, "menu.picker-title"));
        background(gui, s);
        double balance = economy.getKasa(player.getUniqueId());
        List<Component> deal = dealLines(player, it, ref.category());
        for (SlotEntry e : s.withRole(SlotRole.AMOUNT_SLOT)) {
            double price = buyPriceFor(player, it, ref.category(), e.amount());
            boolean afford = balance >= price;
            ItemStack option = sample.clone();
            option.setAmount(Math.max(1, Math.min(e.amount(), option.getMaxStackSize())));
            ItemMeta meta = option.getItemMeta();
            meta.displayName(t(afford ? "picker.amount-ok" : "picker.amount-no", Map.of("amount", String.valueOf(e.amount()))));
            List<Component> lore = new ArrayList<>();
            lore.add(t("picker.price", Map.of("price", money(price))));
            if (e.amount() > 1) lore.add(t("picker.per-piece", Map.of("price", money(buyPriceFor(player, it, ref.category(), 1)))));
            lore.addAll(deal);
            lore.add(Component.empty());
            lore.add(t(afford ? "picker.click" : "picker.no-money"));
            lore.add(t("picker.shift"));
            meta.lore(lore);
            option.setItemMeta(meta);
            gui.setItem(e.slot(), option);
            holder.amounts().put(e.slot(), e.amount());
        }
        Integer back = s.first(SlotRole.NAV_BACK);
        if (back != null) {
            gui.setItem(back, button("picker-back", Material.ARROW, t("menu.back-picker")));
            holder.buttons().put(back, "NAV_BACK");
        }
        player.openInventory(gui);
    }

    /** Ile sztuk tego przedmiotu zmieści się w ekwipunku (wolne pola + dopełnienie takich samych stosów). */
    private static int freeSpace(Player player, ItemStack sample) {
        int free = 0;
        int max = sample.getMaxStackSize();
        for (ItemStack is : player.getInventory().getStorageContents()) {
            if (is == null || is.getType().isAir()) free += max;
            else if (is.isSimilar(sample)) free += Math.max(0, max - is.getAmount());
        }
        return free;
    }

    private void buy(Player player, ShopItem it, String categoryId, int pieces, boolean max) {
        ItemStack sample = items.create(it, 1, player);
        if (sample == null) {
            send(player, "buy.unavailable");
            return;
        }
        var rounding = config.get().settings().rounding();
        double factor = deals.buyFactor(player, it.key(), categoryId);
        int free = freeSpace(player, sample);
        if (max) {
            if (free <= 0) {
                send(player, "buy.no-space-any");
                return;
            }
            pieces = ShopRules.maxBuyPieces(it, economy.getKasa(player.getUniqueId()), free, rounding, factor);
            if (pieces <= 0) {
                send(player, "buy.cannot-afford-one");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                return;
            }
        }
        double price = ShopRules.buyPrice(it, pieces, rounding, factor);
        if (!economy.maWystarczajaco(player.getUniqueId(), price)) {
            send(player, "buy.no-money", Map.of("price", money(price)));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        // Miejsce przed pobraniem pieniędzy - inaczej gracz płaci za towar, który wypadnie.
        if (free < pieces) {
            send(player, "buy.no-space");
            return;
        }
        economy.odejmijKase(player.getUniqueId(), price);
        int left = pieces;
        while (left > 0) {
            int n = Math.min(left, sample.getMaxStackSize());
            ItemStack stack = sample.clone();
            stack.setAmount(n);
            player.getInventory().addItem(stack);
            left -= n;
        }
        sendWithItem(player, "buy.bought", Map.of("amount", String.valueOf(pieces), "price", money(price)), it);
    }

    // ---------- /cena ----------

    /** /cena (/price): ile gracz zapłaci i ile dostanie za przedmiot w ręce - z jego rabatami i premią. */
    public void priceCheck(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            send(player, "price-check.empty-hand");
            return;
        }
        String key = items.keyOf(hand);
        String categoryId = null;
        ShopItem buyOffer = null;
        for (Category c : config.get().categories().values()) {
            for (ShopItem it : c.items()) if (buyOffer == null && it.buyable() && it.key().equals(key)) { buyOffer = it; categoryId = c.id(); }
            for (ShopItem it : rotation.active(c)) if (buyOffer == null && it.buyable() && it.key().equals(key)) { buyOffer = it; categoryId = c.id(); }
        }
        ShopItem sellOffer = ShopRules.sellOffer(config.get(), rotation.allActive(), key);
        if (buyOffer == null && sellOffer == null) {
            send(player, "price-check.not-in-shop");
            return;
        }
        ShopItem any = buyOffer != null ? buyOffer : sellOffer;
        sendWithItem(player, "price-check.header", Map.of(), any);
        if (buyOffer != null) {
            double base = ShopRules.buyPrice(buyOffer, 1, config.get().settings().rounding());
            double mine = buyPriceFor(player, buyOffer, categoryId, 1);
            player.sendMessage(mine < base ? t("price-check.buy-discounted", Map.of("old", money(base), "price", money(mine)))
                    : t("price-check.buy", Map.of("price", money(mine))));
            for (Component line : dealLines(player, buyOffer, categoryId)) player.sendMessage(line);
        } else {
            send(player, "price-check.cannot-buy");
        }
        if (sellOffer != null) {
            Component line = t("price-check.sell", Map.of("price", money(sellPerLot(sellOffer, player)), "amount", String.valueOf(sellOffer.sellAmount())));
            if (prices.enabled() && prices.czyZablokowany(key)) {
                Long left = prices.zostaloEventu(key);
                line = line.append(left == null ? t("item.event") : t("item.event-timed", Map.of("time", ShopRules.formatDuration(left))));
            }
            player.sendMessage(line);
            double bonus = deals.rankSellBonus(player);
            if (bonus > 0) send(player, "price-check.rank-sell-bonus", Map.of("percent", pct(bonus)));
        } else {
            send(player, "price-check.cannot-sell");
        }
    }

    // ---------- sprzedaż ----------

    private enum SellMode { HAND, ONE_STACK, ALL }

    public void sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            send(player, "sell.empty-hand");
            return;
        }
        sell(player, items.keyOf(hand), SellMode.HAND);
    }

    public void sellAll(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            send(player, "sell.empty-hand-all");
            return;
        }
        sell(player, items.keyOf(hand), SellMode.ALL);
    }

    private void sell(Player player, String key, SellMode mode) {
        ShopItem offer = ShopRules.sellOffer(config.get(), rotation.allActive(), key);
        if (offer == null) {
            send(player, "sell.cannot-sell");
            return;
        }
        PlayerInventory inv = player.getInventory();
        int owned = switch (mode) {
            case HAND -> key.equals(items.keyOf(inv.getItemInMainHand())) ? inv.getItemInMainHand().getAmount() : 0;
            case ONE_STACK -> firstStack(inv, key);
            case ALL -> count(inv, key);
        };
        if (owned == 0) {
            send(player, "sell.nothing");
            return;
        }
        // Premia rangi podnosi skup, ale sufit (poniżej ceny kupna z promocją i rabatem) i tak obowiązuje.
        ShopRules.SellResult r = ShopRules.sell(offer, owned, prices.getMnoznik(key) * deals.sellFactor(player), sellShare(player, key),
                config.get().settings().rounding());
        if (r.lots() == 0) {
            send(player, "sell.not-enough", Map.of("amount", String.valueOf(offer.sellAmount()), "have", String.valueOf(owned)));
            return;
        }
        if (mode == SellMode.HAND) {
            ItemStack hand = inv.getItemInMainHand();
            hand.setAmount(hand.getAmount() - r.pieces());
            inv.setItemInMainHand(hand.getAmount() > 0 ? hand : null);
        } else {
            remove(inv, key, r.pieces());
        }
        economy.dodajKase(player.getUniqueId(), r.money());
        prices.zarejestrujSprzedaz(key, r.pieces());
        stats.zapiszTransakcje(key, r.pieces(), r.money());
        if (config.get().settings().statsEnabled()) {
            history.record(java.time.LocalDate.now(), key, player.getUniqueId().toString(), player.getName(), r.pieces(), r.money());
        }

        Component msg = lang.msg(plugin, "sell.sold", Map.of("amount", String.valueOf(r.pieces()), "price", money(r.money())))
                .replaceText(TextReplacementConfig.builder().matchLiteral("{item}").replacement(items.name(offer)).build());
        int rest = owned - r.pieces();
        if (rest > 0) msg = msg.append(lang.msg(plugin, "sell.rest", Map.of("rest", String.valueOf(rest))));
        player.sendMessage(msg);
    }

    private int count(PlayerInventory inv, String key) {
        int n = 0;
        for (ItemStack is : inv.getStorageContents()) if (key.equals(items.keyOf(is))) n += is.getAmount();
        return n;
    }

    private int firstStack(PlayerInventory inv, String key) {
        for (ItemStack is : inv.getStorageContents()) if (key.equals(items.keyOf(is))) return is.getAmount();
        return 0;
    }

    /** Zabiera dokładnie tyle sztuk, ile trzeba - tylko przedmioty o tym samym kluczu. */
    private void remove(PlayerInventory inv, String key, int amount) {
        ItemStack[] content = inv.getStorageContents();
        for (int i = 0; i < content.length && amount > 0; i++) {
            ItemStack is = content[i];
            if (!key.equals(items.keyOf(is))) continue;
            int take = Math.min(is.getAmount(), amount);
            is.setAmount(is.getAmount() - take);
            amount -= take;
            if (is.getAmount() <= 0) content[i] = null;
        }
        inv.setStorageContents(content);
    }

    // ---------- otwieranie z zewnątrz (/sklep bloki, /@shop open, tabliczka, NPC) ----------

    /** Id kategorii z tekstu (id albo nazwa bez kolorów) albo null. */
    public String findCategory(String raw) {
        Map<String, String> names = new LinkedHashMap<>();
        config.get().categories().forEach((id, c) -> names.put(id, c.name()));
        return ShopRules.matchCategory(raw, names);
    }

    /** Otwiera sklep od razu na kategorii; null albo nieznana kategoria = menu główne. */
    public void openFor(Player player, String categoryId) {
        fromMenu.put(player.getUniqueId(), false);
        if (categoryId == null || !config.get().categories().containsKey(categoryId)) openMain(player, false);
        else openCategory(player, categoryId, 0);
    }

    /** /sklep szukaj <nazwa> - wyniki bez klikania "Szukaj" i pisania na czacie. */
    public void search(Player player, String query) {
        fromMenu.put(player.getUniqueId(), false);
        awaitingSearch.remove(player.getUniqueId());
        openSearch(player, query);
    }

    // ---------- wyszukiwarka ----------

    private void openSearch(Player player, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<ShopGuiHolder.Ref> hits = new ArrayList<>();
        for (Category c : config.get().categories().values()) {
            for (ShopGuiHolder.Ref ref : refsOf(c)) {
                ShopItem it = resolve(ref);
                if (it == null) continue;
                for (String term : items.searchTerms(it)) {
                    if (term.contains(q)) {
                        hits.add(ref);
                        break;
                    }
                }
            }
        }
        boolean menu = fromMenu.getOrDefault(player.getUniqueId(), false);
        if (hits.isEmpty()) {
            send(player, "search.none", Map.of("query", query));
            openMain(player, menu);
            return;
        }
        MenuScreen s = screen("search-results");
        List<SlotEntry> slots = s.withRole(SlotRole.ITEM_SLOT);
        ShopGuiHolder holder = new ShopGuiHolder(ShopGuiHolder.Kind.SEARCH, null, 0, menu, null);
        Inventory gui = holder.create(s.size(), lang.msg(plugin, "menu.search-title", Map.of("query", query)));
        background(gui, s);
        int shown = Math.min(hits.size(), slots.size());
        for (int i = 0; i < shown; i++) {
            ShopGuiHolder.Ref ref = hits.get(i);
            ItemStack icon = itemIcon(player, ref, resolve(ref), true);
            if (icon == null) continue;
            gui.setItem(slots.get(i).slot(), icon);
            holder.items().put(slots.get(i).slot(), ref);
        }
        Integer back = s.first(SlotRole.NAV_BACK);
        if (back != null) {
            gui.setItem(back, button("back", Material.COMPASS, t("menu.back-shop")));
            holder.buttons().put(back, "NAV_BACK");
        }
        player.openInventory(gui);
        if (hits.size() > shown) send(player, "search.found-cut", Map.of("count", String.valueOf(hits.size()), "shown", String.valueOf(shown)));
        else send(player, "search.found", Map.of("count", String.valueOf(hits.size())));
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!awaitingSearch.remove(player.getUniqueId())) return;
        event.setCancelled(true);
        String query = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        String cancelWord = PlainTextComponentSerializer.plainText().serialize(lang.msg(plugin, "search.cancel-word"));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (query.isEmpty() || query.equalsIgnoreCase(cancelWord) || query.equalsIgnoreCase("cancel")) {
                send(player, "search.cancelled");
                return;
            }
            openSearch(player, query);
        });
    }

    // ---------- kliknięcia ----------

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof ShopGuiHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof ShopGuiHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) return;
        int slot = event.getRawSlot();
        String button = holder.buttons().get(slot);

        switch (holder.kind()) {
            case MAIN -> {
                if ("SEARCH".equals(button)) {
                    awaitingSearch.add(player.getUniqueId());
                    player.closeInventory();
                    send(player, "search.prompt");
                    send(player, "search.cancel-hint");
                } else if ("EXIT".equals(button)) {
                    exit(player, holder.fromMenu());
                } else if (holder.categories().containsKey(slot)) {
                    openCategory(player, holder.categories().get(slot), 0);
                }
            }
            case CATEGORY -> {
                if (button != null) {
                    switch (button) {
                        case "SORT" -> {
                            // LPM - od najtańszego kupna, PPM - od najwyższego skupu; to samo jeszcze raz - kolejność sklepu.
                            ShopSettings.CategorySort want = event.isRightClick() ? ShopSettings.CategorySort.SELL : ShopSettings.CategorySort.BUY;
                            ShopSettings.CategorySort cur = sortChoice.getOrDefault(player.getUniqueId(), config.get().settings().categorySort());
                            sortChoice.put(player.getUniqueId(), cur == want ? ShopSettings.CategorySort.ORDER : want);
                            openCategory(player, holder.category(), 0);
                        }
                        case "NAV_PREV" -> openCategory(player, holder.category(), holder.page() - 1);
                        case "NAV_NEXT" -> openCategory(player, holder.category(), holder.page() + 1);
                        case "NAV_BACK" -> openMain(player, holder.fromMenu());
                        case "EXIT" -> exit(player, holder.fromMenu());
                        default -> { }
                    }
                    return;
                }
                ShopGuiHolder.Ref ref = holder.items().get(slot);
                if (ref != null) clickItem(player, ref, event, () -> openCategory(player, holder.category(), holder.page()), holder.page());
            }
            case SEARCH -> {
                if ("NAV_BACK".equals(button)) {
                    openMain(player, holder.fromMenu());
                    return;
                }
                ShopGuiHolder.Ref ref = holder.items().get(slot);
                if (ref != null) clickItem(player, ref, event, () -> { }, 0);
            }
            case PICKER -> {
                if ("NAV_BACK".equals(button)) {
                    openCategory(player, holder.category(), holder.page());
                    return;
                }
                Integer amount = holder.amounts().get(slot);
                if (amount == null) return;
                ShopItem it = resolve(holder.picked());
                if (it == null) {
                    send(player, "buy.gone");
                    player.closeInventory();
                    return;
                }
                // Shift+LPM = kup ile się da (kasa + miejsce), zamiast liczby z przycisku.
                buy(player, it, holder.picked().category(), amount, event.isShiftClick() && event.isLeftClick());
                openPicker(player, holder.picked(), holder.page());
            }
        }
    }

    private void clickItem(Player player, ShopGuiHolder.Ref ref, InventoryClickEvent event, Runnable refresh, int page) {
        ShopItem it = resolve(ref);
        if (it == null) {
            send(player, "buy.gone");
            player.closeInventory();
            return;
        }
        if (event.isLeftClick()) {
            if (!it.buyable()) send(player, "buy.cannot-buy");
            else openPicker(player, ref, page);
        } else if (event.isRightClick()) {
            if (!it.sellable()) {
                send(player, "sell.cannot-sell");
                return;
            }
            sell(player, it.key(), event.isShiftClick() ? SellMode.ALL : SellMode.ONE_STACK);
            refresh.run();
        }
    }

    private void exit(Player player, boolean menu) {
        if (menu) MenuBridge.returnToMainMenu(player);
        else player.closeInventory();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sortChoice.remove(id);
        awaitingSearch.remove(id);
        fromMenu.remove(id);
    }
}
