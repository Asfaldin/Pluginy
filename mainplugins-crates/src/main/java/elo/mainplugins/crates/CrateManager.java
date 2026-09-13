package elo.mainplugins.crates;

import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.RewardService;
import elo.mainplugins.core.api.ServerAnnounceEvent;
import elo.mainplugins.crates.model.CrateConfig;
import elo.mainplugins.crates.model.CrateDef;
import elo.mainplugins.crates.model.KeyDef;
import elo.mainplugins.crates.model.Prize;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Skrzynki z crates.yml: PPM skrzynką w ręce (z pasującym kluczem w ekwipunku) = animacja
 * "ruletki" jak w CS i wypłata przez RewardService; LPM = podgląd wygranych z szansami.
 * Implementuje też CrateService dla innych pluginów (stare metody po tierze działają dalej).
 */
public class CrateManager implements Listener, CrateService {

    // Opóźnienia (ticki) między klatkami - rosnące = zwalnianie ruletki pod koniec.
    private static final int[] OPOZNIENIA_TICK = {
            1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 11, 13, 15, 18, 21, 25
    };
    private static final int SZEROKOSC_OKNA = 9;
    private static final int WIERSZ_ANIMACJI = 9; // pierwszy slot środkowego rzędu w 27-slotowym GUI

    private final Plugin plugin;
    private final LangService lang;
    private final RewardService rewards;
    private final CrateItems crateItems;
    private final Set<UUID> otwierajacy = new HashSet<>();
    private CrateConfig config = new CrateConfig(Map.of(), Map.of());

