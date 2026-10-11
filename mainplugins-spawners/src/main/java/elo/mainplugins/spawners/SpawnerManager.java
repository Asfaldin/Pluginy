package elo.mainplugins.spawners;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.CustomMobService;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.core.util.CustomItemKeys;
import elo.mainplugins.spawners.config.SpawnerConfig;
import elo.mainplugins.spawners.config.SpawnerSettings;
import elo.mainplugins.spawners.config.SpawnerTypeDef;
import elo.mainplugins.spawners.config.UpgradeDef;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sadzenie/zbiory customowych spawnerów - własny, w pełni sterowany harmonogram
 * spawnu (ignoruje światło/graczy w pobliżu itd. jak wanilijski spawner),
 * niezależny od tego, czy mainplugins-skyblock jest w ogóle zainstalowany
 * (bez niego wszystkie spawnery działają na sztywnym poziomie 1).
 *
 * Spawner może robić zwykłego moba z gry albo custom moba z Kreatora mobów (mainplugins-mobs,
 * przez CoreAPI.getCustomMobService()) - wtedy w klatce kręci się miniatura jego modelu.
 * Każdy typ ma własne ustawienia (patrz SpawnerTypeDef): stackowanie albo osobne moby, promienie,
 * drop, XP, pora dnia, limity, ulepszenia.
 */
public class SpawnerManager implements Listener {

    static class Instancja {
        final Location lokalizacja;
        final SpawnerTypeDef typ;
        final UUID ownerUUID;
        long nastepnySpawnMillis;

        // "Stackowanie" mobków - zamiast trzymać osobną żywą encję na każdego moba (to one
        // najbardziej obciążają serwer - AI/pathfinding), spawner ma NAJWYŻEJ JEDNĄ żywą encję
        // na raz. rozmiarStosu to ile "kolejnych" mobków ta jedna encja reprezentuje - każde
        // zabicie zdejmuje 1 (normalny drop za normalne zabicie) i od razu odradza resztę stosu
        // w tym samym miejscu, więc dla gracza czuć to jak zabijanie osobnych mobków pod rząd.
        // Bez stackowania (typ.stackowanie() == false) "zywe" trzyma do maxNaRaz osobnych mobów.
        int rozmiarStosu = 0;
        final Set<UUID> zywe = new LinkedHashSet<>();

        // Miniatura custom moba w klatce (null = nie pokazana) i jej obrót.
        CustomMobService.Preview podglad;
        float kat;

        Instancja(Location lokalizacja, SpawnerTypeDef typ, UUID ownerUUID) {
            this.lokalizacja = lokalizacja;
            this.typ = typ;
            this.ownerUUID = ownerUUID;
        }
    }

    /** Co ile ticków obracamy miniatury custom mobów w klatkach i o ile stopni. */
    private static final int PODGLAD_TICKI = 3;
    private static final float PODGLAD_KROK = 10f;
    /** Miniatura pokazywana, gdy gracz jest bliżej niż tyle bloków. */
    private static final double PODGLAD_ZASIEG = 32;

    private final Plugin plugin;
    private final LangService lang;
    private final SpawnerLevels levels;
    private SpawnerUpgradeMenu menu;
    private final File plikSpawnerow;
    private final FileConfiguration configSpawnerow;
    private final AsyncConfigSaver saverSpawnerow;
    private final Map<String, Instancja> instancje = new HashMap<>();
    // Odwrotna mapa mob -> spawner który go wyprodukował, żeby onSmierc wiedział skąd go wypisać
    // bez przeszukiwania wszystkich instancji.
    private final Map<UUID, Instancja> mobyDoSpawnera = new HashMap<>();
    private volatile SpawnerConfig config;

