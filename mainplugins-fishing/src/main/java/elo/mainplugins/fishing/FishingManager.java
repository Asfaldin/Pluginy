package elo.mainplugins.fishing;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.api.CustomItemService;
import elo.mainplugins.core.api.ObszarService;
import elo.mainplugins.core.util.CustomItemKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Łowienie - wersja 1 (celowo uproszczona, patrz niżej). Jedna zwykła wędka (/wedka,
 * bez tierów, bez upgrade'u w kowadle) - wanilijski hak działa normalnie wszędzie,
 * ale gdy przychodzi BITE (branie) W OBRĘBIE obszaru oznaczonego jako łowisko (patrz
 * ObszarService, flaga ryby-dozwolone ustawiana /@obszar ryby w mainplugins-spawn),
 * przejmujemy go: usuwamy hak i odpalamy własną minigrę "pasek" w stylu Stardew Valley
 * (FishingMinigame), a nagrodą jest jeden z własnych gatunków ryb zamiast wanilijskiego
 * looty. POZA łowiskiem plugin się w ogóle nie wtrąca - zwykłe wanilijskie łowienie.
 *
 * Świadomie wycięte na razie (wraca później jako osobny etap, patrz historia gita):
 * tiery wędek, łowienie w powietrzu/nad pustką, upgrade wędki w kowadle (receptury).
 *
 * Zero zależności od mainplugins-quests/mainplugins-shop: gatunki ryb są rozpoznawane
 * wyłącznie po współdzielonym CustomItemKeys.CUSTOM_ITEM_ID (patrz mainplugins-core) -
 * te moduły zgadzają się na te same stringi tylko przez konwencję, bez twardej zależności.
 */
public class FishingManager implements Listener {

    private final Plugin plugin;

    // Tymczasowo tylko 3 gatunki, po prostu nazwane wg rzadkosci (na czas dopracowywania
    // minigry) - dokladnie te sloty co juz sa wymogami questow kategorii "Rybak" (patrz
    // quests-content.yml), zeby nic nie zepsuc. FISH_MISTYCZNA ma prawdziwy custom model
    // z resourcepacka (patrz custom-items.yml + stworzRybe nizej) - jedyny gatunek, ktory
    // NIE jest budowany bezposrednio z golego Materialu.
    private static final List<RybaGatunek> GATUNKI = List.of(
            new RybaGatunek("FISH_ZWYKLA", "Zwykła Rybka", Material.COD, NamedTextColor.GRAY, RybaGatunek.Rzadkosc.ZWYKLA, 50),
            new RybaGatunek("FISH_SUPER", "Super Rybka", Material.SALMON, NamedTextColor.GREEN, RybaGatunek.Rzadkosc.NIEZWYKLA, 30),
            new RybaGatunek("FISH_MISTYCZNA", "Mistyczna Rybka", Material.TROPICAL_FISH, NamedTextColor.LIGHT_PURPLE, RybaGatunek.Rzadkosc.RZADKA, 5)
    );

    // Aktywna minigra "pasek" - patrz rozpocznijMinigre.
    private final Map<UUID, FishingMinigame> aktywneMinigry = new HashMap<>();

    public FishingManager(Plugin plugin) {
        this.plugin = plugin;
    }

    // ==================================================================== Przedmioty ====

    /** Jedyna wędka na razie - zwykły wanilijski FISHING_ROD, zero tagów. Działanie zależy WYŁĄCZNIE od miejsca (patrz onFish), nie od samego przedmiotu. */
    public ItemStack stworzWedke() {
        return new ItemStack(Material.FISHING_ROD);
    }

    /**
     * Jeśli gatunek ma wpis w rejestrze custom itemów (patrz mainplugins-core,
     * custom-items.yml) - np. FISH_MISTYCZNA z własnym modelem z resourcepacka -
     * wydajemy DOKŁADNIE ten item stamtąd (ten sam wzorzec co ShopManager.stworzBazowyItem).
     * W przeciwnym razie (reszta gatunków - zwykłe przefarbowane materiały) budujemy
     * item ręcznie, tak jak dotychczas.
     */
    private ItemStack stworzRybe(RybaGatunek gatunek) {
        CustomItemService rejestr = CoreAPI.getCustomItemService();
        if (rejestr != null && rejestr.exists(gatunek.customId())) {
            ItemStack custom = rejestr.create(gatunek.customId(), 1);
            if (custom != null) return custom;
        }

        ItemStack item = new ItemStack(gatunek.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(gatunek.nazwa(), gatunek.kolor(), TextDecoration.BOLD));
        meta.lore(List.of(Component.text(opisRzadkosci(gatunek.rzadkosc()), gatunek.kolor()).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, gatunek.customId());
        if (gatunek.rzadkosc().ordinal() >= RybaGatunek.Rzadkosc.RZADKA.ordinal()) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    private String opisRzadkosci(RybaGatunek.Rzadkosc r) {
        return switch (r) {
            case ZWYKLA -> "Zwykła ryba";
            case NIEZWYKLA -> "Niezwykła ryba";
            case RZADKA -> "Rzadka ryba";
            case EPICKA -> "Epicka ryba";
            case LEGENDARNA -> "Legendarna ryba";
        };
    }

    // ==================================================================== Losowanie ====

    private RybaGatunek losujRybe() {
        int suma = 0;
        for (RybaGatunek g : GATUNKI) suma += g.waga();

        int los = ThreadLocalRandom.current().nextInt(suma);
        int akumulator = 0;
        for (RybaGatunek g : GATUNKI) {
            akumulator += g.waga();
            if (los < akumulator) return g;
        }
        return GATUNKI.getLast();
    }

    /**
     * Niezależny bonusowy drop skrzynki z mainplugins-crates po udanym połowie - płaska
     * szansa na razie (brak tierów wędki do skalowania nią, patrz javadoc klasy). Cichy
     * no-op, jeśli mainplugins-crates nie jest wgrany (opcjonalny serwis, patrz CoreAPI).
     */
    private void rzucBonusowaSkrzynke(Player player) {
        CrateService crateService = CoreAPI.getCrateService();
        if (crateService == null) return;
        if (ThreadLocalRandom.current().nextDouble(100.0) >= 3.0) return;

        ItemStack skrzynka = crateService.stworzSkrzynke(1);
        var nieZmieszczone = player.getInventory().addItem(skrzynka);
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        player.sendMessage(Component.text("Z haczyka wypadła też skrzynka!", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
    }

    // ==================================================================== Łowienie ====

    /**
     * Poza łowiskiem nie robimy NIC - zdarzenie leci dalej nietknięte, gracz łowi zupełnie
     * wanilijsko. Wewnątrz łowiska przejmujemy branie: usuwamy prawdziwy hak (żeby wanilijski
     * CAUGHT_FISH nigdy nie nastąpił - nie ma już czym go wywołać) i odpalamy własną minigrę.
     */
    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.BITE) return;
        if (event.getHook() == null) return;

        ObszarService obszarService = CoreAPI.getObszarService();
        if (obszarService == null || !obszarService.jestLowiskiem(event.getHook().getLocation())) return;

        event.setCancelled(true);
        event.getHook().remove();

        rozpocznijMinigre(event.getPlayer(), losujRybe());
    }

    // ==================================================================== Minigra "pasek" ====

    private void rozpocznijMinigre(Player player, RybaGatunek gatunek) {
        UUID uuid = player.getUniqueId();
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f);

        FishingMinigame gra = new FishingMinigame(plugin, player, gatunek,
                () -> {
                    aktywneMinigry.remove(uuid);
                    nagrodaZaPolow(player, gatunek);
                },
                () -> {
                    aktywneMinigry.remove(uuid);
                    player.sendMessage(Component.text("Ryba się wyrwała...", NamedTextColor.GRAY));
                });
        aktywneMinigry.put(uuid, gra);
    }

    private void nagrodaZaPolow(Player player, RybaGatunek zlowiona) {
        ItemStack ryba = stworzRybe(zlowiona);
        var nieZmieszczone = player.getInventory().addItem(ryba);
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));

        player.sendMessage(Component.text("Złowiłeś: ", NamedTextColor.GREEN)
                .append(Component.text(zlowiona.nazwa(), zlowiona.kolor(), TextDecoration.BOLD)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        rzucBonusowaSkrzynke(player);
    }

    /** Jedyna rola tego handlera: przekazać rytmiczne PPM gracza do jego aktywnej minigry (patrz FishingMinigame.kliknij). */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        FishingMinigame gra = aktywneMinigry.get(event.getPlayer().getUniqueId());
        if (gra == null) return;

        event.setCancelled(true);
        gra.kliknij();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        FishingMinigame gra = aktywneMinigry.remove(event.getPlayer().getUniqueId());
        if (gra != null) gra.przerwij();
    }
}
