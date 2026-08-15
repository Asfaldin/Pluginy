package elo.mainplugins.spawners;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.util.CustomItemKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
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

        Location loc = block.getLocation();
        Instancja instancja = new Instancja(loc, typ, ownerUUID);
        zainicjujKolejnySpawn(instancja); // pełny interwał, nie spawnuje natychmiast po postawieniu
        instancje.put(kluczLokalizacji(loc), instancja);
        zapisz();

        player.sendMessage(Component.text("Postawiono spawner: " + typ.getNazwaOdmieniona() + "!", NamedTextColor.GREEN));
    }

    @EventHandler(ignoreCancelled = true)
    public void onZniszczenie(BlockBreakEvent event) {
        if (event.getBlock().getType() != Material.SPAWNER) return;

        String klucz = kluczLokalizacji(event.getBlock().getLocation());
        if (instancje.remove(klucz) != null) {
            zapisz();
        }
    }

    private void tick() {
        long teraz = System.currentTimeMillis();

        for (Instancja instancja : instancje.values()) {
            Location loc = instancja.lokalizacja;
            if (!loc.isChunkLoaded()) continue;
            if (loc.getBlock().getType() != Material.SPAWNER) continue; // usunięty poza BlockBreakEvent (np. explozja)

            if (teraz < instancja.nastepnySpawnMillis) continue;
            if (!gracsAktywujeSpawner(loc)) continue; // nikt w promieniu ani na tym samym chunku - śpi, nie zużywa cyklu

            // Jeśli żywa encja stosu zniknęła bez zabicia (naturalny despawn - setRemoveWhenFarAway
            // nie odpala EntityDeathEvent), tylko zapominamy o niej. rozmiarStosu zostaje - to wciąż
            // "należny" limit temu spawnerowi, po prostu ktoś inny go zaraz odrodzi (poniżej albo w
            // kolejnym cyklu).
            if (instancja.zywyMobId != null) {
                Entity mob = Bukkit.getEntity(instancja.zywyMobId);
                if (mob == null || !mob.isValid()) {
                    mobyDoSpawnera.remove(instancja.zywyMobId);
                    instancja.zywyMobId = null;
                }
            }

            int poziomIlosci = pobierzPoziom(instancja, SUFIKS_ILOSC);
            int poziomSzybkosci = pobierzPoziom(instancja, SUFIKS_SZYBKOSC);
            int limit = SpawnerType.limitPobliskich(poziomIlosci);
            int iloscDoSpawnu = SpawnerType.iloscNaCykl(poziomIlosci);

            if (instancja.rozmiarStosu < limit) {
                instancja.rozmiarStosu = Math.min(limit, instancja.rozmiarStosu + iloscDoSpawnu);
            }
            if (instancja.zywyMobId == null && instancja.rozmiarStosu > 0) {
                odrodzMoba(instancja, losowyPunktObokSpawnera(loc));
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
        if (!loc.getWorld().getNearbyPlayers(loc, PROMIEN_AKTYWNOSCI_GRACZA).isEmpty()) return true;

        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        for (Player gracz : loc.getWorld().getPlayers()) {
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
        encja.getPersistentDataContainer().set(CustomItemKeys.SPAWNER_MOB_SOURCE, PersistentDataType.STRING, instancja.typ.name());

        if (instancja.rozmiarStosu > 1 && encja instanceof LivingEntity zywa) {
            zywa.customName(Component.text(instancja.typ.getNazwaPojedyncza() + " x" + instancja.rozmiarStosu, NamedTextColor.YELLOW));
            zywa.setCustomNameVisible(true);
        }

        instancja.zywyMobId = encja.getUniqueId();
        mobyDoSpawnera.put(encja.getUniqueId(), instancja);
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