    public SpawnerManager(Plugin plugin, SpawnerConfig config, LangService lang) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.levels = new SpawnerLevels(plugin);
        this.plikSpawnerow = new File(plugin.getDataFolder(), "spawnery.yml");
        if (!plikSpawnerow.exists()) {
            plikSpawnerow.getParentFile().mkdirs();
            try { plikSpawnerow.createNewFile(); } catch (IOException ignored) {}
        }
        this.configSpawnerow = YamlConfiguration.loadConfiguration(plikSpawnerow);
        this.saverSpawnerow = new AsyncConfigSaver(plugin, configSpawnerow, plikSpawnerow, 30);
        wczytaj();

        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickPodglad, 40L, PODGLAD_TICKI);
    }

    private void wczytaj() {
        ConfigurationSection sekcja = configSpawnerow.getConfigurationSection("spawnery");
        if (sekcja == null) return;

        for (String key : sekcja.getKeys(false)) {
            String path = "spawnery." + key + ".";
            String worldName = configSpawnerow.getString(path + "world");
            World world = worldName != null ? Bukkit.getWorld(worldName) : null;
            if (world == null) {
                plugin.getLogger().warning("Pominięto spawner - świat '" + worldName + "' nie jest jeszcze wczytany.");
                continue;
            }

            Location loc = new Location(world,
                    configSpawnerow.getInt(path + "x"),
                    configSpawnerow.getInt(path + "y"),
                    configSpawnerow.getInt(path + "z"));

            if (loc.getBlock().getType() != Material.SPAWNER) continue; // ktoś usunął blok poza BlockBreakEvent

            SpawnerTypeDef typ = config.typ(configSpawnerow.getString(path + "type", ""));
            if (typ == null) {
                plugin.getLogger().warning("Pominięto spawner - nieznany typ '" + configSpawnerow.getString(path + "type") + "' (usunięty ze spawnery-typy.yml?).");
                continue;
            }

            UUID ownerUUID;
            try {
                ownerUUID = UUID.fromString(configSpawnerow.getString(path + "owner", ""));
            } catch (IllegalArgumentException e) {
                continue;
            }

            Instancja instancja = new Instancja(loc, typ, ownerUUID);
            zainicjujKolejnySpawn(instancja);
            instancje.put(kluczLokalizacji(loc), instancja);
        }

        plugin.getLogger().info("Wczytano " + instancje.size() + " customowych spawnerów.");
    }

    private void zapisz() {
        configSpawnerow.set("spawnery", null);
        int i = 0;
        for (Instancja instancja : instancje.values()) {
            String path = "spawnery." + (i++) + ".";
            Location loc = instancja.lokalizacja;
            configSpawnerow.set(path + "world", loc.getWorld().getName());
            configSpawnerow.set(path + "x", loc.getBlockX());
            configSpawnerow.set(path + "y", loc.getBlockY());
            configSpawnerow.set(path + "z", loc.getBlockZ());
            configSpawnerow.set(path + "type", instancja.typ.id());
            configSpawnerow.set(path + "owner", instancja.ownerUUID.toString());
        }
        saverSpawnerow.oznaczZmiane();
    }

    public void zamknij() {
        for (Instancja i : instancje.values()) usunPodglad(i);
        saverSpawnerow.zamknij();
        levels.zamknij();
    }

    SpawnerConfig config() {
        return config;
    }

    SpawnerLevels levels() {
        return levels;
    }

    void ustawMenu(SpawnerUpgradeMenu menu) {
        this.menu = menu;
    }

    /**
     * Podmienia typy/ustawienia spawnerów na żywo - patrz /@reloadspawnery. Postawione spawnery
     * od razu biorą nowe ustawienia swojego typu (typ(instancja) czyta je po id), a miniatury
     * custom mobów odświeżają się same przy najbliższym obrocie.
     */
    public void aktualizujKonfiguracje(SpawnerConfig nowy) {
        this.config = nowy;
        for (Instancja i : instancje.values()) usunPodglad(i);
    }

    /** Aktualne ustawienia typu tego spawnera - po przeładowaniu configu nowe, gdy typ usunięto - zapamiętane. */
    private SpawnerTypeDef typ(Instancja instancja) {
        SpawnerTypeDef t = config.typ(instancja.typ.id());
        return t != null ? t : instancja.typ;
    }

    /** Prefiks id spawnerów w katalogu itemów core ("custom: spawner_zombie"). */
    public static final String CATALOG_PREFIX = "spawner_";

    /** Id wszystkich spawnerów w katalogu itemów. */
    public java.util.Set<String> catalogIds() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String id : config.typy().keySet()) out.add(CATALOG_PREFIX + id.toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    /** Typ z tagu przedmiotu: nowy "spawner_zombie" albo stary "ZOMBIE" (przedmioty sprzed katalogu). */
    SpawnerTypeDef typZTagu(String tag) {
        String id = tag.toLowerCase(java.util.Locale.ROOT).startsWith(CATALOG_PREFIX) ? tag.substring(CATALOG_PREFIX.length()) : tag;
        SpawnerTypeDef exact = config.typ(id);
        if (exact != null) return exact;
        for (Map.Entry<String, SpawnerTypeDef> e : config.typy().entrySet()) {
            if (e.getKey().equalsIgnoreCase(id)) return e.getValue();
        }
        return null;
    }

    /** Przedmiot spawnera danego typu (id z katalogu albo samo id typu); null, gdy typu nie ma. */
    public ItemStack createItem(String catalogId, int amount) {
        SpawnerTypeDef typ = typZTagu(catalogId);
        if (typ == null) return null;
        ItemStack item = new ItemStack(Material.SPAWNER, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(lang.msg(plugin, "item.name", Map.of("type", typ.nazwaOdmieniona())).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING,
                CATALOG_PREFIX + typ.id().toLowerCase(java.util.Locale.ROOT));
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private String kluczLokalizacji(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    /** Postawiony spawner w tym bloku (dla /@spawner info); null = to nie nasz spawner. */
    Instancja instancjaW(Block block) {
        return instancje.get(kluczLokalizacji(block.getLocation()));
    }

    /** Ile postawionych spawnerów jest na serwerze (dla /@spawner list). */
    int liczbaPostawionych() {
        return instancje.size();
    }

    SpawnerTypeDef aktualnyTyp(Instancja instancja) {
        return typ(instancja);
    }

    /** Opis poziomów ulepszeń tego spawnera dla /@spawner info, np. "Ilość 3/5, Drop 2/4". */
    String opisPoziomow(Instancja instancja) {
        SpawnerTypeDef typ = typ(instancja);
        StringBuilder sb = new StringBuilder();
        for (UpgradeDef u : config.ulepszeniaTypu(typ)) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(u.nazwa()).append(' ').append(poziomUlepszenia(instancja.ownerUUID, typ.id(), u)).append('/').append(u.maxPoziom());
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    long sekundDoSpawnu(Instancja instancja) {
        return Math.max(0, (instancja.nastepnySpawnMillis - System.currentTimeMillis()) / 1000L);
    }

    /** Ustawia blok spawnera: wyłącza wanilijski spawn i mówi grze, co ma się kręcić w klatce. */
    private void przygotujBlok(Block block, SpawnerTypeDef typ) {
        if (!(block.getState() instanceof CreatureSpawner state)) return;
        try {
            // Custom mob: klatka pusta (kręci się nasza miniatura); zwykły mob: gra sama go kręci.
            state.setSpawnedType(!typ.custom() && typ.wKlatce() ? typ.entityType() : null);
        } catch (IllegalArgumentException | NullPointerException e) {
            if (typ.entityType() != null) state.setSpawnedType(typ.entityType());
        }
        state.setMaxNearbyEntities(0); // wyłącza wanilijski spawn - o spawnie decyduje wyłącznie nasz harmonogram
        state.update(true, false);
    }

    @EventHandler
    public void onSadzenie(BlockPlaceEvent event) {
        ItemStack itemUzyty = event.getItemInHand();
        if (itemUzyty.getType() != Material.SPAWNER) return;

        ItemMeta meta = itemUzyty.getItemMeta();
        if (meta == null) return;

        String typName = meta.getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
        if (typName == null) return; // zwykły wanilijski spawner (np. z creative) - zostaw jak jest

        SpawnerTypeDef typ = typZTagu(typName);
        if (typ == null) return; // nieznany typ (spoza spawnery-typy.yml) - nie nasz custom-id

        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();

        UUID ownerUUID = player.getUniqueId();
        IslandService islandService = CoreAPI.getIslandService();
        if (islandService != null) {
            IslandSummary summary = islandService.getIslandOf(player.getUniqueId());
            if (summary != null) ownerUUID = summary.ownerUUID();
        }

        int limit = limitGracza(player);
        int juzPostawione = policzSpawneryWyspy(ownerUUID, null);
        if (juzPostawione >= limit) {
            lang.send(player, plugin, "place.limit", Map.of("limit", String.valueOf(limit), "count", String.valueOf(juzPostawione)));
            event.setCancelled(true);   // blok nie zostaje postawiony, item wraca do ekwipunku
            return;
        }
        if (typ.limitNaWyspe() > 0) {
            int tegoTypu = policzSpawneryWyspy(ownerUUID, typ.id());
            if (tegoTypu >= typ.limitNaWyspe()) {
                lang.send(player, plugin, "place.type-limit", Map.of("limit", String.valueOf(typ.limitNaWyspe()), "type", typ.nazwaOdmieniona()));
                event.setCancelled(true);
                return;
            }
        }
        if (typ.custom() && customMoby() == null) {
            lang.send(player, plugin, "place.no-mobs-plugin", Map.of("type", typ.nazwaOdmieniona()));
        }

        przygotujBlok(block, typ);

        Location loc = block.getLocation();
        Instancja instancja = new Instancja(loc, typ, ownerUUID);
        zainicjujKolejnySpawn(instancja); // pełny interwał, nie spawnuje natychmiast po postawieniu
        instancje.put(kluczLokalizacji(loc), instancja);
        zapisz();

        lang.send(player, plugin, "place.done", Map.of("type", typ.nazwaOdmieniona()));
    }

    /** Limit spawnerów na wyspę dla gracza: zwykły albo wyższy z uprawnień (ustawienia.limity-uprawnien). */
    int limitGracza(Player player) {
        SpawnerSettings u = config.ustawienia();
        int limit = u.limitSpawnerowNaWyspe();
        for (Map.Entry<String, Integer> e : u.limityUprawnien().entrySet()) {
            if (player.hasPermission(e.getKey())) limit = Math.max(limit, e.getValue());
        }
        return limit;
    }

    /**
     * Zdejmuje spawner z ewidencji i sprząta po nim wszystko: wpis w mapie
     * instancji, wpisy w mobyDoSpawnera, żywe encje i miniaturę w klatce.
     *
     * Bez wyczyszczenia mobyDoSpawnera osierocony mob dalej wskazywałby na
     * usuniętą instancję i przy zabiciu odradzałby kolejne sztuki ze stosu.
     */
    private void usunSpawner(Location loc) {
        Instancja instancja = instancje.remove(kluczLokalizacji(loc));
        if (instancja == null) return;
        wygasZyweMoby(instancja);
        usunPodglad(instancja);
        zapisz();
    }

    /**
     * Usuwa żywe encje tego spawnera i zeruje kolejkę - używane gdy nikt już nie jest
     * w zasięgu aktywacji (patrz gracsAktywujeSpawner), zarówno z tick() (gracz zwyczajnie
     * odszedł/poszedł na spawn) jak i z onQuit (gracz się wylogował).
     *
     * Zerowanie kolejki (nie tylko usunięcie encji) jest celowe: nastepnySpawnMillis liczy się
     * na realnym czasie i leci dalej w tle niezależnie od aktywności - gdyby kolejka zostawała
     * "należna", po powrocie gracza tick() doliczyłby kolejny pełny cykl NA WIERZCH tego co już
     * czekało (np. 9 starych + 9 nowych = 18 zamiast normalnych 9). Zerowanie daje czysty start.
     */
    private void wygasZyweMoby(Instancja instancja) {
        for (UUID id : instancja.zywe) {
            usunEncje(Bukkit.getEntity(id)); // bez EntityDeathEvent - zero dropów, zwykłe zniknięcie
            mobyDoSpawnera.remove(id);
        }
        instancja.zywe.clear();
        instancja.rozmiarStosu = 0;
    }

    /** Usuwa encję spawnera - custom moba razem z jego modelem. */
    private void usunEncje(Entity e) {
        if (e == null) return;
        CustomMobService moby = customMoby();
        if (moby != null && moby.isCustomMob(e)) moby.remove(e);
        else e.remove();
    }

    /**
     * Zwykłe niszczenie (kilof, cokolwiek innego) jest zablokowane dla naszych spawnerów -
     * jedyna droga to onZbieranieSpawnera niżej (narzędzie zbierania + PPM), żeby nikt nie stracił
     * spawnera przez przypadkowe/impulsywne rozbicie. Wanilijski spawner (nieśledzony
     * w instancje, np. w lochu) łamie się normalnie - to nie jest nasz teren.
     */
    @EventHandler(ignoreCancelled = true)
    public void onZniszczenie(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.SPAWNER) return;
        Instancja instancja = instancje.get(kluczLokalizacji(event.getBlock().getLocation()));
        if (instancja == null) return;

        event.setCancelled(true);
        lang.send(event.getPlayer(), plugin, "break.blocked");
    }

    @EventHandler
    public void onZbieranieSpawnera(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Zdarzenie odpala się raz na każdą rękę - bez tego kod poszedłby dwa razy.
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) return;

        Player player = event.getPlayer();
        Instancja instancja = instancje.get(kluczLokalizacji(block.getLocation()));
        if (instancja == null) return;   // wanilijski spawner - nie nasz, zostaw w spokoju

        // Pusta ręka na swoim spawnerze = okno ulepszeń (to samo co /spawnery).
        if (player.getInventory().getItemInMainHand().getType() == Material.AIR) {
            if (menu != null && instancja.ownerUUID.equals(wyspaGracza(player.getUniqueId()))) {
                event.setCancelled(true);
                menu.otworz(player, 0);
            }
            return;
        }
        if (player.getInventory().getItemInMainHand().getType() != config.ustawienia().narzedzieZbierania()) return;

        if (!mozeZebrac(player, instancja)) {
            lang.send(player, plugin, "collect.not-yours");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);   // nie otwieraj GUI spawnera ani nic innego

        Location loc = block.getLocation();
        SpawnerTypeDef typ = instancja.typ;

        usunSpawner(loc);
        block.setType(Material.AIR);

        // Item z custom-id (ten sam co w katalogu), żeby po podniesieniu i postawieniu był tym samym typem.
        ItemStack drop = createItem(typ.id(), 1);

        loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), drop);
        lang.send(player, plugin, "collect.done", Map.of("type", typ.nazwaOdmieniona()));
    }

    /** Kto może podnieść spawner (ustawienia.kto-moze-zbierac); admin zawsze. */
    private boolean mozeZebrac(Player player, Instancja instancja) {
        if (player.hasPermission("mainplugins.spawners.admin")) return true;
        return switch (config.ustawienia().ktoZbiera()) {
            case KAZDY -> true;
            case NIKT -> false;
            case WLASCICIEL -> instancja.ownerUUID.equals(player.getUniqueId());
            case WYSPA -> instancja.ownerUUID.equals(wyspaGracza(player.getUniqueId()));
        };
    }

    private void tick() {
        long teraz = System.currentTimeMillis();

        for (Instancja instancja : instancje.values()) {
            Location loc = instancja.lokalizacja;
            if (!loc.isChunkLoaded()) continue;
            if (loc.getBlock().getType() != Material.SPAWNER) continue; // usunięty poza BlockBreakEvent (np. explozja)
            SpawnerTypeDef typ = typ(instancja);

            // Sprzątanie żywych encji, gdy nikt już nie jest w zasięgu - CO SEKUNDĘ, niezależnie
            // od tego czy akurat minął interwał cyklu (ten warunek jest NIŻEJ). Bez tego czekalibyśmy
            // aż zegar cyklu odpali (kilkanaście-kilkadziesiąt sekund) albo aż zadziała wanilijski
            // despawn (setRemoveWhenFarAway) - a ten nigdy się nie uruchomi, jeśli chunk zdąży się
            // wyładować zanim despawn zdąży zajść (gracz poszedł na spawn/wylogował się i chunk
            // przestaje tickować, zamrażając moba na zawsze zamiast go usuwać).
            if (!instancja.zywe.isEmpty() && !gracsAktywujeSpawner(instancja, typ, null)) {
                wygasZyweMoby(instancja);
                continue;
            }

            if (teraz < instancja.nastepnySpawnMillis) continue;
            if (!gracsAktywujeSpawner(instancja, typ, null)) continue; // nikt w promieniu ani na tym samym chunku - śpi, nie zużywa cyklu
            if (!typ.dzialaO(loc.getWorld().getTime())) continue; // nie ta pora dnia - czeka

            // Encje, które zniknęły bez zabicia (np. zabite przez coś innego niż nasz onSmierc -
            // rzadkie, ale na wszelki wypadek) - tylko zapominamy o nich. rozmiarStosu zostaje -
            // to wciąż "należny" limit temu spawnerowi (gracz jest tu i teraz aktywny).
            instancja.zywe.removeIf(id -> {
                Entity mob = Bukkit.getEntity(id);
                if (mob != null && mob.isValid()) return false;
                mobyDoSpawnera.remove(id);
                return true;
            });

            int poziomIlosci = poziomWzoru(instancja, typ, UpgradeDef.Efekt.ILOSC);
            int poziomSzybkosci = poziomWzoru(instancja, typ, UpgradeDef.Efekt.SZYBKOSC);
            int iloscDoSpawnu = typ.iloscNaCykl(poziomIlosci);

            if (typ.stackowanie()) {
                int limitStosu = typ.limitKolejki() + (int) Math.round(bonus(instancja, typ, UpgradeDef.Efekt.STOS));
                if (instancja.rozmiarStosu < limitStosu) {
                    instancja.rozmiarStosu = Math.min(limitStosu, instancja.rozmiarStosu + iloscDoSpawnu);
                }
                if (instancja.zywe.isEmpty() && instancja.rozmiarStosu > 0) {
                    odrodzMoba(instancja, typ, losowyPunktObokSpawnera(loc, typ));
                } else {
                    // Encja już żyje, ale stos jej urósł w tym cyklu - bez tego nametag pokazywałby
                    // starą liczbę aż do najbliższego zabicia (wtedy dopiero leciał respawn z nowym tagiem).
                    aktualizujNametag(instancja, typ);
                }
            } else {
                // Osobne moby: tyle ile daje cykl, ale nigdy więcej niż max-mobow-naraz żywych.
                int wolne = typ.maxNaRaz() + (int) Math.round(bonus(instancja, typ, UpgradeDef.Efekt.MAX_NARAZ)) - instancja.zywe.size();
                for (int i = 0; i < Math.min(wolne, iloscDoSpawnu); i++) {
                    odrodzMoba(instancja, typ, losowyPunktObokSpawnera(loc, typ));
                }
            }

            instancja.nastepnySpawnMillis = teraz + typ.interwalSekund(poziomSzybkosci) * 1000L;
        }
    }

    /**
     * Kratka obok spawnera w promieniu promien-spawnu (domyślnie 3), NIGDY na jego bloku -
     * bez sprawdzania podłoża pod spawnem (gracz prosił, żeby respiło się nawet
     * bez żadnego bloku pod spawnem - encje grawitacyjne po prostu spadną).
     */
    private Location losowyPunktObokSpawnera(Location spawnerLoc, SpawnerTypeDef typ) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int promien = typ.promienSpawnu();
        int dx, dz;
        do {
            dx = r.nextInt(-promien, promien + 1);
            dz = r.nextInt(-promien, promien + 1);
        } while (dx == 0 && dz == 0);
        return spawnerLoc.clone().add(0.5 + dx, 1.0, 0.5 + dz);
    }

    /**
     * Tania wersja "czy ten JEDEN znany punkt jest w zasięgu aktywacji spawnera" - bez żadnego
     * zapytania do Bukkita (getNearbyPlayers/getPlayers), sama arytmetyka na współrzędnych.
     * Używana jako wstępny filtr w onQuit, żeby nie sprawdzać spawnerów, na które wyjście
     * akurat TEGO gracza i tak nie ma wpływu.
     */
    private boolean wZasiegu(Instancja instancja, SpawnerTypeDef typ, Location punkt) {
        Location spawnerLoc = instancja.lokalizacja;
        if (!spawnerLoc.getWorld().equals(punkt.getWorld())) return false;
        int promien = zasieg(instancja, typ);
        if (spawnerLoc.distanceSquared(punkt) <= (double) promien * promien) return true;

        return (spawnerLoc.getBlockX() >> 4) == (punkt.getBlockX() >> 4)
                && (spawnerLoc.getBlockZ() >> 4) == (punkt.getBlockZ() >> 4);
    }

    /**
     * Spawner jest aktywny, gdy gracz jest w promieniu aktywności LUB stoi gdziekolwiek na tym
     * samym chunku co spawner - sam promień by nie wystarczył, bo przekątna chunka (16x16)
     * ma ~22,6 bloku, więc gracz w rogu chunka mógłby się nie łapać. wyklucz = gracz pomijany
     * (PlayerQuitEvent - w momencie sprawdzania wychodzący wciąż widnieje jako "online").
     */
    private boolean gracsAktywujeSpawner(Instancja instancja, SpawnerTypeDef typ, UUID wyklucz) {
        Location loc = instancja.lokalizacja;
        for (Player gracz : loc.getWorld().getNearbyPlayers(loc, zasieg(instancja, typ))) {
            if (!gracz.getUniqueId().equals(wyklucz)) return true;
        }

        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        for (Player gracz : loc.getWorld().getPlayers()) {
            if (gracz.getUniqueId().equals(wyklucz)) continue;
            Location poz = gracz.getLocation();
            if ((poz.getBlockX() >> 4) == chunkX && (poz.getBlockZ() >> 4) == chunkZ) return true;
        }
        return false;
    }

    private static CustomMobService customMoby() {
        return CoreAPI.getCustomMobService();
    }

    /** Spawnuje jedną encję tego spawnera i rejestruje ją jako "żywą"; false = nie udało się (np. brak pluginu mobów). */
    private boolean odrodzMoba(Instancja instancja, SpawnerTypeDef typ, Location gdzie) {
        Entity encja;
        if (typ.custom()) {
            CustomMobService moby = customMoby();
            encja = moby == null ? null : moby.spawn(typ.customMob(), gdzie);
            if (encja == null) return false; // plugin mobów wyłączony albo moba usunięto z Kreatora
        } else {
            encja = gdzie.getWorld().spawnEntity(gdzie, typ.entityType());
            if (encja instanceof Ageable ageable) {
                // Zawsze dorosły - bez tego np. Zombie ma losową szansę wyjść jako "baby zombie"
                // (dotyczy też Cow/Pig/Sheep/Chicken, choć u nich to i tak rzadkie przy zwykłym spawnie).
                ageable.setAdult();
            }
            // Zero jockeyów (zombie na kurczaku, szkielet na pająku) - to normalny wanilijski
            // spawn-bonus dla tych typów, ale u nas ma spawnować dokładnie to, co kupione, nic więcej.
            // Usuwamy niezależnie w obie strony: gdyby nasza encja była wierzchowcem LUB jeźdźcem.
            for (Entity pasazer : new ArrayList<>(encja.getPassengers())) {
                pasazer.remove();
            }
            Entity pojazd = encja.getVehicle();
            if (pojazd != null) {
                encja.eject();
                pojazd.remove();
            }
            if (!typ.ai() && encja instanceof Mob mob) mob.setAI(false); // stoi w miejscu - pod farmy
        }
        if (encja instanceof LivingEntity zywa) {
            zywa.setRemoveWhenFarAway(true); // ma despawnować jak zwykły dziki mob (zwierzęta domyślnie by NIE despawnowały)
        }
        encja.getPersistentDataContainer().set(CustomItemKeys.SPAWNER_MOB_SOURCE, PersistentDataType.STRING, typ.id());

        instancja.zywe.add(encja.getUniqueId());
        mobyDoSpawnera.put(encja.getUniqueId(), instancja);
        aktualizujNametag(instancja, typ);
        return true;
    }

    /**
     * Odświeża nametag "TypNazwa xN" na żywej encji stosu - albo go chowa, gdy stos ma tylko 1 sztukę.
     * Custom moby mają własną tabliczkę z nazwą z Kreatora - ich nie ruszamy.
     */
    private void aktualizujNametag(Instancja instancja, SpawnerTypeDef typ) {
        if (!typ.stackowanie() || typ.custom() || instancja.zywe.isEmpty()) return;
        if (!(Bukkit.getEntity(instancja.zywe.iterator().next()) instanceof LivingEntity zywa)) return;

        if (typ.nametag() && instancja.rozmiarStosu > 1) {
            zywa.customName(lang.msg(plugin, "mob.stack-name",
                    Map.of("name", typ.nazwaPojedyncza(), "count", String.valueOf(instancja.rozmiarStosu))));
            zywa.setCustomNameVisible(true);
        } else {
            zywa.customName(null);
            zywa.setCustomNameVisible(false);
        }
    }

    /** Ile spawnerów stoi już na wyspie danego właściciela (typId != null - tylko tego typu). */
    private int policzSpawneryWyspy(UUID ownerUUID, String typId) {
        int licznik = 0;
        for (Instancja i : instancje.values()) {
            if (i.ownerUUID.equals(ownerUUID) && (typId == null || i.typ.id().equals(typId))) licznik++;
        }
        return licznik;
    }

    /**
     * UUID właściciela wyspy, na której gracz jest członkiem. Gdy modułu wysp
     * nie ma albo gracz nie ma wyspy - zwraca jego własne UUID, tak samo jak
     * robi to onSadzenie() przy przypisywaniu właściciela.
     */
    UUID wyspaGracza(UUID playerUUID) {
        IslandService islandService = CoreAPI.getIslandService();
        if (islandService == null) return playerUUID;
        IslandSummary summary = islandService.getIslandOf(playerUUID);
        return summary != null ? summary.ownerUUID() : playerUUID;
    }

    /** Kupiony poziom ulepszenia dla właściciela tego spawnera (1 = nic), nie wyżej niż jego max-poziom. */
    int poziomUlepszenia(UUID wlasciciel, String typId, UpgradeDef u) {
        return Math.min(u.maxPoziom(), levels.poziom(wlasciciel, typId, u.id()));
    }

    /**
     * Poziom do wzoru typu (Ilość/Szybkość): 1 + suma kupionych poziomów ulepszeń z tym efektem
     * (zwykle jedno ulepszenie - wtedy dokładnie jego poziom, jak dawniej). Bez ulepszeń = 1.
     */
    private int poziomWzoru(Instancja instancja, SpawnerTypeDef typ, UpgradeDef.Efekt efekt) {
        int poziom = 1;
        for (UpgradeDef u : config.ulepszeniaTypu(typ)) {
            if (u.efekt() == efekt) poziom += poziomUlepszenia(instancja.ownerUUID, typ.id(), u) - 1;
        }
        return poziom;
    }

    /** Dodatek z ulepszeń o danym efekcie (drop, XP, maks. naraz, stos, zasięg): naPoziom × kupione poziomy. */
    private double bonus(Instancja instancja, SpawnerTypeDef typ, UpgradeDef.Efekt efekt) {
        double suma = 0;
        for (UpgradeDef u : config.ulepszeniaTypu(typ)) {
            if (u.efekt() == efekt) suma += u.naPoziom() * (poziomUlepszenia(instancja.ownerUUID, typ.id(), u) - 1);
        }
        return suma;
    }

    private int zasieg(Instancja instancja, SpawnerTypeDef typ) {
        return Math.max(1, typ.promienAktywnosci() + (int) Math.round(bonus(instancja, typ, UpgradeDef.Efekt.ZASIEG)));
    }

    /**
     * Termin pierwszego spawnu - zawsze pełny interwał od teraz, nigdy natychmiast.
     * Używane przy postawieniu (onSadzenie) i przy wczytaniu z pliku (wczytaj) - bez tego
     * każdy świeżo postawiony LUB każdy istniejący spawner po restarcie serwera
     * (nastepnySpawnMillis wraca na domyślne 0) strzelał moba w tej samej sekundzie.
     */
    private void zainicjujKolejnySpawn(Instancja instancja) {
        SpawnerTypeDef typ = typ(instancja);
        int poziomSzybkosci = poziomWzoru(instancja, typ, UpgradeDef.Efekt.SZYBKOSC);
        instancja.nastepnySpawnMillis = System.currentTimeMillis() + typ.interwalSekund(poziomSzybkosci) * 1000L;
    }

    // ---- miniatura custom moba w klatce ----

    /**
     * Co kilka ticków: przy spawnerach custom mobów, przy których jest gracz, kręci się miniatura modelu
     * (tworzona przy pierwszym podejściu, usuwana, gdy wszyscy odejdą). Części nie są zapisywane
     * w świecie, więc po wyładowaniu chunka/restarcie tworzymy je od nowa (valid() == false).
     */
    private void tickPodglad() {
        CustomMobService moby = customMoby();
        for (Instancja instancja : instancje.values()) {
            SpawnerTypeDef typ = typ(instancja);
            Location loc = instancja.lokalizacja;
            boolean pokaz = moby != null && typ.custom() && typ.wKlatce() && loc.isChunkLoaded()
                    && loc.getBlock().getType() == Material.SPAWNER && graczBlisko(loc);
            if (!pokaz) {
                usunPodglad(instancja);
                continue;
            }
            if (instancja.podglad != null && !instancja.podglad.valid()) usunPodglad(instancja);
            if (instancja.podglad == null) {
                instancja.podglad = moby.preview(typ.customMob(), loc.clone().add(0.5, 0.12, 0.5), 0.72f);
                if (instancja.podglad == null) continue; // moba nie ma (usunięty z Kreatora)
            }
            instancja.kat = (instancja.kat + PODGLAD_KROK) % 360f;
            instancja.podglad.spin(instancja.kat, PODGLAD_TICKI);
        }
    }

    private static boolean graczBlisko(Location loc) {
        double r2 = PODGLAD_ZASIEG * PODGLAD_ZASIEG;
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(loc) <= r2) return true;
        }
        return false;
    }

    private static void usunPodglad(Instancja instancja) {
        if (instancja.podglad != null) instancja.podglad.remove();
        instancja.podglad = null;
    }

    /**
     * Gdy gracz wychodzi z serwera, znikamy od razu każdą żywą encję, którą trzymał aktywną -
     * czyli tam, gdzie po jego wyjściu nikt inny nie stoi w promieniu/na chunku
     * (patrz gracsAktywujeSpawner). To duplikuje sprzątanie, które i tak zrobi najbliższy
     * tick() (patrz tam) - ale onQuit robi to NATYCHMIAST, zamiast czekać do 1 sekundy na
     * najbliższy przebieg tick().
     *
     * Optymalizacja: NAJPIERW tani filtr matematyczny (odległość do lokalizacji wychodzącego
     * gracza, bez żadnego zapytania do Bukkita) - spawner poza jego zasięgiem aktywacji i tak
     * nie mógł być "trzymany" przez tego gracza, więc jego wyjście nic tam nie zmienia i nie ma
     * sensu w ogóle odpalać gracsAktywujeSpawner (getNearbyPlayers/pętla po graczach) dla niego.
     * Na serwerze z wieloma wyspami to zwykle 0-1 spawnerów zamiast całej mapy instancje.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Location gdzie = event.getPlayer().getLocation();
        UUID wychodzacy = event.getPlayer().getUniqueId();

        for (Instancja instancja : instancje.values()) {
            if (instancja.zywe.isEmpty()) continue;
            SpawnerTypeDef typ = typ(instancja);
            if (!wZasiegu(instancja, typ, gdzie)) continue; // ten gracz i tak nie był w zasięgu tego spawnera
            if (gracsAktywujeSpawner(instancja, typ, wychodzacy)) continue; // ktoś inny nadal tam jest

            wygasZyweMoby(instancja);
        }
    }

    /**
     * Zabicie encji spawnera = zwykły drop (pomnożony przez mnoznik-dropu typu, XP z typu, gdy ustawione).
     * Przy stackowaniu zdejmuje 1 z licznika stosu i - jeśli coś zostało - od razu odradza resztę
     * w tym samym miejscu, żeby dla gracza wyglądało to jak zabijanie mobków jeden po drugim (ważne
     * dla automatycznych farm - bez tego farma "zatykałaby się" po jednym zabiciu, czekając na
     * kolejny cykl spawnu). Priorytet HIGH: dropy custom mobów (mainplugins-mobs, NORMAL) są już wtedy wpisane.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSmierc(EntityDeathEvent event) {
        UUID mobId = event.getEntity().getUniqueId();
        Instancja instancja = mobyDoSpawnera.remove(mobId);
        if (instancja == null) return; // nie nasz spawner-mob
        SpawnerTypeDef typ = typ(instancja);

        mnozDrop(event.getDrops(), Math.max(0, typ.mnoznikDropu() + bonus(instancja, typ, UpgradeDef.Efekt.DROP)));
        if (typ.xp() >= 0) event.setDroppedExp(typ.xp());
        int dodatkoweXp = (int) Math.round(bonus(instancja, typ, UpgradeDef.Efekt.XP));
        if (dodatkoweXp > 0) event.setDroppedExp(event.getDroppedExp() + dodatkoweXp);

        instancja.zywe.remove(mobId);
        if (!typ.stackowanie()) return;
        instancja.rozmiarStosu = Math.max(0, instancja.rozmiarStosu - 1);

        if (instancja.rozmiarStosu > 0) {
            odrodzMoba(instancja, typ, event.getEntity().getLocation());
        }
    }

    /** Mnoży drop: ×2 = dwa razy więcej, ×1,5 = połowa przedmiotów dostaje drugą sztukę, ×0 = bez dropu. */
    static void mnozDrop(List<ItemStack> drops, double mnoznik) {
        if (mnoznik == 1.0) return;
        if (mnoznik <= 0) {
            drops.clear();
            return;
        }
        List<ItemStack> wynik = new ArrayList<>();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (ItemStack item : drops) {
            if (item == null || item.getType().isAir()) continue;
            double dokladnie = item.getAmount() * mnoznik;
            int ile = (int) Math.floor(dokladnie);
            if (r.nextDouble() < dokladnie - ile) ile++;
            int max = item.getMaxStackSize();
            while (ile > 0) {
                ItemStack kopia = item.clone();
                kopia.setAmount(Math.min(max, ile));
                wynik.add(kopia);
                ile -= kopia.getAmount();
            }
        }
        drops.clear();
        drops.addAll(wynik);
    }

    /** Zapasowy pełny zapis na wyłączeniu pluginu - każda zmiana i tak zapisuje się od razu. */
    public void zapiszWszystkie() {
        zapisz();
    }
}
