package elo.mainplugins.market;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.core.util.GuiUtils;
import elo.mainplugins.core.util.MenuBridge;
import elo.mainplugins.core.util.MoneyFormat;
import elo.mainplugins.market.model.ButtonDef;
import elo.mainplugins.market.model.Listing;
import elo.mainplugins.market.model.MailItem;
import elo.mainplugins.market.model.MarketSettings;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Targ graczy: menu, wystawianie, kupno, wycofanie, wygasanie ofert, skrzynka "Do odebrania", podatek.
 * Działa z samym core - limit ofert z uprawnień, nie z pluginu Rang.
 */
public final class MarketManager implements Listener {

    private static final long RETRACT_TIMEOUT_TICKS = 15 * 20L;
    private static final long EXPIRY_CHECK_TICKS = 60 * 20L;
    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();

    private final Plugin plugin;
    private final LangService lang;
    private final EconomyService economy;
    private final ListingStore store;
    private final AsyncConfigSaver saver;
    private MarketSettings settings;
    private BukkitTask expiryTask;

    private final Map<UUID, Boolean> onlyMine = new HashMap<>();
    private final Map<UUID, Boolean> sortAsc = new HashMap<>();
    private final Set<UUID> awaitingSearch = new HashSet<>();
    private final Map<UUID, String> lastQuery = new HashMap<>();
    private final Map<UUID, Boolean> lastFromMenu = new HashMap<>();
    private final Map<UUID, String> pendingRetract = new HashMap<>();

    public MarketManager(Plugin plugin, LangService lang, EconomyService economy) {
        this.plugin = plugin;
        this.lang = lang;
        this.economy = economy;
        if (!new File(plugin.getDataFolder(), "market.yml").exists()) plugin.saveResource("market.yml", false);
        reload();

        File listingsFile = new File(plugin.getDataFolder(), "listings.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(listingsFile);
        this.store = new ListingStore(yml);
        this.saver = new AsyncConfigSaver(plugin, yml, listingsFile, 10);
        importLegacy();

        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expireOffers, EXPIRY_CHECK_TICKS, EXPIRY_CHECK_TICKS);
    }

