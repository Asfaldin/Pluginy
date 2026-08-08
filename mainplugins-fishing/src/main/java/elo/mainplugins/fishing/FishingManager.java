package elo.mainplugins.fishing;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CrateService;
import elo.mainplugins.core.util.CustomItemKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Całe łowienie: gatunki ryb, tiery wędek (zwykła -> Niebiańska -> Kosmiczna DARKSTAR),
 * łowienie w wodzie (przejmuje wanilijski PlayerFishEvent od BITE), łowienie w
 * powietrzu/nad pustką (własna symulacja - wanilijski hak wymaga wody), wspólna
 * minigra "pasek" w stylu Stardew Valley (FishingMinigame - rytmiczne PPM podbija
 * suwak, trzeba nałożyć go na błądzącą rybkę), zamrażanie haka lecącego nad krawędzią
 * wyspy w otwartą pustkę (zamiast cichego zniknięcia) oraz upgrade wędki w kowadle
 * receptura+wędka.
 *
 * Zero zależności od mainplugins-quests/mainplugins-shop: receptury i gatunki ryb są
 * rozpoznawane wyłącznie po współdzielonym CustomItemKeys.CUSTOM_ITEM_ID (patrz
 * mainplugins-core) - te trzy moduły zgadzają się na te same stringi tylko przez
 * konwencję, bez twardej zależności między sobą.
 */
public class FishingManager implements Listener {

    private final Plugin plugin;
    private final NamespacedKey rodTierTag;

    private static final List<RybaGatunek> GATUNKI_WODA = List.of(
            new RybaGatunek("FISH_KARP_MIELIZNY", "Karp Mielizny", Material.COD, NamedTextColor.GRAY, RybaGatunek.Rzadkosc.ZWYKLA, 40),
            new RybaGatunek("FISH_SREBRNY_LESZCZ", "Srebrny Leszcz", Material.SALMON, NamedTextColor.GRAY, RybaGatunek.Rzadkosc.ZWYKLA, 30),
            new RybaGatunek("FISH_TECZOWY_SKRZELACZ", "Tęczowy Skrzelacz", Material.TROPICAL_FISH, NamedTextColor.GREEN, RybaGatunek.Rzadkosc.NIEZWYKLA, 15),
            new RybaGatunek("FISH_KOLCZASTY_NURKACZ", "Kolczasty Nurkacz", Material.PUFFERFISH, NamedTextColor.GREEN, RybaGatunek.Rzadkosc.NIEZWYKLA, 10),
            new RybaGatunek("FISH_SZMARAGDOWY_WEGORZ", "Szmaragdowy Węgorz", Material.TROPICAL_FISH, NamedTextColor.AQUA, RybaGatunek.Rzadkosc.RZADKA, 4),
            new RybaGatunek("FISH_MGLAWICOWY_SUM", "Mgławicowy Sum", Material.COD, NamedTextColor.AQUA, RybaGatunek.Rzadkosc.RZADKA, 1)
    );

    private static final List<RybaGatunek> GATUNKI_POWIETRZE = List.of(
            new RybaGatunek("FISH_OBLOCZNY_LATAWIEC", "Obłoczny Latawiec", Material.SALMON, NamedTextColor.AQUA, RybaGatunek.Rzadkosc.RZADKA, 45),
            new RybaGatunek("FISH_GWIEZDNY_PROMYK", "Gwiezdny Promyk", Material.TROPICAL_FISH, NamedTextColor.LIGHT_PURPLE, RybaGatunek.Rzadkosc.EPICKA, 30),
            new RybaGatunek("FISH_OTCHLANNY_LEWIATAN", "Otchłanny Lewiatan", Material.PUFFERFISH, NamedTextColor.GOLD, RybaGatunek.Rzadkosc.LEGENDARNA, 20),
            new RybaGatunek("FISH_DARKSTAR_ISKRA", "Iskra Ciemnej Gwiazdy", Material.COD, NamedTextColor.GOLD, RybaGatunek.Rzadkosc.LEGENDARNA, 5)
    );

