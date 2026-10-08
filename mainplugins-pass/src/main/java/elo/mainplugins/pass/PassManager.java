package elo.mainplugins.pass;

import elo.mainplugins.core.api.ItemNameService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.Reward;
import elo.mainplugins.core.api.RewardService;
import elo.mainplugins.pass.model.PassConfig;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Przepustka sezonowa: XP za logowanie, czas gry, moby i głosy; poziomy z nagrodą darmową
 * i premium (premium = permisja, którą właściciel serwera sprzedaje). Do tego nagrody dzienne
 * (seria dni) i nagrody za głosowanie. Dzienne i głosy działają zawsze; przepustka - z planem.
 */
public final class PassManager implements Listener {

    private static final LegacyComponentSerializer SER = LegacyComponentSerializer.legacyAmpersand();
    /** Ile poziomów mieści jedna strona okna przepustki. */
    static final int PER_PAGE = 7;

    private final JavaPlugin plugin;
    private final LangService lang;
    private final RewardService rewards;
    private final ItemNameService names;
    private final PlayerStore store;
    private PassConfig config;
    /** Przepustka sezonowa odblokowana planem (licencja "pass"). */
    private boolean passUnlocked;

    PassManager(JavaPlugin plugin, LangService lang, RewardService rewards, ItemNameService names) {
        this.plugin = plugin;
        this.lang = lang;
        this.rewards = rewards;
        this.names = names;
        this.store = new PlayerStore(new File(plugin.getDataFolder(), "players.yml"), plugin.getLogger()::warning);
    }

    // ---- konfiguracja ----

    void reload() {
        File file = new File(plugin.getDataFolder(), "pass.yml");
        if (!file.exists()) copyDefaults(file);
        config = PassConfigParser.parse(YamlConfiguration.loadConfiguration(file), rewards::parse, plugin.getLogger()::warning);
        store.load();
        plugin.getLogger().info("Season '" + config.season().id() + "': " + config.levels().size() + " levels, "
                + config.daily().days().size() + " daily rewards.");
    }