    public CrateManager(Plugin plugin, LangService lang, RewardService rewards, CustomItemService items) {
        this.plugin = plugin;
        this.lang = lang;
        this.rewards = rewards;
        this.crateItems = new CrateItems(plugin, items);
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "crates.yml");
        if (!file.exists()) plugin.saveResource("crates.yml", false);
        for (String old : List.of("crate-rewards.yml", "crate-rewards-2.yml", "crate-rewards-3.yml")) {
            if (new File(plugin.getDataFolder(), old).exists()) {
                plugin.getLogger().warning(old + " is no longer read - crates now live in crates.yml.");
            }
        }
        config = CrateConfigParser.parse(YamlConfiguration.loadConfiguration(file), rewards::parse,
                name -> Material.matchMaterial(name) != null, plugin.getLogger()::warning);
        plugin.getLogger().info("Loaded " + config.crates().size() + " crates and " + config.keys().size() + " keys.");
    }

    public CrateConfig config() {
        return config;
    }

    // ---- CrateService ----

    @Override
    public ItemStack stworzSkrzynke(int tier) {
        String id = CrateOdds.legacyCrateId(tier, config.crateIdsInOrder());
        ItemStack item = id != null ? createCrate(id, 1) : null;
        return item != null ? item : new ItemStack(Material.PAPER);
    }

    @Override
    public ItemStack stworzKlucz() {
        ItemStack item = createKey("universal_key", 1);
        if (item != null) return item;
        List<String> ids = config.crateIdsInOrder();
        item = ids.isEmpty() ? null : createKey(config.crates().get(ids.getFirst()).keys().getFirst(), 1);
        return item != null ? item : new ItemStack(Material.PAPER);
    }

    @Override
    public ItemStack createCrate(String id, int amount) {
        CrateDef c = config.crates().get(id);
        return c == null ? null : crateItems.crate(c, Math.max(1, amount));
    }

    @Override
    public ItemStack createKey(String id, int amount) {
        KeyDef k = config.keys().get(id);
        return k == null ? null : crateItems.key(k, Math.max(1, amount));
    }

    @Override
    public Set<String> crateIds() {
        return new LinkedHashSet<>(config.crates().keySet());
    }

    @Override
    public Set<String> keyIds() {
        return new LinkedHashSet<>(config.keys().keySet());
    }

    // ---- Gracz ----

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        boolean prawy = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        boolean lewy = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        if (!prawy && !lewy) return;

        Player player = event.getPlayer();
        ItemStack wRece = player.getInventory().getItemInMainHand();
        String crateId = crateItems.crateIdOf(wRece, config);
        CrateDef crate = crateId != null ? config.crates().get(crateId) : null;
        if (crate == null) return;
        // Zawsze anulujemy - skrzynka-blok w ręku nie może się postawić ani niszczyć bloków.
        event.setCancelled(true);

        if (lewy) {
            otworzPodglad(player, crate);
            return;
        }
        if (otwierajacy.contains(player.getUniqueId())) {
            lang.send(player, plugin, "crate.already-opening");
            return;
        }
        int slotKlucza = znajdzSlotKlucza(player, crate);
        if (slotKlucza == -1) {
            String klucze = crate.keys().stream().map(k -> config.keys().get(k).name()).collect(Collectors.joining("&7, "));
            lang.send(player, plugin, "crate.need-key", Map.of("keys", klucze));
            return;
        }
        zmniejsz(player, player.getInventory().getHeldItemSlot());
        zmniejsz(player, slotKlucza);
        rozpocznijAnimacje(player, crate);
    }

    private int znajdzSlotKlucza(Player player, CrateDef crate) {
        ItemStack[] zawartosc = player.getInventory().getContents();
        for (int i = 0; i < zawartosc.length; i++) {
            String keyId = crateItems.keyIdOf(zawartosc[i]);
            if (keyId != null && crate.keys().contains(keyId)) return i;
        }
        return -1;
    }

    private void zmniejsz(Player player, int slot) {
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null) return;
        if (item.getAmount() <= 1) player.getInventory().setItem(slot, null);
        else item.setAmount(item.getAmount() - 1);
    }

    private void otworzPodglad(Player player, CrateDef crate) {
        int ile = Math.min(54, crate.prizes().size());
        int rozmiar = Math.max(9, ((ile + 8) / 9) * 9);
        Inventory gui = new CrateGuiHolder().create(rozmiar,
                lang.msg(plugin, "crate.preview-title", Map.of("crate", crate.name())));
        for (int i = 0; i < ile; i++) {
            Prize p = crate.prizes().get(i);
            String szansa = CrateOdds.formatChance(CrateOdds.chancePercent(crate, p));
            gui.setItem(i, crateItems.icon(p, List.of(lang.msg(plugin, "crate.preview-chance", Map.of("chance", szansa)))));
        }
        player.openInventory(gui);
    }

    private void rozpocznijAnimacje(Player player, CrateDef crate) {
        otwierajacy.add(player.getUniqueId());
        Prize wygrana = CrateOdds.pick(crate, n -> ThreadLocalRandom.current().nextInt(n));

        // Pasek przewijanych ikon; wygrana ląduje na środkowym slocie w ostatniej klatce.
        int liczbaKlatek = OPOZNIENIA_TICK.length;
        List<ItemStack> pasek = new ArrayList<>(liczbaKlatek + SZEROKOSC_OKNA);
        for (int i = 0; i < liczbaKlatek + SZEROKOSC_OKNA; i++) {
            Prize los = CrateOdds.pick(crate, n -> ThreadLocalRandom.current().nextInt(n));
            pasek.add(crateItems.icon(los, List.of()));
        }
        pasek.set((liczbaKlatek - 1) + 4, crateItems.icon(wygrana, List.of()));

        Inventory gui = new CrateGuiHolder().create(27, lang.msg(plugin, "crate.opening-title", Map.of("crate", crate.name())));
        ItemStack tlo = szyba(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < gui.getSize(); i++) gui.setItem(i, tlo);
        gui.setItem(4, szyba(Material.YELLOW_STAINED_GLASS_PANE, "&e&l▼"));
        gui.setItem(22, szyba(Material.YELLOW_STAINED_GLASS_PANE, "&e&l▲"));
        player.openInventory(gui);
        animujKlatke(player, gui, pasek, 0, crate, wygrana);
    }

    private void animujKlatke(Player player, Inventory gui, List<ItemStack> pasek, int krok, CrateDef crate, Prize wygrana) {
        if (!player.isOnline()) {
            otwierajacy.remove(player.getUniqueId());
            return;
        }
        for (int i = 0; i < SZEROKOSC_OKNA; i++) gui.setItem(WIERSZ_ANIMACJI + i, pasek.get(krok + i));
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
        if (krok < OPOZNIENIA_TICK.length - 1) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> animujKlatke(player, gui, pasek, krok + 1, crate, wygrana), OPOZNIENIA_TICK[krok]);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> zakoncz(player, crate, wygrana), 30L);
        }
    }

    private void zakoncz(Player player, CrateDef crate, Prize wygrana) {
        otwierajacy.remove(player.getUniqueId());
        if (!player.isOnline()) return;
        player.closeInventory();
        rewards.give(player, wygrana.rewards());
        lang.send(player, plugin, "crate.won", Map.of("prize", wygrana.name()));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        if (wygrana.announce()) {
            String plainPrize = PlainTextComponentSerializer.plainText().serialize(CrateItems.text(wygrana.name()));
            String plainCrate = PlainTextComponentSerializer.plainText().serialize(CrateItems.text(crate.name()));
            // Most do mainplugins-announcer (events.crate-legendary); bez niego - wbudowane ogłoszenie.
            Bukkit.getPluginManager().callEvent(new ServerAnnounceEvent("crate-legendary", player,
                    Map.of("reward", plainPrize, "crate", plainCrate)));
            if (Bukkit.getPluginManager().getPlugin("MainpluginsAnnouncer") == null) {
                Bukkit.broadcast(lang.msg(plugin, "crate.announce",
                        Map.of("player", player.getName(), "prize", wygrana.name(), "crate", crate.name())));
            }
        }
    }

    private static ItemStack szyba(Material m, String nazwa) {
        ItemStack item = new ItemStack(m);
        item.setData(DataComponentTypes.CUSTOM_NAME, CrateItems.text(nazwa));
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof CrateGuiHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CrateGuiHolder) event.setCancelled(true);
    }
}