    public void reload() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "market.yml"));
        settings = MarketSettingsParser.parse(yml, m -> Material.matchMaterial(m) != null, plugin.getLogger()::warning);
    }

    public void close() {
        if (expiryTask != null) expiryTask.cancel();
        saver.zamknij();
    }

    public ListingStore store() {
        return store;
    }

    private void changed() {
        saver.oznaczZmiane();
    }

    /** Stary rynek.yml (przedmioty.<id>.{item,cena,sprzedawca,nick_sprzedawcy}) -> listings.yml, raz. */
    private void importLegacy() {
        File old = new File(plugin.getDataFolder(), "rynek.yml");
        if (!old.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(old);
        Map<String, ListingStore.LegacyOffer> offers = new LinkedHashMap<>();
        ConfigurationSection s = y.getConfigurationSection("przedmioty");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ItemStack item = y.getItemStack("przedmioty." + id + ".item");
                if (item == null || item.getType().isAir()) continue;
                offers.put(id, new ListingStore.LegacyOffer(encode(item), y.getLong("przedmioty." + id + ".cena"),
                        y.getString("przedmioty." + id + ".sprzedawca"), y.getString("przedmioty." + id + ".nick_sprzedawcy")));
            }
        }
        int n = store.importLegacy(offers, System.currentTimeMillis());
        changed();
        saver.zapiszTeraz();
        if (old.renameTo(new File(plugin.getDataFolder(), "rynek.yml.old"))) {
            plugin.getLogger().info("Imported " + n + " offers from rynek.yml (renamed to rynek.yml.old).");
        } else {
            plugin.getLogger().warning("Imported " + n + " offers from rynek.yml but could not rename it - delete it by hand, or the offers will be imported again.");
        }
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

    private static String money(long amount) {
        return MoneyFormat.pelna(amount);
    }

    // ---------- przedmioty <-> Base64 ----------

    static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static ItemStack decode(String b64) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
        } catch (Exception e) {
            return null;
        }
    }

    /** Daje przedmiot; co się nie zmieści - do skrzynki (albo pod nogi, gdy skrzynka wyłączona). */
    private void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        if (left.isEmpty()) return;
        for (ItemStack rest : left.values()) {
            if (settings.mailbox()) store.addMail(player.getUniqueId(), new MailItem(System.currentTimeMillis(), encode(rest)));
            else player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        if (settings.mailbox()) changed();
        send(player, settings.mailbox() ? "buy.to-mailbox" : "buy.dropped");
    }

    // ---------- wystawianie ----------

    public void sell(Player player, String priceArg) {
        long price;
        try {
            price = Long.parseLong(priceArg);
        } catch (NumberFormatException e) {
            price = -1;
        }
        if (price < settings.minPrice() || price > settings.maxPrice()) {
            send(player, "sell.bad-price", Map.of("min", money(settings.minPrice()), "max", money(settings.maxPrice())));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            send(player, "sell.empty-hand");
            return;
        }
        List<String> perms = new ArrayList<>();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (info.getValue()) perms.add(info.getPermission());
        }
        int limit = MarketRules.limitFor(settings.defaultLimit(), perms);
        if (store.countBy(player.getUniqueId()) >= limit) {
            send(player, "sell.limit", Map.of("limit", String.valueOf(limit)));
            return;
        }
        Listing listing = new Listing(UUID.randomUUID().toString(), player.getUniqueId(), player.getName(),
                price, System.currentTimeMillis(), encode(hand.clone()));
        store.add(listing);
        changed();
        player.getInventory().setItemInMainHand(null);
        send(player, "sell.listed", Map.of("price", money(price)));

        List<Listing> shown = visible(player);
        int index = shown.indexOf(listing);
        openMain(player, Math.max(0, index) / MarketSettings.OFFER_SLOTS.size(), lastFromMenu.getOrDefault(player.getUniqueId(), false));
    }

    // ---------- menu ----------

    private List<Listing> visible(Player player) {
        boolean mine = onlyMine.getOrDefault(player.getUniqueId(), false);
        List<Listing> out = new ArrayList<>();
        for (Listing l : store.listings()) {
            if (!mine || l.seller().equals(player.getUniqueId())) out.add(l);
        }
        Comparator<Listing> byPrice = Comparator.comparingLong(Listing::price);
        out.sort(sortAsc.getOrDefault(player.getUniqueId(), true) ? byPrice : byPrice.reversed());
        return out;
    }

    public void openMain(Player player, int page, boolean fromMenu) {
        lastFromMenu.put(player.getUniqueId(), fromMenu);
        List<Listing> list = visible(player);
        int per = MarketSettings.OFFER_SLOTS.size();
        int pages = Math.max(1, (list.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        boolean mine = onlyMine.getOrDefault(player.getUniqueId(), false);

        MarketGuiHolder holder = new MarketGuiHolder(MarketGuiHolder.Kind.MAIN, page, fromMenu);
        Map<String, String> ph = Map.of("page", String.valueOf(page + 1));
        Component title = !settings.title().isEmpty() ? SER.deserialize(settings.title().replace("{page}", String.valueOf(page + 1)))
                : lang.msg(plugin, mine ? "menu.title-mine" : "menu.title", ph);
        Inventory gui = holder.create(title);
        GuiUtils.fillBackground(gui, material(settings.background(), Material.GRAY_STAINED_GLASS_PANE));

        for (int i = 0; i < per && page * per + i < list.size(); i++) {
            Listing l = list.get(page * per + i);
            ItemStack shown = offerIcon(player, l);
            if (shown == null) continue;
            int slot = MarketSettings.OFFER_SLOTS.get(i);
            gui.setItem(slot, shown);
            holder.offers().put(slot, l.id());
        }

        if (page > 0) button(holder, gui, "prev", t("menu.prev"), List.of());
        if (page < pages - 1) button(holder, gui, "next", t("menu.next"), List.of());
        button(holder, gui, "search", t("menu.search"), List.of(t("menu.search-hint")));
        button(holder, gui, "mine", t(mine ? "menu.mine-on" : "menu.mine-off"), mine ? List.of(t("menu.mine-on-hint")) : List.of());
        boolean asc = sortAsc.getOrDefault(player.getUniqueId(), true);
        button(holder, gui, "sort", t(asc ? "menu.sort-asc" : "menu.sort-desc"), List.of(t("menu.sort-hint")));
        closeButton(holder, gui, fromMenu);
        if (settings.mailbox()) {
            int waiting = store.mailbox(player.getUniqueId()).size();
            button(holder, gui, "mailbox", t("menu.mailbox"), List.of(t("menu.mailbox-count", Map.of("count", String.valueOf(waiting)))));
        }
        player.openInventory(gui);
    }

    private void closeButton(MarketGuiHolder holder, Inventory gui, boolean fromMenu) {
        ButtonDef b = settings.buttons().get("close");
        if (b == null) return;
        Material m = fromMenu ? Material.NETHER_STAR : material(b.material(), Material.BARRIER);
        gui.setItem(b.slot(), GuiUtils.namedItem(m, t(fromMenu ? "menu.back-to-menu" : "menu.close")));
        holder.buttons().put(b.slot(), "close");
    }

    private void button(MarketGuiHolder holder, Inventory gui, String id, Component name, List<Component> lore) {
        ButtonDef b = settings.buttons().get(id);
        if (b == null) return;
        ItemStack item = GuiUtils.namedItem(material(b.material(), Material.STONE), name, lore.toArray(Component[]::new));
        gui.setItem(b.slot(), item);
        holder.buttons().put(b.slot(), id);
    }

    private ItemStack offerIcon(Player viewer, Listing l) {
        ItemStack item = decode(l.item());
        if (item == null) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(t("offer.price", Map.of("price", money(l.price()))));
        lore.add(t("offer.seller", Map.of("seller", l.sellerName())));
        if (settings.expireDays() > 0) {
            long left = Math.max(0, l.listedAt() + settings.expireDays() * 86_400_000L - System.currentTimeMillis());
            lore.add(t("offer.expires", Map.of("days", String.valueOf(left / 86_400_000L), "hours", String.valueOf(left / 3_600_000L % 24))));
        }
        boolean own = l.seller().equals(viewer.getUniqueId());
        lore.add(t(own ? "offer.yours" : "offer.buy"));
        if (own && l.id().equals(pendingRetract.get(viewer.getUniqueId()))) lore.add(t("offer.confirm-retract"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void openSearch(Player player, String query) {
        lastQuery.put(player.getUniqueId(), query);
        String q = query.toLowerCase(Locale.ROOT);
        List<Listing> hits = new ArrayList<>();
        for (Listing l : visible(player)) {
            ItemStack item = decode(l.item());
            if (item != null && matches(item, q)) hits.add(l);
        }
        if (hits.isEmpty()) {
            send(player, "search.none", Map.of("query", query));
            openMain(player, 0, lastFromMenu.getOrDefault(player.getUniqueId(), false));
            return;
        }
        int per = MarketSettings.OFFER_SLOTS.size();
        MarketGuiHolder holder = new MarketGuiHolder(MarketGuiHolder.Kind.SEARCH, 0, lastFromMenu.getOrDefault(player.getUniqueId(), false));
        Inventory gui = holder.create(lang.msg(plugin, "menu.search-title", Map.of("query", query)));
        GuiUtils.fillBackground(gui, material(settings.background(), Material.GRAY_STAINED_GLASS_PANE));
        for (int i = 0; i < per && i < hits.size(); i++) {
            ItemStack icon = offerIcon(player, hits.get(i));
            if (icon == null) continue;
            int slot = MarketSettings.OFFER_SLOTS.get(i);
            gui.setItem(slot, icon);
            holder.offers().put(slot, hits.get(i).id());
        }
        backButton(holder, gui);
        player.openInventory(gui);
        if (hits.size() > per) {
            send(player, "search.found-cut", Map.of("count", String.valueOf(hits.size()), "shown", String.valueOf(per)));
        } else {
            send(player, "search.found", Map.of("count", String.valueOf(hits.size())));
        }
    }

    private void backButton(MarketGuiHolder holder, Inventory gui) {
        ButtonDef b = settings.buttons().get("close");
        int slot = b != null ? b.slot() : 49;
        gui.setItem(slot, GuiUtils.namedItem(Material.ARROW, t("menu.back")));
        holder.buttons().put(slot, "back");
    }

    private static boolean matches(ItemStack item, String q) {
        if (item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ').contains(q)) return true;
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT).contains(q);
        }
        return false;
    }

    private void openMailbox(Player player) {
        MarketGuiHolder holder = new MarketGuiHolder(MarketGuiHolder.Kind.MAILBOX, 0, lastFromMenu.getOrDefault(player.getUniqueId(), false));
        Inventory gui = holder.create(lang.msg(plugin, "menu.mailbox-title"));
        GuiUtils.fillBackground(gui, material(settings.background(), Material.GRAY_STAINED_GLASS_PANE));
        List<MailItem> mail = store.mailbox(player.getUniqueId());
        for (int i = 0; i < MarketSettings.OFFER_SLOTS.size() && i < mail.size(); i++) {
            ItemStack item = decode(mail.get(i).item());
            if (item == null) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(t("menu.mailbox-item-hint"));
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            int slot = MarketSettings.OFFER_SLOTS.get(i);
            gui.setItem(slot, item);
            holder.mail().put(slot, i);
        }
        if (mail.isEmpty()) send(player, "mailbox.empty");
        backButton(holder, gui);
        player.openInventory(gui);
    }

    // ---------- kliknięcia ----------

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder(false) instanceof MarketGuiHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof MarketGuiHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) return;
        int slot = event.getRawSlot();

        String button = holder.buttons().get(slot);
        if (button != null) {
            clickButton(player, holder, button);
            return;
        }
        if (holder.kind() == MarketGuiHolder.Kind.MAILBOX) {
            Integer index = holder.mail().get(slot);
            if (index != null) collect(player, index);
            return;
        }
        String offerId = holder.offers().get(slot);
        if (offerId == null) return;
        Runnable refresh = holder.kind() == MarketGuiHolder.Kind.SEARCH
                ? () -> openSearch(player, lastQuery.getOrDefault(player.getUniqueId(), ""))
                : () -> openMain(player, holder.page(), holder.fromMenu());
        clickOffer(player, offerId, refresh);
    }

    private void clickButton(Player player, MarketGuiHolder holder, String button) {
        UUID id = player.getUniqueId();
        switch (button) {
            case "prev" -> openMain(player, holder.page() - 1, holder.fromMenu());
            case "next" -> openMain(player, holder.page() + 1, holder.fromMenu());
            case "mine" -> {
                onlyMine.put(id, !onlyMine.getOrDefault(id, false));
                openMain(player, 0, holder.fromMenu());
            }
            case "sort" -> {
                sortAsc.put(id, !sortAsc.getOrDefault(id, true));
                openMain(player, 0, holder.fromMenu());
            }
            case "search" -> {
                awaitingSearch.add(id);
                player.closeInventory();
                send(player, "search.prompt");
                send(player, "search.cancel-hint");
            }
            case "mailbox" -> openMailbox(player);
            case "back" -> openMain(player, 0, holder.fromMenu());
            case "close" -> {
                if (holder.fromMenu()) MenuBridge.returnToMainMenu(player);
                else player.closeInventory();
            }
            default -> { }
        }
    }

    private void clickOffer(Player player, String offerId, Runnable refresh) {
        Listing l = store.get(offerId);
        if (l == null) {
            send(player, "buy.gone");
            refresh.run();
            return;
        }
        UUID id = player.getUniqueId();
        if (l.seller().equals(id)) {
            if (offerId.equals(pendingRetract.get(id))) {
                pendingRetract.remove(id);
                store.remove(offerId);
                changed();
                ItemStack item = decode(l.item());
                if (item != null) give(player, item);
                send(player, "retract.done");
            } else {
                pendingRetract.put(id, offerId);
                Bukkit.getScheduler().runTaskLater(plugin, () -> pendingRetract.remove(id, offerId), RETRACT_TIMEOUT_TICKS);
            }
            refresh.run();
            return;
        }
        if (!economy.maWystarczajaco(id, l.price())) {
            send(player, "buy.no-money");
            return;
        }
        ItemStack item = decode(l.item());
        if (item == null) {
            plugin.getLogger().warning("listings.yml: offer " + offerId + " has a broken item - removing it.");
            store.remove(offerId);
            changed();
            refresh.run();
            return;
        }
        store.remove(offerId);
        economy.odejmijKase(id, l.price());
        long payout = MarketRules.payout(l.price(), settings.taxPercent());
        economy.dodajKase(l.seller(), payout);
        Player seller = Bukkit.getPlayer(l.seller());
        if (seller != null && seller.isOnline()) {
            send(seller, "buy.sold", Map.of("price", money(l.price()), "payout", money(payout)));
        } else {
            store.addEarning(l.seller(), payout, 1);
        }
        changed();
        give(player, item);
        send(player, "buy.bought", Map.of("price", money(l.price())));
        refresh.run();
    }

    private void collect(Player player, int index) {
        List<MailItem> mail = store.mailbox(player.getUniqueId());
        if (index >= mail.size()) {
            openMailbox(player);
            return;
        }
        ItemStack item = decode(mail.get(index).item());
        if (item != null) {
            if (!fits(player, item)) {
                send(player, "mailbox.full");
                return;
            }
            player.getInventory().addItem(item);
        }
        store.takeMail(player.getUniqueId(), index);
        changed();
        openMailbox(player);
    }

    // ---------- wyszukiwarka ----------

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

    // ---------- wygasanie, wejście, wyjście ----------

    private void expireOffers() {
        List<Listing> expired = store.expired(System.currentTimeMillis(), settings.expireDays());
        if (expired.isEmpty()) return;
        for (Listing l : expired) {
            store.remove(l.id());
            Player seller = Bukkit.getPlayer(l.seller());
            if (settings.mailbox()) {
                store.addMail(l.seller(), new MailItem(System.currentTimeMillis(), l.item()));
                if (seller != null) send(seller, "expire.to-mailbox");
            } else if (seller != null) {
                ItemStack item = decode(l.item());
                if (item != null) {
                    for (ItemStack rest : seller.getInventory().addItem(item).values()) {
                        seller.getWorld().dropItemNaturally(seller.getLocation(), rest);
                    }
                }
                send(seller, "expire.returned");
            } else {
                store.addPendingReturn(l.seller(), l.item());
            }
        }
        changed();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long[] earned = store.takeEarnings(id);
        if (earned != null) {
            changed();
            send(player, "join.earnings", Map.of("count", String.valueOf(earned[1]), "amount", money(earned[0])));
        }
        List<String> returns = store.takePendingReturns(id);
        if (!returns.isEmpty()) {
            changed();
            for (String b64 : returns) {
                ItemStack item = decode(b64);
                if (item == null) continue;
                for (ItemStack rest : player.getInventory().addItem(item).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), rest);
                }
            }
            send(player, "join.returned");
        }
        int waiting = settings.mailbox() ? store.mailbox(id).size() : 0;
        if (waiting > 0) send(player, "mailbox.waiting", Map.of("count", String.valueOf(waiting)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        onlyMine.remove(id);
        sortAsc.remove(id);
        awaitingSearch.remove(id);
        lastQuery.remove(id);
        lastFromMenu.remove(id);
        pendingRetract.remove(id);
    }

    // ---------- admin ----------

    /** Zdejmuje wszystkie oferty gracza: do skrzynki, a przy wyłączonej - do ekwipunku (online) albo zwrotów. */
    public int removeAll(UUID seller) {
        int n = 0;
        Player online = Bukkit.getPlayer(seller);
        for (Listing l : store.listings()) {
            if (!l.seller().equals(seller)) continue;
            store.remove(l.id());
            if (settings.mailbox()) {
                store.addMail(seller, new MailItem(System.currentTimeMillis(), l.item()));
            } else if (online != null) {
                ItemStack item = decode(l.item());
                if (item != null) {
                    for (ItemStack rest : online.getInventory().addItem(item).values()) {
                        online.getWorld().dropItemNaturally(online.getLocation(), rest);
                    }
                }
            } else {
                store.addPendingReturn(seller, l.item());
            }
            n++;
        }
        if (n > 0) changed();
        return n;
    }

    /** Nazwa przedmiotu oferty do czatu (własna nazwa albo nazwa materiału). */
    static String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        return item.getAmount() + "x " + item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m != null ? m : fallback;
    }

    /** Czy cały stos zmieści się w ekwipunku (wolne pola + dopełnienie takich samych stosów). */
    private static boolean fits(Player player, ItemStack item) {
        int room = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType().isAir()) room += item.getMaxStackSize();
            else if (slot.isSimilar(item)) room += Math.max(0, slot.getMaxStackSize() - slot.getAmount());
            if (room >= item.getAmount()) return true;
        }
        return false;
    }
}