    /** Pierwszy start: domyślna przepustka w języku serwera (defaults/<język>/pass.yml), brak = angielska. */
    private void copyDefaults(File file) {
        String resource = "defaults/" + lang.language() + "/pass.yml";
        if (plugin.getResource(resource) == null) resource = "defaults/en/pass.yml";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) return;
            file.getParentFile().mkdirs();
            Files.copy(in, file.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write pass.yml: " + e.getMessage());
        }
    }

    PassConfig config() {
        return config;
    }

    void setPassUnlocked(boolean unlocked) {
        this.passUnlocked = unlocked;
    }

    boolean passUnlocked() {
        return passUnlocked;
    }

    void save() {
        store.save();
    }

    // ---- dane gracza ----

    /** Dane gracza z bieżącego sezonu - nowy sezon (inne id) zeruje XP i odebrane poziomy. */
    PlayerStore.Data data(UUID id) {
        PlayerStore.Data d = store.get(id);
        if (!config.season().id().equals(d.season)) {
            d.season = config.season().id();
            d.xp = 0;
            d.claimedFree.clear();
            d.claimedPremium.clear();
            store.markDirty();
        }
        return d;
    }

    int level(PlayerStore.Data d) {
        return PassMath.level(d.xp, config.season().xpPerLevel(), config.maxLevel());
    }

    boolean seasonEnded() {
        return config.season().ends() != null && LocalDate.now().isAfter(config.season().ends());
    }

    boolean premium(Player p) {
        return p.hasPermission(config.premiumPermission());
    }

    /** Dodaje XP przepustki (tylko z planem i w trakcie sezonu); komunikat przy nowym poziomie. */
    void addXp(Player p, int amount) {
        if (!passUnlocked || amount <= 0 || seasonEnded() || config.maxLevel() == 0) return;
        PlayerStore.Data d = data(p.getUniqueId());
        int before = level(d);
        d.xp += amount;
        store.markDirty();
        int after = level(d);
        if (after > before) {
            lang.send(p, plugin, "pass.level-up", Map.of("level", String.valueOf(after)));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        }
    }

    // ---- źródła XP ----

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        PlayerStore.Data d = data(p.getUniqueId());
        if (d.pendingVotes > 0) {
            int n = d.pendingVotes;
            d.pendingVotes = 0;
            store.markDirty();
            for (int i = 0; i < n; i++) giveVote(p);
        }
        if (config.daily().enabled() && !config.daily().days().isEmpty()) {
            PassMath.Daily daily = dailyState(d);
            if (daily.canClaim()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline()) lang.send(p, plugin, "daily.available");
                }, 40L);
            }
        }
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer != null && !(e.getEntity() instanceof Player)) addXp(killer, config.xp().mobKill());
    }

    /** Wołane co minutę: XP za czas gry co playtime-minutes. */
    void tickPlaytime() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerStore.Data d = data(p.getUniqueId());
            d.minutes++;
            store.markDirty();
            if (d.minutes >= config.xp().playtimeMinutes()) {
                d.minutes = 0;
                addXp(p, config.xp().playtimeXp());
            }
        }
    }

    // ---- nagrody dzienne ----

    PassMath.Daily dailyState(PlayerStore.Data d) {
        return PassMath.daily(d.lastDaily, d.streak, LocalDate.now(), config.daily().resetIfMissed(), config.daily().days().size());
    }

    void claimDaily(Player p) {
        if (!config.daily().enabled() || config.daily().days().isEmpty()) return;
        PlayerStore.Data d = data(p.getUniqueId());
        PassMath.Daily st = dailyState(d);
        if (!st.canClaim()) {
            lang.send(p, plugin, "daily.already");
            return;
        }
        d.streak = st.streakAfter();
        d.lastDaily = LocalDate.now();
        store.markDirty();
        rewards.give(p, config.daily().days().get(st.dayIndex()));
        lang.send(p, plugin, "daily.claimed-ok", Map.of("day", String.valueOf(st.dayIndex() + 1), "streak", String.valueOf(d.streak)));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
        addXp(p, config.xp().dailyLogin());
    }

    // ---- głosowanie ----

    /** Głos z serwisu (komenda /@pass vote, wołana np. przez plugin od głosowania). Offline = nagroda przy wejściu. */
    boolean vote(String name) {
        if (!config.vote().enabled()) return false;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            giveVote(online);
            return true;
        }
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off == null) return false;
        data(off.getUniqueId()).pendingVotes++;
        store.markDirty();
        return true;
    }

    private void giveVote(Player p) {
        rewards.give(p, config.vote().rewards());
        lang.send(p, plugin, "vote.thanks");
        addXp(p, config.xp().vote());
    }

    // ---- odbieranie poziomów ----

    void claimLevel(Player p, int level, boolean premiumTrack) {
        PassConfig.LevelDef def = config.level(level);
        if (def == null || !passUnlocked) return;
        PlayerStore.Data d = data(p.getUniqueId());
        if (level(d) < level) {
            lang.send(p, plugin, "pass.locked-level", Map.of("level", String.valueOf(level)));
            return;
        }
        if (premiumTrack && !premium(p)) {
            lang.send(p, plugin, "pass.need-premium");
            return;
        }
        var claimed = premiumTrack ? d.claimedPremium : d.claimedFree;
        List<Reward> list = premiumTrack ? def.premium() : def.free();
        if (list.isEmpty() || claimed.contains(level)) return;
        claimed.add(level);
        store.markDirty();
        rewards.give(p, list);
        lang.send(p, plugin, "pass.reward-claimed", Map.of("level", String.valueOf(level)));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
    }

    // ---- okna ----

    static final class PassGui implements InventoryHolder {
        Inventory inventory;
        int page;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    static final class DailyGui implements InventoryHolder {
        Inventory inventory;
        /** Slot -> czy to dzisiejsza nagroda do odebrania. */
        int todaySlot = -1;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    void openPass(Player p, int page) {
        PassGui gui = new PassGui();
        gui.inventory = Bukkit.createInventory(gui, 54, lang.msg(plugin, "pass.title", Map.of("season", plain(config.season().name()))));
        int pages = Math.max(1, (config.levels().size() + PER_PAGE - 1) / PER_PAGE);
        gui.page = Math.max(0, Math.min(page, pages - 1));
        render(p, gui, pages);
        p.openInventory(gui.inventory);
    }

    private void render(Player p, PassGui gui, int pages) {
        Inventory inv = gui.inventory;
        inv.clear();
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        PlayerStore.Data d = data(p.getUniqueId());
        int lvl = level(d);
        int perLevel = config.season().xpPerLevel();
        int prog = PassMath.progress(d.xp, perLevel, config.maxLevel());

        List<Component> info = new ArrayList<>();
        info.add(lang.msg(plugin, "pass.info-level", Map.of("level", String.valueOf(lvl), "max", String.valueOf(config.maxLevel()))));
        info.add(lang.msg(plugin, "pass.info-xp", Map.of("bar", PassMath.bar(prog, perLevel, 10), "xp", String.valueOf(prog), "need", String.valueOf(perLevel))));
        if (config.season().ends() != null) {
            info.add(lang.msg(plugin, seasonEnded() ? "pass.info-ended" : "pass.info-ends", Map.of("date", config.season().ends().toString())));
        }
        if (!passUnlocked) info.add(lang.msg(plugin, "pass.locked-plan"));
        inv.setItem(4, item(Material.NETHER_STAR, legacy(config.season().name()), info, true));

        inv.setItem(0, item(Material.CLOCK, lang.msg(plugin, "pass.daily-button"), List.of(lang.msg(plugin, "pass.daily-button-lore"))));
        inv.setItem(8, item(premium(p) ? Material.GOLD_INGOT : Material.IRON_BARS,
                lang.msg(plugin, premium(p) ? "pass.premium-on" : "pass.premium-off"), List.of()));

        inv.setItem(9, item(Material.LIME_STAINED_GLASS_PANE, lang.msg(plugin, "pass.free-row"), List.of()));
        inv.setItem(18, item(Material.WHITE_STAINED_GLASS_PANE, lang.msg(plugin, "pass.level-row"), List.of()));
        inv.setItem(27, item(Material.YELLOW_STAINED_GLASS_PANE, lang.msg(plugin, "pass.premium-row"), List.of()));

        int start = gui.page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < config.levels().size(); i++) {
            PassConfig.LevelDef def = config.levels().get(start + i);
            boolean reached = lvl >= def.level();
            inv.setItem(10 + i, rewardIcon(def.free(), def.level(), reached, d.claimedFree.contains(def.level()), true, false));
            inv.setItem(28 + i, rewardIcon(def.premium(), def.level(), reached, d.claimedPremium.contains(def.level()), premium(p), true));
            Material pane = reached ? Material.LIME_STAINED_GLASS_PANE : def.level() == lvl + 1 ? Material.ORANGE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            ItemStack marker = item(pane, lang.msg(plugin, "pass.level-name", Map.of("level", String.valueOf(def.level()))), List.of());
            marker.setAmount(Math.max(1, Math.min(64, def.level())));
            inv.setItem(19 + i, marker);
        }

        if (gui.page > 0) inv.setItem(45, item(Material.ARROW, lang.msg(plugin, "pass.prev"), List.of()));
        inv.setItem(49, item(Material.PAPER, lang.msg(plugin, "pass.page", Map.of("page", String.valueOf(gui.page + 1), "pages", String.valueOf(pages))), List.of()));
        if (gui.page < pages - 1) inv.setItem(53, item(Material.ARROW, lang.msg(plugin, "pass.next"), List.of()));
    }

    /** Ikona nagrody poziomu: przedmiot z pierwszej nagrody, opis wszystkich i stan (do odebrania / odebrane / zablokowane). */
    private ItemStack rewardIcon(List<Reward> list, int level, boolean reached, boolean claimed, boolean allowed, boolean premiumTrack) {
        if (list.isEmpty()) return item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, lang.msg(plugin, "pass.no-rewards"), List.of());
        List<Component> lore = new ArrayList<>();
        for (Reward r : list) lore.add(describe(r));
        lore.add(Component.empty());
        String state;
        if (!passUnlocked) state = "pass.locked-plan";
        else if (claimed) state = "pass.claimed";
        else if (!reached) state = "pass.locked-level-lore";
        else if (!allowed) state = "pass.need-premium";
        else state = "pass.claim";
        lore.add(lang.msg(plugin, state, Map.of("level", String.valueOf(level))));
        Material mat = claimed ? Material.LIME_DYE : iconOf(list.get(0), premiumTrack);
        boolean glow = passUnlocked && reached && !claimed && allowed;
        return item(mat, lang.msg(plugin, premiumTrack ? "pass.premium-reward" : "pass.free-reward", Map.of("level", String.valueOf(level))), lore, glow);
    }

    private Material iconOf(Reward r, boolean premiumTrack) {
        if (RewardService.ITEM.equals(r.type())) {
            Material m = Material.matchMaterial(String.valueOf(r.value()));
            if (m != null && m.isItem()) return m;
        }
        return switch (r.type()) {
            case RewardService.MONEY -> Material.GOLD_NUGGET;
            case "key" -> Material.TRIPWIRE_HOOK;
            case "crate" -> Material.CHEST;
            default -> premiumTrack ? Material.GOLD_BLOCK : Material.CHEST_MINECART;
        };
    }

    private Component describe(Reward r) {
        String amount = String.valueOf(Math.max(1, r.amount()));
        return switch (r.type()) {
            case RewardService.MONEY -> lang.msg(plugin, "reward.money", Map.of("amount", String.valueOf(r.value())));
            case RewardService.ITEM -> {
                Material m = Material.matchMaterial(String.valueOf(r.value()));
                yield lang.msg(plugin, "reward.item", Map.of("amount", amount, "item", m != null ? names.name(m) : String.valueOf(r.value())));
            }
            case "key" -> lang.msg(plugin, "reward.key", Map.of("amount", amount, "key", String.valueOf(r.value())));
            case "crate" -> lang.msg(plugin, "reward.crate", Map.of("amount", amount, "crate", String.valueOf(r.value())));
            case RewardService.CUSTOM -> lang.msg(plugin, "reward.item", Map.of("amount", amount, "item", String.valueOf(r.value())));
            default -> lang.msg(plugin, "reward.special");
        };
    }

    void openDaily(Player p) {
        DailyGui gui = new DailyGui();
        gui.inventory = Bukkit.createInventory(gui, 27, lang.msg(plugin, "daily.title"));
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < 27; i++) gui.inventory.setItem(i, filler);
        List<List<Reward>> days = config.daily().days();
        PlayerStore.Data d = data(p.getUniqueId());
        PassMath.Daily st = dailyState(d);
        if (days.isEmpty() || !config.daily().enabled()) {
            gui.inventory.setItem(13, item(Material.BARRIER, lang.msg(plugin, "daily.disabled"), List.of()));
        } else {
            // Okno 7 dni wokół bieżącego dnia cyklu.
            int window = (st.dayIndex() / 7) * 7;
            for (int i = 0; i < 7 && window + i < days.size(); i++) {
                int day = window + i;
                List<Component> lore = new ArrayList<>();
                for (Reward r : days.get(day)) lore.add(describe(r));
                lore.add(Component.empty());
                boolean today = day == st.dayIndex();
                boolean done = !st.canClaim() ? day <= st.dayIndex() : day < st.dayIndex();
                String state = today && st.canClaim() ? "daily.today" : done ? "daily.claimed" : "daily.locked";
                lore.add(lang.msg(plugin, state));
                Material mat = done && !(today && st.canClaim()) ? Material.LIME_DYE : today ? Material.CHEST : Material.ENDER_CHEST;
                int slot = 10 + i;
                gui.inventory.setItem(slot, item(mat, lang.msg(plugin, "daily.day", Map.of("day", String.valueOf(day + 1))), lore, today && st.canClaim()));
                if (today && st.canClaim()) gui.todaySlot = slot;
            }
            gui.inventory.setItem(22, item(Material.CLOCK, lang.msg(plugin, "daily.streak", Map.of("streak", String.valueOf(d.streak))), List.of()));
        }
        p.openInventory(gui.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder(false);
        if (!(holder instanceof PassGui) && !(holder instanceof DailyGui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (holder instanceof DailyGui daily) {
            if (slot == daily.todaySlot) {
                claimDaily(p);
                openDaily(p);
            }
            return;
        }
        PassGui gui = (PassGui) holder;
        int pages = Math.max(1, (config.levels().size() + PER_PAGE - 1) / PER_PAGE);
        if (slot == 45 && gui.page > 0) openPass(p, gui.page - 1);
        else if (slot == 53 && gui.page < pages - 1) openPass(p, gui.page + 1);
        else if (slot == 0) openDaily(p);
        else if ((slot >= 10 && slot <= 16) || (slot >= 28 && slot <= 34)) {
            boolean premiumTrack = slot >= 28;
            int idx = gui.page * PER_PAGE + (slot - (premiumTrack ? 28 : 10));
            if (idx < config.levels().size()) {
                claimLevel(p, config.levels().get(idx).level(), premiumTrack);
                render(p, gui, pages);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getInventory().getHolder(false);
        if (holder instanceof PassGui || holder instanceof DailyGui) e.setCancelled(true);
    }

    // ---- pomocnicze ----

    private static Component legacy(String text) {
        return SER.deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    private static String plain(String legacyText) {
        return legacyText.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    private static ItemStack item(Material mat, Component name, List<Component> lore) {
        return item(mat, name, lore, false);
    }

    private static ItemStack item(Material mat, Component name, List<Component> lore, boolean glow) {
        ItemStack it = new ItemStack(mat);
        it.setData(DataComponentTypes.CUSTOM_NAME, name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (Component c : lore) lines.add(c.decoration(TextDecoration.ITALIC, false));
            it.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        }
        if (glow) it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return it;
    }
}
