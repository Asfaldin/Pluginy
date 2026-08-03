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

        Instancja(Location lokalizacja, SpawnerType typ, UUID ownerUUID) {
            this.lokalizacja = lokalizacja;
            this.typ = typ;
            this.ownerUUID = ownerUUID;
        }
    }

    // Promień (bloki) w jakim liczymy "pobliskie" mobki tego samego typu pod kątem limitu.
    private static final int PROMIEN_LICZENIA = 12;

    // Sufiksy kluczy w IslandSummary.spawnerLevels() - MUSZĄ się zgadzać 1:1 z tymi
    // samymi literałami w IslandManager (mainplugins-skyblock). Dwa osobne poziomy
    // per typ spawnera zamiast jednego wspólnego - patrz otworzMenuUlepszenSpawnera.
    private static final String SUFIKS_ILOSC = "_ILOSC";
    private static final String SUFIKS_SZYBKOSC = "_SZYBKOSC";

    private final Plugin plugin;
    private final File plikSpawnerow;
    private final FileConfiguration configSpawnerow;
    private final Map<String, Instancja> instancje = new HashMap<>();

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

            instancje.put(kluczLokalizacji(loc), new Instancja(loc, typ, ownerUUID));
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
        instancje.put(kluczLokalizacji(loc), new Instancja(loc, typ, ownerUUID));
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

            int poziomIlosci = pobierzPoziom(instancja, SUFIKS_ILOSC);
            int poziomSzybkosci = pobierzPoziom(instancja, SUFIKS_SZYBKOSC);
            int limit = SpawnerType.limitPobliskich(poziomIlosci);
            int iloscDoSpawnu = SpawnerType.iloscNaCykl(poziomIlosci);

            long pobliskich = loc.getWorld().getNearbyEntities(loc, PROMIEN_LICZENIA, PROMIEN_LICZENIA, PROMIEN_LICZENIA)
                    .stream()
                    .filter(e -> e.getType() == instancja.typ.getEntityType())
                    .count();

            if (pobliskich < limit) {
                for (int i = 0; i < iloscDoSpawnu && pobliskich + i < limit; i++) {
                    Entity encja = loc.getWorld().spawnEntity(losowyPunktObokSpawnera(loc), instancja.typ.getEntityType());
                    encja.setPersistent(true);
                    encja.getPersistentDataContainer().set(CustomItemKeys.SPAWNER_MOB_SOURCE, PersistentDataType.STRING, instancja.typ.name());
                }
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

    private int pobierzPoziom(Instancja instancja, String sufiks) {
        IslandService islandService = CoreAPI.getIslandService();
        if (islandService == null) return 1;

        IslandSummary summary = islandService.getIslandOf(instancja.ownerUUID);
        if (summary == null) return 1;

        return summary.spawnerLevels().getOrDefault(instancja.typ.name() + sufiks, 1);
    }

    /**
     * Dodatkowy custom drop dla królików z tego spawnera - nie dotyczy dzikich
     * królików spawnujących się naturalnie w świecie (te nie mają PDC taga).
     */
    @EventHandler
    public void onSmierc(EntityDeathEvent event) {
        String source = event.getEntity().getPersistentDataContainer().get(CustomItemKeys.SPAWNER_MOB_SOURCE, PersistentDataType.STRING);
        if (source == null) return;

        if (SpawnerType.RABBIT.name().equals(source)) {
            event.getDrops().add(new ItemStack(Material.BLAZE_ROD, 10));
        }
    }

    /** Zapasowy pełny zapis na wyłączeniu pluginu - każda zmiana i tak zapisuje się od razu. */
    public void zapiszWszystkie() {
        zapisz();
    }
}