    private static final String RECEPTURA_NIEBIANSKA = "FISHING_RECIPE_NIEBIANSKA";
    private static final String RECEPTURA_KOSMICZNA = "FISHING_RECIPE_KOSMICZNA";

    // Stan oczekiwania na branie w powietrzu - patrz onInteract/celujeWPustke niżej.
    private final Map<UUID, BukkitTask> oczekujacyNaBranie = new HashMap<>();

    // Aktywna minigra "pasek Stardew" (woda po BITE albo powietrze po braniu) - patrz rozpocznijMinigre.
    private final Map<UUID, FishingMinigame> aktywneMinigry = new HashMap<>();

    // Prawdziwy wanilijski hak, który poleciał w otwartą pustkę i został zamrożony - patrz zamrozHaczykWVoid.
    private final Map<UUID, BukkitTask> monitorVoidTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> particleVoidTasks = new HashMap<>();
    private final Map<UUID, FishHook> zamrozoneWVoidzie = new HashMap<>();

    private static final double VOID_BUFOR = 8.0;

    public FishingManager(Plugin plugin) {
        this.plugin = plugin;
        this.rodTierTag = new NamespacedKey(plugin, "rod_tier");
    }

    // ==================================================================== Przedmioty ====

    /** Tier 1 = Zwykła Wędka - zwykły wanilijski FISHING_ROD, zero tagów (craftowalna normalnie w survivalu). */
    public ItemStack stworzWedke(int tier) {
        if (tier <= 1) return new ItemStack(Material.FISHING_ROD);

        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(rodTierTag, PersistentDataType.INTEGER, tier);
        meta.setEnchantmentGlintOverride(true);

        if (tier == 2) {
            meta.displayName(Component.text("Niebiańska Wędka", NamedTextColor.AQUA, TextDecoration.BOLD));
            meta.lore(List.of(
                    Component.text("Pozwala łowić nad otwartą pustką,", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("nie tylko w wodzie.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            meta.displayName(nazwaDarkstar());
            meta.lore(List.of(
                    Component.text("Pozwala łowić nad otwartą pustką.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Znacznie zwiększa szansę na rzadkie ryby.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Gradient fiolet -> cyjan -> biel, litera po literze (adventure TextColor.lerp - zero dodatkowych zależności). */
    private Component nazwaDarkstar() {
        String tekst = "✦ Wędka Kosmiczna DARKSTAR ✦";
        TextColor od = TextColor.color(0x8A2BE2);
        TextColor przez = TextColor.color(0x00FFFF);
        TextColor doKoloru = TextColor.color(0xFFFFFF);

        Component wynik = Component.empty();
        int dlugosc = tekst.length();
        for (int i = 0; i < dlugosc; i++) {
            float pozycja = dlugosc <= 1 ? 0f : (float) i / (dlugosc - 1);
            TextColor kolor = pozycja < 0.5f
                    ? TextColor.lerp(pozycja * 2f, od, przez)
                    : TextColor.lerp((pozycja - 0.5f) * 2f, przez, doKoloru);
            wynik = wynik.append(Component.text(String.valueOf(tekst.charAt(i)), kolor));
        }
        return wynik.decoration(TextDecoration.BOLD, true);
    }

    public ItemStack stworzRecepture(int docelowyTier) {
        String customId = docelowyTier == 3 ? RECEPTURA_KOSMICZNA : RECEPTURA_NIEBIANSKA;
        String nazwa = docelowyTier == 3 ? "Receptura: Wędka Kosmiczna DARKSTAR" : "Receptura: Niebiańska Wędka";

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, NamedTextColor.AQUA, TextDecoration.BOLD));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, customId);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack stworzRybe(RybaGatunek gatunek) {
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

    private String pobierzCustomId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
    }

    /** 1 dla zwykłego (nawet nietagowanego) FISHING_ROD - bezpieczny fallback, żeby każda wędka złapała się w system. */
    private int pobierzTierWedki(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD || !rod.hasItemMeta()) return 1;
        Integer tier = rod.getItemMeta().getPersistentDataContainer().get(rodTierTag, PersistentDataType.INTEGER);
        return tier != null ? tier : 1;
    }

    private ItemStack wedkaGracza(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main.getType() == Material.FISHING_ROD) return main;
        ItemStack off = player.getInventory().getItemInOffHand();
        return off.getType() == Material.FISHING_ROD ? off : main;
    }

    // ==================================================================== Losowanie ====

    private RybaGatunek losujRybe(List<RybaGatunek> pula, boolean boostRzadkich) {
        int[] wagi = new int[pula.size()];
        int suma = 0;
        for (int i = 0; i < pula.size(); i++) {
            RybaGatunek g = pula.get(i);
            int w = g.waga();
            if (boostRzadkich && g.rzadkosc().ordinal() >= RybaGatunek.Rzadkosc.RZADKA.ordinal()) {
                w *= 3; // DARKSTAR (tier 3) - wyraźnie podbija szansę na rzadkie+ gatunki
            }
            wagi[i] = w;
            suma += w;
        }
        int los = ThreadLocalRandom.current().nextInt(suma);
        int akumulator = 0;
        for (int i = 0; i < pula.size(); i++) {
            akumulator += wagi[i];
            if (los < akumulator) return pula.get(i);
        }
        return pula.getLast();
    }

    /**
     * Niezależny bonusowy drop skrzynki z mainplugins-crates po udanym połowie - tier
     * skrzynki gated tierem wędki (tier 3 skrzynkę tylko Wędka Kosmiczna, itd.), więc
     * inwestycja w lepszą wędkę ma sens także poza samą pulą ryb. Cichy no-op, jeśli
     * mainplugins-crates nie jest wgrany (opcjonalny serwis, patrz CoreAPI).
     */
    private void rzucBonusowaSkrzynke(Player player, int rodTier) {
        CrateService crateService = CoreAPI.getCrateService();
        if (crateService == null) return;

        double los = ThreadLocalRandom.current().nextDouble(100.0);
        int tier;
        if (rodTier >= 3 && los < 0.1) tier = 3;
        else if (rodTier >= 2 && los < 0.7) tier = 2;
        else if (los < 3.7) tier = 1;
        else return;

        ItemStack skrzynka = crateService.stworzSkrzynke(tier);
        var nieZmieszczone = player.getInventory().addItem(skrzynka);
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        player.sendMessage(Component.text("Z haczyka wypadła też skrzynka!", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
    }

    // ==================================================================== Łowienie w wodzie ====

    /**
     * Wanilijski hak tylko sygnalizuje branie (BITE) - od razu go usuwamy i przejmujemy
     * całość ręcznie przez minigrę "pasek" (FishingMinigame), więc CAUGHT_FISH nigdy
     * już nie następuje (nie ma czym go wywołać). Tier wędki wpływa tylko na boost
     * rzadkich+ gatunków (DARKSTAR), nie na dostęp do samej wody.
     *
     * Stan FISHING (zarzucenie) uruchamia dodatkowo nadzór nad hakiem lecącym w pustkę
     * nad krawędzią wyspy - patrz rozpocznijMonitorVoid/zamrozHaczykWVoid niżej; każdy
     * inny stan kończy ten nadzór (branie już się odbyło albo hak wrócił bez połowu).
     */
    @EventHandler
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (event.getState() == PlayerFishEvent.State.FISHING) {
            if (event.getHook() != null) rozpocznijMonitorVoid(player, event.getHook());
            return;
        }

        if (event.getState() == PlayerFishEvent.State.BITE) {
            zatrzymajMonitorVoid(uuid);
            event.setCancelled(true);
            if (event.getHook() != null) event.getHook().remove();

            int rodTier = pobierzTierWedki(wedkaGracza(player));
            RybaGatunek zlowiona = losujRybe(GATUNKI_WODA, rodTier >= 3);
            rozpocznijMinigre(player, zlowiona, rodTier);
            return;
        }

        zatrzymajMonitorVoid(uuid);
    }

    // ==================================================================== Minigra "pasek" ====

    /** Wspólny start minigry dla wody (po BITE) i powietrza (po braniu w onInteract). */
    private void rozpocznijMinigre(Player player, RybaGatunek gatunek, int rodTier) {
        UUID uuid = player.getUniqueId();
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f);

        FishingMinigame gra = new FishingMinigame(plugin, player, gatunek,
                () -> {
                    aktywneMinigry.remove(uuid);
                    nagrodaZaPolow(player, gatunek, rodTier);
                },
                () -> {
                    aktywneMinigry.remove(uuid);
                    player.sendMessage(Component.text("Ryba się wyrwała...", NamedTextColor.GRAY));
                });
        aktywneMinigry.put(uuid, gra);
    }

    private void nagrodaZaPolow(Player player, RybaGatunek zlowiona, int rodTier) {
        ItemStack ryba = stworzRybe(zlowiona);
        var nieZmieszczone = player.getInventory().addItem(ryba);
        nieZmieszczone.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));

        player.sendMessage(Component.text("Złowiłeś: ", NamedTextColor.GREEN)
                .append(Component.text(zlowiona.nazwa(), zlowiona.kolor(), TextDecoration.BOLD)));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        rzucBonusowaSkrzynke(player, rodTier);
    }

    // ==================================================================== Hak w pustce (void) ====

    /** Co 10 ticków sprawdza, czy prawdziwy hak nie poleciał nad krawędzią wyspy w otwartą pustkę. */
    private void rozpocznijMonitorVoid(Player player, FishHook hook) {
        UUID uuid = player.getUniqueId();
        zatrzymajMonitorVoid(uuid);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!hook.isValid()) {
                zatrzymajMonitorVoid(uuid);
                return;
            }
            if (hook.getLocation().getY() < hook.getWorld().getMinHeight() - VOID_BUFOR) {
                zamrozHaczykWVoid(player, hook);
            }
        }, 10L, 10L);
        monitorVoidTasks.put(uuid, task);
    }

