package elo.mainplugins.spawners;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.util.CustomItemKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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
import java.util.Map;
import java.util.UUID;

/**
 * Sadzenie/zbiory customowych spawnerów - własny, w pełni sterowany harmonogram
 * spawnu (ignoruje światło/graczy w pobliżu itd. jak wanilijski spawner),
 * niezależny od tego, czy mainplugins-skyblock jest w ogóle zainstalowany
 * (bez niego wszystkie spawnery działają na sztywnym poziomie 1).
 */
public class SpawnerManager implements Listener {

    private static class Instancja {
        final Location lokalizacja;
        final SpawnerType typ;
        final UUID ownerUUID;
        long nastepnySpawnMillis;

        // "Stackowanie" mobków - zamiast trzymać osobną żywą encję na każdego moba (to one
        // najbardziej obciążają serwer - AI/pathfinding), spawner ma NAJWYŻEJ JEDNĄ żywą encję
        // na raz. rozmiarStosu to ile "kolejnych" mobków ta jedna encja reprezentuje - każde
        // zabicie zdejmuje 1 (normalny drop za normalne zabicie) i od razu odradza resztę stosu
        // w tym samym miejscu, więc dla gracza czuć to jak zabijanie osobnych mobków pod rząd.
        int rozmiarStosu = 0;
        UUID zywyMobId; // null = obecnie żadna encja nie reprezentuje stosu (czeka na najbliższy cykl)

        Instancja(Location lokalizacja, SpawnerType typ, UUID ownerUUID) {
            this.lokalizacja = lokalizacja;
            this.typ = typ;
            this.ownerUUID = ownerUUID;
        }
    }

    // Promień (bloki) w jakim musi być JAKIŚ gracz, żeby spawner w ogóle próbował spawnować -
    // bez tego spawner mielił bez sensu nawet gdy właściciel jest na drugim końcu mapy
    // (chunk czasem zostaje wczytany z innych powodów niż obecność gracza). Jak w vanilla.
    private static final int PROMIEN_AKTYWNOSCI_GRACZA = 16;

    /** Ile spawnerów łącznie może stać na jednej wyspie. */
    private static final int LIMIT_SPAWNEROW_NA_WYSPE = 10;

    /** Czym się zbiera spawner. */
    private static final Material NARZEDZIE_ZBIERANIA = Material.STICK;

    // Sufiksy kluczy w IslandSummary.spawnerLevels() - MUSZĄ się zgadzać 1:1 z tymi
    // samymi literałami w IslandManager (mainplugins-skyblock). Dwa osobne poziomy
    // per typ spawnera zamiast jednego wspólnego - patrz otworzMenuUlepszenSpawnera.
    private static final String SUFIKS_ILOSC = "_ILOSC";
    private static final String SUFIKS_SZYBKOSC = "_SZYBKOSC";

    private final Plugin plugin;
    private final File plikSpawnerow;
    private final FileConfiguration configSpawnerow;
    private final Map<String, Instancja> instancje = new HashMap<>();
    // Odwrotna mapa mob -> spawner który go wyprodukował, żeby onSmierc wiedział skąd go wypisać
    // bez przeszukiwania wszystkich instancji.
    private final Map<UUID, Instancja> mobyDoSpawnera = new HashMap<>();

    public SpawnerManager(Plugin plugin) {
        this.plugin = plugin;
        this.plikSpawnerow = new File(plugin.getDataFolder(), "spawnery.yml");
        if (!plikSpawnerow.exists()) {
            plikSpawnerow.getParentFile().mkdirs();
            try { plikSpawnerow.createNewFile(); } catch (IOException ignored) {}
        }
        this.configSpawnerow = YamlConfiguration.loadConfiguration(plikSpawnerow);
        wczytaj();

        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
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

            SpawnerType typ;
            try {
                typ = SpawnerType.valueOf(configSpawnerow.getString(path + "type", ""));
            } catch (IllegalArgumentException e) {
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
            configSpawnerow.set(path + "type", instancja.typ.name());
            configSpawnerow.set(path + "owner", instancja.ownerUUID.toString());
        }
        try { configSpawnerow.save(plikSpawnerow); }
        catch (IOException e) { plugin.getLogger().warning("Nie mozna zapisac spawnery.yml!"); }
    }