    private void zatrzymajMonitorVoid(UUID uuid) {
        BukkitTask task = monitorVoidTasks.remove(uuid);
        if (task != null) task.cancel();
    }

    /**
     * Zamiast pozwolić hakowi spadać w nieskończoność aż silnik po cichu go usunie
     * (domyślne zachowanie poza granicami świata), zatrzymujemy go w miejscu (zerowa
     * grawitacja i prędkość) z cząsteczkami jako sygnałem "utknął". Wisi tak bez
     * limitu czasu - gracz musi ręcznie zwinąć wędkę kolejnym PPM (patrz onInteract
     * i zwinWedkeZVoidu).
     */
    private void zamrozHaczykWVoid(Player player, FishHook hook) {
        UUID uuid = player.getUniqueId();
        zatrzymajMonitorVoid(uuid);

        hook.setGravity(false);
        hook.setVelocity(new Vector(0, 0, 0));
        zamrozoneWVoidzie.put(uuid, hook);

        player.sendMessage(Component.text("Haczyk wpadł w otchłań i tam utknął... kliknij PPM, by zwinąć wędkę.", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.6f, 0.6f);

        BukkitTask particleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!hook.isValid()) {
                BukkitTask t = particleVoidTasks.remove(uuid);
                if (t != null) t.cancel();
                zamrozoneWVoidzie.remove(uuid);
                return;
            }
            hook.getWorld().spawnParticle(Particle.PORTAL, hook.getLocation(), 6, 0.15, 0.15, 0.15, 0.02);
        }, 0L, 8L);
        particleVoidTasks.put(uuid, particleTask);
    }

    private void zwinWedkeZVoidu(Player player) {
        UUID uuid = player.getUniqueId();
        FishHook hook = zamrozoneWVoidzie.remove(uuid);
        if (hook == null) return;

        BukkitTask particleTask = particleVoidTasks.remove(uuid);
        if (particleTask != null) particleTask.cancel();

        hook.remove();
        player.sendMessage(Component.text("Zwinąłeś wędkę.", NamedTextColor.GRAY));
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0f, 1.0f);
    }

    // ==================================================================== Łowienie w powietrzu ====

    /**
     * Wanilijski hak wymaga otwartej wody, więc nie da się pod niego podpiąć łowienia
     * "nad pustką" - własna, uproszczona symulacja: PPM nad czystą pustką (raytrace bez
     * trafienia w blok) startuje losowe oczekiwanie (jak wanilijskie 5-25s), a branie
     * od razu uruchamia tę samą minigrę "pasek" co przy łowieniu w wodzie.
     *
     * Ta sama metoda obsługuje też PPM podczas aktywnej minigry (przekazywane jako
     * "podbicie" suwaka) oraz PPM zwijające hak zamrożony w pustce.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        FishingMinigame gra = aktywneMinigry.get(uuid);
        if (gra != null) {
            event.setCancelled(true);
            gra.kliknij();
            return;
        }

        if (zamrozoneWVoidzie.containsKey(uuid)) {
            event.setCancelled(true);
            zwinWedkeZVoidu(player);
            return;
        }

        ItemStack wRece = player.getInventory().getItemInMainHand();
        if (wRece.getType() != Material.FISHING_ROD) return;

        int tier = pobierzTierWedki(wRece);
        if (tier < 2) return; // Zwykła Wędka - tylko woda (PlayerFishEvent wyżej)

        if (oczekujacyNaBranie.containsKey(uuid)) return; // już zarzucone - czekamy na branie

        if (!celujeWPustke(player)) return; // nie stoi nad krawędzią/pustką - zostaw zdarzenie wanilii (np. woda)

        event.setCancelled(true);
        player.sendMessage(Component.text("Zarzuciłeś wędkę w otchłań...", NamedTextColor.AQUA));
        player.playSound(player.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1.0f, 1.0f);

        long opoznienie = ThreadLocalRandom.current().nextLong(20L * 5, 20L * 25);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            oczekujacyNaBranie.remove(uuid);
            if (!player.isOnline()) return;

            player.sendMessage(Component.text("Coś złapało haczyk!", NamedTextColor.GOLD, TextDecoration.BOLD));
            RybaGatunek zlowiona = losujRybe(GATUNKI_POWIETRZE, tier >= 3);
            rozpocznijMinigre(player, zlowiona, tier);
        }, opoznienie);

        oczekujacyNaBranie.put(uuid, task);
    }

    /** "Otwarta pustka" = brak jakiegokolwiek bloku w linii wzroku w rozsądnym zasięgu - krawędź wyspy/nad urwiskiem. */
    private boolean celujeWPustke(Player player) {
        RayTraceResult trafienie = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), 40);
        return trafienie == null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        BukkitTask task = oczekujacyNaBranie.remove(uuid);
        if (task != null) task.cancel();

        FishingMinigame gra = aktywneMinigry.remove(uuid);
        if (gra != null) gra.przerwij();

        zatrzymajMonitorVoid(uuid);
        BukkitTask particleTask = particleVoidTasks.remove(uuid);
        if (particleTask != null) particleTask.cancel();
        zamrozoneWVoidzie.remove(uuid);
    }

    // ==================================================================== Upgrade w kowadle ====

    /**
     * Jedyne miejsce w całym repo używające PrepareAnvilEvent (brak wcześniejszego wzorca -
     * patrz research przed implementacją). Lewy slot = wędka odpowiedniego tieru, prawy
     * slot = pasująca receptura (papier tagowany przez QuestManager.receptura() albo
     * stworzRecepture() wyżej) -> nadpisujemy wynik i zerujemy koszt naprawy, żeby wzięcie
     * przedmiotu nie kosztowało graczowi poziomów doświadczenia.
     */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack lewy = event.getInventory().getItem(0);
        ItemStack prawy = event.getInventory().getItem(1);
        if (lewy == null || prawy == null || lewy.getType() != Material.FISHING_ROD) return;

        String customId = pobierzCustomId(prawy);
        if (customId == null) return;

        int tierLewej = pobierzTierWedki(lewy);
        ItemStack wynik = switch (customId) {
            case RECEPTURA_NIEBIANSKA -> tierLewej == 1 ? stworzWedke(2) : null;
            case RECEPTURA_KOSMICZNA -> tierLewej == 2 ? stworzWedke(3) : null;
            default -> null;
        };
        if (wynik == null) return;

        event.setResult(wynik);
        event.getInventory().setRepairCost(0);
    }
}