    private String kluczLokalizacji(Location loc) {
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    @EventHandler
    public void onSadzenie(BlockPlaceEvent event) {
        ItemStack itemUzyty = event.getItemInHand();
        if (itemUzyty.getType() != Material.SPAWNER) return;

        ItemMeta meta = itemUzyty.getItemMeta();
        if (meta == null) return;

        String typName = meta.getPersistentDataContainer().get(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING);
        if (typName == null) return; // zwykły wanilijski spawner (np. z creative) - zostaw jak jest

        SpawnerType typ;
        try {
            typ = SpawnerType.valueOf(typName);
        } catch (IllegalArgumentException e) {
            return;
        }

        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();

        if (block.getState() instanceof CreatureSpawner state) {
            state.setSpawnedType(typ.getEntityType());
            state.setMaxNearbyEntities(0); // wyłącza wanilijski spawn - o spawnie decyduje wyłącznie nasz harmonogram
            state.update(true, false);
        }

        UUID ownerUUID = player.getUniqueId();
        IslandService islandService = CoreAPI.getIslandService();
        if (islandService != null) {
            IslandSummary summary = islandService.getIslandOf(player.getUniqueId());
            if (summary != null) ownerUUID = summary.ownerUUID();
        }

        int juzPostawione = policzSpawneryWyspy(ownerUUID);
        if (juzPostawione >= LIMIT_SPAWNEROW_NA_WYSPE) {
            player.sendMessage(Component.text("Limit spawnerów na wyspę: "
                    + LIMIT_SPAWNEROW_NA_WYSPE + " (masz już " + juzPostawione + ").",
                    NamedTextColor.RED));
            event.setCancelled(true);   // blok nie zostaje postawiony, item wraca do ekwipunku
            return;
        }

        Location loc = block.getLocation();
        Instancja instancja = new Instancja(loc, typ, ownerUUID);
        zainicjujKolejnySpawn(instancja); // pełny interwał, nie spawnuje natychmiast po postawieniu
        instancje.put(kluczLokalizacji(loc), instancja);
        zapisz();

        player.sendMessage(Component.text("Postawiono spawner: " + typ.getNazwaOdmieniona() + "!", NamedTextColor.GREEN));
    }

    /**
     * Zdejmuje spawner z ewidencji i sprząta po nim wszystko: wpis w mapie
     * instancji, wpis w mobyDoSpawnera oraz żywą encję stosu.
     *
     * Bez wyczyszczenia mobyDoSpawnera osierocony mob dalej wskazywałby na
     * usuniętą instancję i przy zabiciu odradzałby kolejne sztuki ze stosu.
     */
    private void usunSpawner(Location loc) {
        Instancja instancja = instancje.remove(kluczLokalizacji(loc));
        if (instancja == null) return;

        if (instancja.zywyMobId != null) {
            mobyDoSpawnera.remove(instancja.zywyMobId);
            Entity mob = Bukkit.getEntity(instancja.zywyMobId);
            if (mob != null) mob.remove();   // remove() nie odpala EntityDeathEvent - żadnych dropów
        }
        instancja.rozmiarStosu = 0;
        zapisz();
    }

    /**
     * Usuwa żywą encję stosu tego spawnera i zeruje kolejkę - używane gdy nikt już nie jest
     * w zasięgu aktywacji (patrz gracsAktywujeSpawner), zarówno z tick() (gracz zwyczajnie
     * odszedł/poszedł na spawn) jak i z onQuit (gracz się wylogował).
     *
     * Zerowanie kolejki (nie tylko usunięcie encji) jest celowe: nastepnySpawnMillis liczy się
     * na realnym czasie i leci dalej w tle niezależnie od aktywności - gdyby kolejka zostawała
     * "należna", po powrocie gracza tick() doliczyłby kolejny pełny cykl NA WIERZCH tego co już
     * czekało (np. 9 starych + 9 nowych = 18 zamiast normalnych 9). Zerowanie daje czysty start.
     */
    private void wygasZywegoMoba(Instancja instancja) {
        Entity mob = Bukkit.getEntity(instancja.zywyMobId);
        if (mob != null) mob.remove(); // bez EntityDeathEvent - zero dropów, zwykłe zniknięcie
        mobyDoSpawnera.remove(instancja.zywyMobId);
        instancja.zywyMobId = null;
        instancja.rozmiarStosu = 0;
    }

    /**
     * Zwykłe niszczenie (kilof, cokolwiek innego) jest zablokowane dla naszych spawnerów -
     * jedyna droga to onZbieranieSpawnera niżej (patyk + PPM), żeby nikt nie stracił
     * spawnera przez przypadkowe/impulsywne rozbicie. Wanilijski spawner (nieśledzony
     * w instancje, np. w lochu) łamie się normalnie - to nie jest nasz teren.
     */
    @EventHandler(ignoreCancelled = true)
    public void onZniszczenie(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.SPAWNER) return;

        Instancja instancja = instancje.get(kluczLokalizacji(event.getBlock().getLocation()));
        if (instancja == null) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text(
                "Nie możesz w ten sposób zniszczyć spawnera! Kliknij go PPM trzymając patyk, żeby go zebrać.",
                NamedTextColor.RED));
    }

    @EventHandler
    public void onZbieranieSpawnera(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Zdarzenie odpala się raz na każdą rękę - bez tego kod poszedłby dwa razy.
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) return;

        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != NARZEDZIE_ZBIERANIA) return;

        Instancja instancja = instancje.get(kluczLokalizacji(block.getLocation()));
        if (instancja == null) return;   // wanilijski spawner - nie nasz, zostaw w spokoju

        // Zbierać może właściciel wyspy albo ktokolwiek z tej samej wyspy.
        UUID wyspaGracza = wyspaGracza(player.getUniqueId());
        if (!instancja.ownerUUID.equals(wyspaGracza)) {
            player.sendMessage(Component.text("To nie jest spawner z Twojej wyspy!", NamedTextColor.RED));
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);   // nie otwieraj GUI spawnera ani nic innego

        Location loc = block.getLocation();
        SpawnerType typ = instancja.typ;

        usunSpawner(loc);
        block.setType(Material.AIR);

        // Item z custom-id, żeby po podniesieniu i postawieniu był tym samym typem.
        ItemStack drop = new ItemStack(Material.SPAWNER);
        ItemMeta meta = drop.getItemMeta();
        meta.displayName(Component.text("Spawner: " + typ.getNazwaOdmieniona(),
                NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        meta.getPersistentDataContainer()
                .set(CustomItemKeys.CUSTOM_ITEM_ID, PersistentDataType.STRING, typ.name());
        meta.setEnchantmentGlintOverride(true);
        drop.setItemMeta(meta);

        loc.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), drop);
        player.sendMessage(Component.text("Zebrano spawner: " + typ.getNazwaOdmieniona() + "!",
                NamedTextColor.GREEN));
    }

    private void tick() {
        long teraz = System.currentTimeMillis();

        for (Instancja instancja : instancje.values()) {
            Location loc = instancja.lokalizacja;
            if (!loc.isChunkLoaded()) continue;
            if (loc.getBlock().getType() != Material.SPAWNER) continue; // usunięty poza BlockBreakEvent (np. explozja)

            // Sprzątanie żywej encji, gdy nikt już nie jest w zasięgu - CO SEKUNDĘ, niezależnie
            // od tego czy akurat minął interwał cyklu (ten warunek jest NIŻEJ). Bez tego czekalibyśmy
            // aż zegar cyklu odpali (kilkanaście-kilkadziesiąt sekund) albo aż zadziała wanilijski
            // despawn (setRemoveWhenFarAway) - a ten nigdy się nie uruchomi, jeśli chunk zdąży się
            // wyładować zanim despawn zdąży zajść (gracz poszedł na spawn/wylogował się i chunk
            // przestaje tickować, zamrażając moba na zawsze zamiast go usuwać).
            if (instancja.zywyMobId != null && !gracsAktywujeSpawner(loc)) {
                wygasZywegoMoba(instancja);
                continue;
            }

            if (teraz < instancja.nastepnySpawnMillis) continue;
            if (!gracsAktywujeSpawner(loc)) continue; // nikt w promieniu ani na tym samym chunku - śpi, nie zużywa cyklu

            // Jeśli żywa encja stosu zniknęła bez zabicia (np. zabita przez coś innego niż nasz
            // onSmierc - rzadkie, ale na wszelki wypadek), tylko zapominamy o niej. rozmiarStosu
            // zostaje - to wciąż "należny" limit temu spawnerowi (gracz jest tu i teraz aktywny,
            // więc to nie jest przypadek z wygasZywegoMoba wyżej).
            if (instancja.zywyMobId != null) {
                Entity mob = Bukkit.getEntity(instancja.zywyMobId);
                if (mob == null || !mob.isValid()) {
                    mobyDoSpawnera.remove(instancja.zywyMobId);
                    instancja.zywyMobId = null;
                }
            }

            int poziomIlosci = pobierzPoziom(instancja, SUFIKS_ILOSC);
            int poziomSzybkosci = pobierzPoziom(instancja, SUFIKS_SZYBKOSC);
            int limit = SpawnerType.LIMIT_KOLEJKI;
            int iloscDoSpawnu = SpawnerType.iloscNaCykl(poziomIlosci);

            if (instancja.rozmiarStosu < limit) {
                instancja.rozmiarStosu = Math.min(limit, instancja.rozmiarStosu + iloscDoSpawnu);
            }
            if (instancja.zywyMobId == null && instancja.rozmiarStosu > 0) {
                odrodzMoba(instancja, losowyPunktObokSpawnera(loc));
            } else {
                // Encja już żyje, ale stos jej urósł w tym cyklu - bez tego nametag pokazywałby
                // starą liczbę aż do najbliższego zabicia (wtedy dopiero leciał respawn z nowym tagiem).
                aktualizujNametag(instancja);
            }

            instancja.nastepnySpawnMillis = teraz + SpawnerType.interwalSekund(poziomSzybkosci) * 1000L;
        }
    }

    /**
     * 1-3 kratki obok spawnera wzdłuż jednej osi (X albo Z), NIGDY na jego bloku -
     * bez sprawdzania podłoża pod spawnem (gracz prosił, żeby respiło się nawet
     * bez żadnego bloku pod spawnem - encje grawitacyjne po prostu spadną).
     */
    private Location losowyPunktObokSpawnera(Location spawnerLoc) {
        boolean osX = Math.random() < 0.5;
        int znak = Math.random() < 0.5 ? -1 : 1;
        int odleglosc = 1 + (int) (Math.random() * 3); // 1-3

        double dx = osX ? znak * odleglosc : 0;
        double dz = osX ? 0 : znak * odleglosc;
        return spawnerLoc.clone().add(0.5 + dx, 1.0, 0.5 + dz);
    }

    /**
     * Spawner jest aktywny, gdy gracz jest w promieniu PROMIEN_AKTYWNOSCI_GRACZA LUB stoi
     * gdziekolwiek na tym samym chunku co spawner - sam promień by nie wystarczył, bo
     * przekątna chunka (16x16) ma ~22,6 bloku, więc gracz w rogu chunka mógłby się nie łapać.
     */
    private boolean gracsAktywujeSpawner(Location loc) {
        return gracsAktywujeSpawner(loc, null);
    }

    /**
     * Tania wersja "czy ten JEDEN znany punkt jest w zasięgu aktywacji spawnera" - bez żadnego
     * zapytania do Bukkita (getNearbyPlayers/getPlayers), sama arytmetyka na współrzędnych.
     * Używana jako wstępny filtr w onQuit, żeby nie sprawdzać spawnerów, na które wyjście
     * akurat TEGO gracza i tak nie ma wpływu.
     */
    private boolean wZasiegu(Location spawnerLoc, Location punkt) {
        if (!spawnerLoc.getWorld().equals(punkt.getWorld())) return false;
        if (spawnerLoc.distanceSquared(punkt) <= (double) PROMIEN_AKTYWNOSCI_GRACZA * PROMIEN_AKTYWNOSCI_GRACZA) return true;

        return (spawnerLoc.getBlockX() >> 4) == (punkt.getBlockX() >> 4)
                && (spawnerLoc.getBlockZ() >> 4) == (punkt.getBlockZ() >> 4);
    }

    /** Jak wyżej, ale pozwala pominąć jednego gracza przy liczeniu - używane przy PlayerQuitEvent,
     *  gdzie w momencie sprawdzania wychodzący gracz wciąż widnieje jako "online". */
    private boolean gracsAktywujeSpawner(Location loc, UUID wyklucz) {
        for (Player gracz : loc.getWorld().getNearbyPlayers(loc, PROMIEN_AKTYWNOSCI_GRACZA)) {
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

    /** Spawnuje encję reprezentującą wierzch stosu tego spawnera i rejestruje ją jako "żywą". */
    private void odrodzMoba(Instancja instancja, Location gdzie) {
        Entity encja = gdzie.getWorld().spawnEntity(gdzie, instancja.typ.getEntityType());
        if (encja instanceof LivingEntity zywa) {
            zywa.setRemoveWhenFarAway(true); // ma despawnować jak zwykły dziki mob (zwierzęta domyślnie by NIE despawnowały)
        }
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
        encja.getPersistentDataContainer().set(CustomItemKeys.SPAWNER_MOB_SOURCE, PersistentDataType.STRING, instancja.typ.name());

        instancja.zywyMobId = encja.getUniqueId();
        mobyDoSpawnera.put(encja.getUniqueId(), instancja);
        aktualizujNametag(instancja);
    }

    /** Odświeża nametag "TypNazwa xN" na żywej encji stosu - albo go chowa, gdy stos ma tylko 1 sztukę. */
    private void aktualizujNametag(Instancja instancja) {
        if (instancja.zywyMobId == null) return;
        if (!(Bukkit.getEntity(instancja.zywyMobId) instanceof LivingEntity zywa)) return;

        if (instancja.rozmiarStosu > 1) {
            zywa.customName(Component.text(instancja.typ.getNazwaPojedyncza() + " x" + instancja.rozmiarStosu, NamedTextColor.YELLOW));
            zywa.setCustomNameVisible(true);
        } else {
            zywa.customName(null);
            zywa.setCustomNameVisible(false);
        }
    }

    /** Ile spawnerów stoi już na wyspie danego właściciela. */
    private int policzSpawneryWyspy(UUID ownerUUID) {
        int licznik = 0;
        for (Instancja i : instancje.values()) {
            if (i.ownerUUID.equals(ownerUUID)) licznik++;
        }
        return licznik;
    }

    /**
     * UUID właściciela wyspy, na której gracz jest członkiem. Gdy modułu wysp
     * nie ma albo gracz nie ma wyspy — zwraca jego własne UUID, tak samo jak
     * robi to onSadzenie() przy przypisywaniu właściciela.
     */
    private UUID wyspaGracza(UUID playerUUID) {
        IslandService islandService = CoreAPI.getIslandService();
        if (islandService == null) return playerUUID;
        IslandSummary summary = islandService.getIslandOf(playerUUID);
        return summary != null ? summary.ownerUUID() : playerUUID;
    }

    private int pobierzPoziom(Instancja instancja, String sufiks) {
        IslandService islandService = CoreAPI.getIslandService();
        if (islandService == null) return 1;

        IslandSummary summary = islandService.getIslandOf(instancja.ownerUUID);
        if (summary == null) return 1;

        return summary.spawnerLevels().getOrDefault(instancja.typ.name() + sufiks, 1);
    }

    /**
     * Termin pierwszego spawnu - zawsze pełny interwał od teraz, nigdy natychmiast.
     * Używane przy postawieniu (onSadzenie) i przy wczytaniu z pliku (wczytaj) - bez tego
     * każdy świeżo postawiony LUB każdy istniejący spawner po restarcie serwera
     * (nastepnySpawnMillis wraca na domyślne 0) strzelał moba w tej samej sekundzie.
     */
    private void zainicjujKolejnySpawn(Instancja instancja) {
        int poziomSzybkosci = pobierzPoziom(instancja, SUFIKS_SZYBKOSC);
        instancja.nastepnySpawnMillis = System.currentTimeMillis() + SpawnerType.interwalSekund(poziomSzybkosci) * 1000L;
    }

    /**
     * Gdy gracz wychodzi z serwera, znikamy od razu każdą żywą encję stosu, którą trzymał
     * aktywną - czyli tam, gdzie po jego wyjściu nikt inny nie stoi w promieniu/na chunku
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
            if (instancja.zywyMobId == null) continue;
            if (!wZasiegu(instancja.lokalizacja, gdzie)) continue; // ten gracz i tak nie był w zasięgu tego spawnera
            if (gracsAktywujeSpawner(instancja.lokalizacja, wychodzacy)) continue; // ktoś inny nadal tam jest

            wygasZywegoMoba(instancja);
        }
    }

    /**
     * Zabicie encji stosu = zwykły, jeden drop (to wciąż normalne, pojedyncze zabicie - nic nie
     * trzeba mnożyć ręcznie) + zdjęcie 1 z licznika stosu. Jeśli w stosie zostało jeszcze coś,
     * od razu odradzamy resztę w tym samym miejscu, żeby dla gracza wyglądało to jak zabijanie
     * mobków jeden po drugim (ważne dla automatycznych farm - bez tego farma "zatykałaby się"
     * po jednym zabiciu, czekając na kolejny cykl spawnu).
     */
    @EventHandler
    public void onSmierc(EntityDeathEvent event) {
        UUID mobId = event.getEntity().getUniqueId();
        Instancja instancja = mobyDoSpawnera.remove(mobId);
        if (instancja == null) return; // nie nasz spawner-mob

        if (mobId.equals(instancja.zywyMobId)) instancja.zywyMobId = null;
        instancja.rozmiarStosu = Math.max(0, instancja.rozmiarStosu - 1);

        if (instancja.rozmiarStosu > 0) {
            odrodzMoba(instancja, event.getEntity().getLocation());
        }
    }

    /** Zapasowy pełny zapis na wyłączeniu pluginu - każda zmiana i tak zapisuje się od razu. */
    public void zapiszWszystkie() {
        zapisz();
    }
}