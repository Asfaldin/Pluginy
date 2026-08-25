package elo.mainplugins.skyblock;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.SpawnService;
import elo.mainplugins.core.world.VoidGenerator;
import elo.mainplugins.skyblock.config.IslandConfigLoader;
import elo.mainplugins.skyblock.config.IslandTuning;
import elo.mainplugins.skyblock.config.SpawnerTyp;
import elo.mainplugins.skyblock.event.IslandBankDepositEvent;
import elo.mainplugins.skyblock.event.IslandCreatedEvent;
import elo.mainplugins.skyblock.event.IslandMemberJoinedEvent;
import elo.mainplugins.skyblock.event.IslandUpgradeEvent;
import elo.mainplugins.skyblock.gui.IslandGuiButton;
import elo.mainplugins.skyblock.gui.IslandGuiContent;
import elo.mainplugins.skyblock.gui.IslandGuiLoader;
import elo.mainplugins.skyblock.gui.IslandScreen;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IslandManager implements Listener, IslandService {

    private final Plugin plugin;
    private final World skyblockWorld;
    private final File schemFile;
    private final EconomyService economyManager;

    // Cala liczbowa/danych konfiguracja (koszty, promienie, timeouty, typy spawnerow...) i
    // caly uklad GUI (sloty/ikony/teksty) - wczytywane z wyspy-config.yml/wyspy-gui.yml,
    // podmieniane w calosci przy /@reloadwyspy (patrz przeladujKonfiguracje). NIE final -
    // to jedyny powod, dla ktorego to pole nie jest static final jak dawne stale.
    private IslandTuning tuning;
    private IslandGuiContent gui;

    // Mapa pamiętająca, czy gracz wszedł do GUI z komendy /menu
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    public static class IslandData {
        private final int id;
        private final UUID ownerUUID;
        private final int centerX;
        private final int centerZ;
        private int borderSize;
        private final Set<UUID> members = new HashSet<>();

        // allowBreak/allowPvP/allowMobs dotyczą WYŁĄCZNIE gości - właściciel i członkowie
        // zawsze mogą budować/niszczyć na własnej wyspie niezależnie od tych ustawień
        // (patrz IslandProtectionManager). allowBreak domyślnie false - wyspa jest
        // prywatna od razu po stworzeniu, właściciel musi ją świadomie otworzyć.
        private boolean allowMobs = true;
        private boolean allowPvP = false;
        private boolean allowBreak = false;
        private boolean visualBorder = true; // Nowa zmienna do wizualnego borderu

        // Podobnie jak allowBreak/allowPvP/allowMobs wyżej - dotyczą WYŁĄCZNIE gości,
        // domyślnie zablokowane (wyspa prywatna od razu po stworzeniu).
        private boolean allowGuestMobKill = false;
        private boolean allowItemPickup = false;
        private boolean allowContainerAccess = false;
        private boolean allowInteract = false;

        // "Pogoda i Czas" - kosmetyczny przełącznik w Ustawieniach Wyspy, wymusza
        // zawsze czyste niebo i południe (patrz IslandManager.aplikujPogodeICzas)
        // dla KAŻDEGO gracza fizycznie stojącego na tej wyspie, nie tylko gości.
        private boolean weatherLocked = false;

        // Kosmetyczna nazwa wyspy ustawiana przez właściciela/admina (przycisk "Nazwa Wyspy"
        // w Ustawieniach Wyspy) - null dopóki nikt jej nie ustawi, wtedy GUI pokazuje
        // domyślnie nick właściciela. Celowo NIE wchodzi do IslandSummary/Topki Wysp -
        // to osobna zmiana obejmująca współdzielone API w mainplugins-core i HUD.
        private String customName;

        // Wspólna kasa wyspy - ZASTĘPUJE osobisty portfel jako JEDYNE źródło pieniędzy
        // na ulepszenia (border, spawnery - patrz uprosGranice/ulepszSpawnerStatystyke).
        // Wpłaca każdy stojący fizycznie na tej wyspie (właściciel/członek/gość -
        // patrz /is wplac), wypłaca wyłącznie właściciel/admin (/is wyplac).
        private double bankBalance = 0.0;

        // Wartość wyspy licznona z postawionych bloków (patrz IslandManager.wartoscBloku) -
        // aktualizowana przyrostowo przy każdym BlockBreakEvent/BlockPlaceEvent na terenie
        // wyspy (IslandProtectionManager), a nie skanowana na żądanie - pełne skanowanie
        // terenu przy każdym odświeżeniu Topki Wysp byłoby zbyt kosztowne.
        private double worth = 0.0;

        // Poziom (domyślnie 1) każdego typu customowego spawnera wykupionego przez
        // właściciela wyspy - klucz to SpawnerType.name() z mainplugins-spawners,
        // ale IslandData celowo trzyma go jako zwykły String (patrz komentarz w
        // IslandSummary) - skyblock nie ma i nie powinien mieć zależności na moduł spawnerów.
        private final Map<String, Integer> spawnerLevels = new HashMap<>();

        // Własny punkt teleportu ustawiony przez /is ustawdom - null dopóki gracz go nie
        // ustawi, wtedy teleportDoWyspy() używa domyślnego środka wyspy zamiast tego.
        // To jest cel /dom i /home - DRUGI, niezależny punkt teleportu, patrz spawnX niżej.
        private Double homeX;
        private Double homeY;
        private Double homeZ;
        private float homeYaw;
        private float homePitch;

        // Osobny punkt teleportu ustawiany przez /is ustawspawn - to jest cel gołego
        // /is (bez argumentów), NIEZALEŻNY od /is ustawdom/homeX wyżej. Dwie możliwości
        // "respienia się" na wyspie: /is (ustawspawn) i /dom-/home (ustawdom) - jak w
        // vanillowym Minecrafcie łóżko vs. respawn anchor, tylko oba na tej samej wyspie.
        private Double spawnX;
        private Double spawnY;
        private Double spawnZ;
        private float spawnYaw;
        private float spawnPitch;

        public IslandData(int id, UUID ownerUUID, int centerX, int centerZ, int borderSize) {
            this.id = id;
            this.ownerUUID = ownerUUID;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.borderSize = borderSize;
        }

        public int getId() { return id; }
        public UUID getOwnerUUID() { return ownerUUID; }
        public int getCenterX() { return centerX; }
        public int getCenterZ() { return centerZ; }
        public int getBorderSize() { return borderSize; }
        public void setBorderSize(int borderSize) { this.borderSize = borderSize; }
        public Set<UUID> getMembers() { return members; }

        public boolean isAllowMobs() { return allowMobs; }
        public void setAllowMobs(boolean allowMobs) { this.allowMobs = allowMobs; }
        public boolean isAllowPvP() { return allowPvP; }
        public void setAllowPvP(boolean allowPvP) { this.allowPvP = allowPvP; }
        public boolean isAllowBreak() { return allowBreak; }
        public void setAllowBreak(boolean allowBreak) { this.allowBreak = allowBreak; }
        public boolean isVisualBorder() { return visualBorder; }
        public void setVisualBorder(boolean visualBorder) { this.visualBorder = visualBorder; }

        public boolean isAllowGuestMobKill() { return allowGuestMobKill; }
        public void setAllowGuestMobKill(boolean allowGuestMobKill) { this.allowGuestMobKill = allowGuestMobKill; }
        public boolean isAllowItemPickup() { return allowItemPickup; }
        public void setAllowItemPickup(boolean allowItemPickup) { this.allowItemPickup = allowItemPickup; }
        public boolean isAllowContainerAccess() { return allowContainerAccess; }
        public void setAllowContainerAccess(boolean allowContainerAccess) { this.allowContainerAccess = allowContainerAccess; }
        public boolean isAllowInteract() { return allowInteract; }
        public void setAllowInteract(boolean allowInteract) { this.allowInteract = allowInteract; }
        public boolean isWeatherLocked() { return weatherLocked; }
        public void setWeatherLocked(boolean weatherLocked) { this.weatherLocked = weatherLocked; }
        public String getCustomName() { return customName; }
        public void setCustomName(String customName) { this.customName = customName; }

        public double getBankBalance() { return bankBalance; }
        public void setBankBalance(double bankBalance) { this.bankBalance = bankBalance; }
        public boolean maWystarczajacoWBanku(double kwota) { return bankBalance >= kwota; }
        public void dodajDoBanku(double kwota) { bankBalance += kwota; }
        /** Zwraca false (i nic nie zmienia) jeśli w banku brakuje środków - wołający musi sprawdzić wynik. */
        public boolean odejmijZBanku(double kwota) {
            if (bankBalance < kwota) return false;
            bankBalance -= kwota;
            return true;
        }

        public double getWorth() { return worth; }
        public void setWorth(double worth) { this.worth = worth; }
        public void dodajDoWartosci(double delta) { worth = Math.max(0, worth + delta); }

        public Map<String, Integer> getSpawnerLevels() { return spawnerLevels; }
        public int getSpawnerLevel(String typ) { return spawnerLevels.getOrDefault(typ, 1); }
        public void setSpawnerLevel(String typ, int level) { spawnerLevels.put(typ, level); }

        public boolean hasCustomHome() { return homeX != null; }
        public double getHomeX() { return homeX; }
        public double getHomeY() { return homeY; }
        public double getHomeZ() { return homeZ; }
        public float getHomeYaw() { return homeYaw; }
        public float getHomePitch() { return homePitch; }
        public void setHome(double x, double y, double z, float yaw, float pitch) {
            this.homeX = x;
            this.homeY = y;
            this.homeZ = z;
            this.homeYaw = yaw;
            this.homePitch = pitch;
        }

        public boolean hasCustomSpawn() { return spawnX != null; }
        public double getSpawnX() { return spawnX; }
        public double getSpawnY() { return spawnY; }
        public double getSpawnZ() { return spawnZ; }
        public float getSpawnYaw() { return spawnYaw; }
        public float getSpawnPitch() { return spawnPitch; }
        public void setSpawn(double x, double y, double z, float yaw, float pitch) {
            this.spawnX = x;
            this.spawnY = y;
            this.spawnZ = z;
            this.spawnYaw = yaw;
            this.spawnPitch = pitch;
        }

        // Rola WYŁĄCZNIE dla członków spoza właściciela - właściciel nigdy nie jest kluczem
        // w tej mapie, jego status wynika zawsze z porównania UUID z ownerUUID (patrz mozeZarzadzac).
        private final Map<UUID, IslandRole> memberRoles = new HashMap<>();
        public IslandRole getRole(UUID uuid) { return memberRoles.getOrDefault(uuid, IslandRole.CZLONEK); }
        public void setRole(UUID uuid, IslandRole role) { memberRoles.put(uuid, role); }
        public Map<UUID, IslandRole> getMemberRoles() { return memberRoles; }
    }

    /** Ranga członka wyspy (nie dotyczy właściciela - patrz komentarz przy IslandData.memberRoles). */
    public enum IslandRole { CZLONEK, ADMIN }

    /** Ile razy gracz w sumie utworzył wyspę (create+delete się liczy) i kiedy ostatnio - patrz historiaTworzeniaWysp. */
    private static class HistoriaTworzenia {
        int ilosc;
        long ostatnieMillis;
    }

    // MUSZĄ się zgadzać 1:1 z tymi samymi literałami w SpawnerManager (mainplugins-spawners) -
    // identyfikatory protokołu miedzy pluginami, nie "tresc" do edycji.
    private static final String SUFIKS_ILOSC = "_ILOSC";
    private static final String SUFIKS_SZYBKOSC = "_SZYBKOSC";

    // Maksymalny rozmiar WorldBordera dopuszczalny przez samego Minecrafta to
    // 5.9999968E7 (59 999 968) - wartość NIECO mniejsza niż okrągłe 60 milionów.
    // Wcześniejsze 60000000 przekraczało ten limit o 32 i wywalało IllegalArgumentException
    // przy każdym /is border i usuwaniu wyspy. Używamy tego jako "brak borderu". To twardy
    // limit silnika Minecrafta, nie wartość do strojenia - celowo NIE w wyspy-config.yml.
    private static final double ROZMIAR_BEZ_BORDERU = 59999900;

    private final Map<UUID, IslandData> islandDatabase = new HashMap<>();
    private final Map<UUID, UUID> playerIslandMap = new HashMap<>();
    // ConcurrentHashMap-backed (nie HashSet) - te trzy są czytane/zapisywane też
    // z onChat(), który odpala się na wątku czatu, a nie głównym wątku serwera
    // (patrz AsyncPlayerChatEvent). pendingLeaveConfirmation nie potrzebuje tego,
    // bo dotyka go wyłącznie kod z głównego wątku (kliknięcia w GUI).
    private final Set<UUID> pendingDeleteConfirmation = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingLeaveConfirmation = new HashSet<>();
    private final Set<UUID> pendingInviteChat = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingNameChat = ConcurrentHashMap.newKeySet();
    // Zapamiętuje, który slot w GUI "Członkowie Wyspy" odpowiada za którego gracza
    private final Map<UUID, Map<Integer, UUID>> slotyCzlonkow = new HashMap<>();
    // Który typ spawnera gracz aktualnie ma otwarty w podmenu "Spawner: X" (Ilość/Szybkość)
    private final Map<UUID, String> otwartySpawnerTyp = new HashMap<>();
    // Historia tworzenia wysp per gracz - anty-spam cooldown na create/delete (patrz kolejnyCooldownTworzenia).
    // Liczba NIGDY się nie zeruje - to celowo licznik na całe życie konta, nie okno czasowe.
    private final Map<UUID, HistoriaTworzenia> historiaTworzeniaWysp = new HashMap<>();
    private int nextIslandId = 0;

    private final File plikWysp;
    private final FileConfiguration configWysp;
    private final AsyncConfigSaver saverWysp;

    public IslandManager(Plugin plugin, EconomyService economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;

        this.tuning = IslandConfigLoader.load(plugin);
        this.gui = IslandGuiLoader.load(plugin);

        WorldCreator wc = new WorldCreator("skyblock_world");
        wc.generator(new VoidGenerator());
        this.skyblockWorld = Bukkit.createWorld(wc);

        File faweFolder = new File(plugin.getDataFolder().getParentFile(), "FastAsyncWorldEdit/schematics");
        this.schemFile = new File(faweFolder, "wyspa_startowa.schem");

        this.plikWysp = new File(plugin.getDataFolder(), "wyspy.yml");
        if (!plikWysp.exists()) {
            plikWysp.getParentFile().mkdirs();
            try { plikWysp.createNewFile(); } catch (IOException ignored) {}
        }
        this.configWysp = YamlConfiguration.loadConfiguration(plikWysp);
        this.saverWysp = new AsyncConfigSaver(plugin, configWysp, plikWysp, 30);
        wczytajWyspy();
        wczytajHistorieTworzenia();
    }

    /** Wywoływane przez /@reloadwyspy - podmienia liczbową konfigurację i cały układ GUI bez restartu serwera. */
    public void przeladujKonfiguracje() {
        this.tuning = IslandConfigLoader.load(plugin);
        this.gui = IslandGuiLoader.load(plugin);
    }

    /** Używane przez IslandProtectionManager - żeby nie duplikować wczytywania configu w każdej klasie. */
    public IslandTuning getTuning() {
        return tuning;
    }

    /**
     * Wczytuje wszystkie wyspy (właścicieli, członków, rozmiar, ustawienia) z wyspy.yml
     * przy starcie pluginu - bez tego cała baza wysp żyła tylko w pamięci i znikała
     * po każdym restarcie serwera, mimo że same bloki zostawały w świecie.
     */
    private void wczytajWyspy() {
        nextIslandId = configWysp.getInt("nextIslandId", 0);

        ConfigurationSection sekcja = configWysp.getConfigurationSection("wyspy");
        if (sekcja == null) return;

        for (String ownerKey : sekcja.getKeys(false)) {
            UUID ownerUUID;
            try {
                ownerUUID = UUID.fromString(ownerKey);
            } catch (IllegalArgumentException e) {
                continue; // uszkodzony/ręcznie zepsuty wpis - pomijamy zamiast wywalać cały load
            }

            String path = "wyspy." + ownerKey + ".";
            int id = configWysp.getInt(path + "id", nextIslandId);
            int centerX = configWysp.getInt(path + "centerX");
            int centerZ = configWysp.getInt(path + "centerZ");
            int borderSize = configWysp.getInt(path + "borderSize", tuning.domyslnyRozmiarWyspy());

            IslandData data = new IslandData(id, ownerUUID, centerX, centerZ, borderSize);
            data.setAllowMobs(configWysp.getBoolean(path + "allowMobs", true));
            data.setAllowPvP(configWysp.getBoolean(path + "allowPvP", false));
            data.setAllowBreak(configWysp.getBoolean(path + "allowBreak", false));
            data.setVisualBorder(configWysp.getBoolean(path + "visualBorder", true));
            data.setAllowGuestMobKill(configWysp.getBoolean(path + "allowGuestMobKill", false));
            data.setAllowItemPickup(configWysp.getBoolean(path + "allowItemPickup", false));
            data.setAllowContainerAccess(configWysp.getBoolean(path + "allowContainerAccess", false));
            data.setAllowInteract(configWysp.getBoolean(path + "allowInteract", false));
            data.setWeatherLocked(configWysp.getBoolean(path + "weatherLocked", false));
            data.setCustomName(configWysp.getString(path + "customName"));
            data.setBankBalance(configWysp.getDouble(path + "bankBalance", 0.0));
            data.setWorth(configWysp.getDouble(path + "worth", 0.0));

            ConfigurationSection spawnerSekcja = configWysp.getConfigurationSection(path + "spawnerLevels");
            if (spawnerSekcja != null) {
                for (String typKey : spawnerSekcja.getKeys(false)) {
                    data.setSpawnerLevel(typKey, spawnerSekcja.getInt(typKey, 1));
                }
            }

            if (configWysp.contains(path + "home.x")) {
                data.setHome(
                        configWysp.getDouble(path + "home.x"),
                        configWysp.getDouble(path + "home.y"),
                        configWysp.getDouble(path + "home.z"),
                        (float) configWysp.getDouble(path + "home.yaw", 0),
                        (float) configWysp.getDouble(path + "home.pitch", 0)
                );
            }

            if (configWysp.contains(path + "spawn.x")) {
                data.setSpawn(
                        configWysp.getDouble(path + "spawn.x"),
                        configWysp.getDouble(path + "spawn.y"),
                        configWysp.getDouble(path + "spawn.z"),
                        (float) configWysp.getDouble(path + "spawn.yaw", 0),
                        (float) configWysp.getDouble(path + "spawn.pitch", 0)
                );
            }

            for (String memberStr : configWysp.getStringList(path + "czlonkowie")) {
                try {
                    UUID memberUUID = UUID.fromString(memberStr);
                    data.getMembers().add(memberUUID);
                    playerIslandMap.put(memberUUID, ownerUUID);
                } catch (IllegalArgumentException ignored) {}
            }

            ConfigurationSection roleSekcja = configWysp.getConfigurationSection(path + "role");
            if (roleSekcja != null) {
                for (String memberStr : roleSekcja.getKeys(false)) {
                    try {
                        data.setRole(UUID.fromString(memberStr), IslandRole.valueOf(roleSekcja.getString(memberStr, "CZLONEK")));
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            islandDatabase.put(ownerUUID, data);
            playerIslandMap.put(ownerUUID, ownerUUID);
        }

        plugin.getLogger().info("Wczytano " + islandDatabase.size() + " wysp z wyspy.yml.");
    }

    /** Zapisuje pełny, aktualny stan wszystkich wysp na dysk. Wołane po każdej zmianie. */
    private void zapiszWyspy() {
        configWysp.set("wyspy", null); // czyścimy stare wpisy, żeby usunięte wyspy nie zostawały w pliku
        configWysp.set("nextIslandId", nextIslandId);

        for (Map.Entry<UUID, IslandData> entry : islandDatabase.entrySet()) {
            String path = "wyspy." + entry.getKey() + ".";
            IslandData data = entry.getValue();

            configWysp.set(path + "id", data.getId());
            configWysp.set(path + "centerX", data.getCenterX());
            configWysp.set(path + "centerZ", data.getCenterZ());
            configWysp.set(path + "borderSize", data.getBorderSize());
            configWysp.set(path + "allowMobs", data.isAllowMobs());
            configWysp.set(path + "allowPvP", data.isAllowPvP());
            configWysp.set(path + "allowBreak", data.isAllowBreak());
            configWysp.set(path + "visualBorder", data.isVisualBorder());
            configWysp.set(path + "allowGuestMobKill", data.isAllowGuestMobKill());
            configWysp.set(path + "allowItemPickup", data.isAllowItemPickup());
            configWysp.set(path + "allowContainerAccess", data.isAllowContainerAccess());
            configWysp.set(path + "allowInteract", data.isAllowInteract());
            configWysp.set(path + "weatherLocked", data.isWeatherLocked());
            configWysp.set(path + "customName", data.getCustomName());
            configWysp.set(path + "bankBalance", data.getBankBalance());
            configWysp.set(path + "worth", data.getWorth());

            if (data.hasCustomHome()) {
                configWysp.set(path + "home.x", data.getHomeX());
                configWysp.set(path + "home.y", data.getHomeY());
                configWysp.set(path + "home.z", data.getHomeZ());
                configWysp.set(path + "home.yaw", data.getHomeYaw());
                configWysp.set(path + "home.pitch", data.getHomePitch());
            }

            if (data.hasCustomSpawn()) {
                configWysp.set(path + "spawn.x", data.getSpawnX());
                configWysp.set(path + "spawn.y", data.getSpawnY());
                configWysp.set(path + "spawn.z", data.getSpawnZ());
                configWysp.set(path + "spawn.yaw", data.getSpawnYaw());
                configWysp.set(path + "spawn.pitch", data.getSpawnPitch());
            }

            for (Map.Entry<String, Integer> lvl : data.getSpawnerLevels().entrySet()) {
                configWysp.set(path + "spawnerLevels." + lvl.getKey(), lvl.getValue());
            }

            List<String> czlonkowie = new ArrayList<>();
            for (UUID member : data.getMembers()) czlonkowie.add(member.toString());
            configWysp.set(path + "czlonkowie", czlonkowie);

            configWysp.set(path + "role", null); // czyścimy, tak samo jak "wyspy" wyżej - usunięci/zdegradowani członkowie nie mają zostawać
            for (Map.Entry<UUID, IslandRole> role : data.getMemberRoles().entrySet()) {
                configWysp.set(path + "role." + role.getKey(), role.getValue().name());
            }
        }

        // Historia tworzenia (cooldown anty-spam) - NIE czyścimy sekcji przed zapisem jak "wyspy"
        // wyżej, bo wpisy tu żyją niezależnie od tego czy gracz aktualnie ma wyspę.
        for (Map.Entry<UUID, HistoriaTworzenia> entry : historiaTworzeniaWysp.entrySet()) {
            String path = "historiaTworzenia." + entry.getKey() + ".";
            configWysp.set(path + "ilosc", entry.getValue().ilosc);
            configWysp.set(path + "ostatnie", entry.getValue().ostatnieMillis);
        }

        saverWysp.oznaczZmiane();
    }

    /** Wywołaj w onDisable() modułu skyblock - zapisuje natychmiast, zatrzymuje cykl. */
    public void zamknij() {
        saverWysp.zamknij();
    }

    /** Wczytuje historię tworzenia wysp (cooldown anty-spam) - osobno od wczytajWyspy(), bo dotyczy
     *  graczy niezależnie od tego czy aktualnie mają wyspę (ta metoda ma early-return gdy jej brak). */
    private void wczytajHistorieTworzenia() {
        ConfigurationSection sekcja = configWysp.getConfigurationSection("historiaTworzenia");
        if (sekcja == null) return;

        for (String key : sekcja.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                HistoriaTworzenia historia = new HistoriaTworzenia();
                historia.ilosc = configWysp.getInt("historiaTworzenia." + key + ".ilosc", 0);
                historia.ostatnieMillis = configWysp.getLong("historiaTworzenia." + key + ".ostatnie", 0L);
                historiaTworzeniaWysp.put(uuid, historia);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    /**
     * /is i /dom(-home) [subkomenda] - ten sam handler dla obu komend (patrz
     * MainpluginsSkyblock), rozróżniane przez `nazwaKomendy` WYŁĄCZNIE przy pustych
     * argumentach (albo z samym "zmenu"): "/is" samo w sobie teleportuje do punktu
     * ustawionego przez /is ustawspawn, a "/dom"/"/home" zawsze do punktu ustawionego
     * przez /is ustawdom - dwa niezależne, ustawialne miejsca "respienia się" na
     * wyspie. Brak własnej wyspy w obu przypadkach - tworzy nową. Reszta subkomend
     * (w tym samo "/is dom"/"/is ustawdom") działa identycznie niezależnie od tego,
     * którą z dwóch komend wpisano - to odpowiedniki przycisków z GUI, dla graczy,
     * którzy wolą wpisać komendę niż klikać w menu.
     */
    public void handleCommand(Player player, String[] args, String nazwaKomendy) {
        UUID uuid = player.getUniqueId();
        boolean zMenu = (args.length > 0 && args[args.length - 1].equalsIgnoreCase("zmenu"));
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        // Polska nazwa jako forma główna, angielska zostawiona jako alias (ten sam
        // wzorzec co /przelej-/pay, /wycisz-/mute itd. - patrz reszta komend serwera).
        // "menu" i "pvp" celowo bez polskiego odpowiednika - to nie są prawdziwe
        // angielskie słowa tylko uniwersalne, powszechnie używane w tej formie terminy.
        switch (sub) {
            case "menu" -> otworzMenuWyspy(player, zMenu);
            case "usun" -> obslugaUsuwaniaKomenda(player);
            case "granica", "border" -> przelaczWizualnyBorder(player);
            case "budowanie", "guests", "build" -> przelaczBudowanieDlaGosci(player);
            case "pvp" -> przelaczPvP(player);
            case "potwory", "mobs" -> przelaczPotwory(player);
            case "ulepszenia", "upgrade" -> otworzMenuUlepszen(player);
            case "czlonkowie", "members" -> otworzMenuCzlonkow(player);
            case "ustawienia", "settings" -> otworzMenuUstawienWyspy(player);
            case "permisje", "permissions" -> otworzMenuPermisji(player);
            case "zapros", "add", "invite" -> zaprosGracza(player, args);
            case "akceptuj", "accept" -> zaakceptujZaproszenie(player);
            case "odrzuc", "deny" -> odrzucZaproszenie(player);
            case "opusc", "leave" -> opuscWyspe(player);
            case "awansuj", "promote" -> zmienRoleKomenda(player, args, IslandRole.ADMIN);
            case "degraduj", "demote" -> zmienRoleKomenda(player, args, IslandRole.CZLONEK);
            case "wyrzuc", "remove" -> usunCzlonkaKomenda(player, args);
            case "dom", "home" -> teleportDoWyspy(player);
            case "ustawdom", "sethome" -> ustawDomek(player);
            case "ustawspawn" -> ustawSpawnWyspy(player);
            case "wplac", "deposit" -> wplacDoBankuKomenda(player, args);
            case "wyplac", "withdraw" -> wyplacZBankuKomenda(player, args);
            default -> {
                if (!playerIslandMap.containsKey(uuid)) {
                    stworzWyspe(player, zMenu);
                } else if (nazwaKomendy.equalsIgnoreCase("dom")) {
                    teleportDoWyspy(player);
                } else {
                    teleportDoSpawnuWyspy(player);
                }
            }
        }
    }

    /** Wspólne dla GUI (kosz) i komendy (/is usun) - potwierdzenie wygasa po timeouty.potwierdzenie-sekundy. */
    private void ustawOczekiwanieNaPotwierdzenie(UUID uuid) {
        pendingDeleteConfirmation.add(uuid);
        Bukkit.getScheduler().runTaskLater(plugin, () -> pendingDeleteConfirmation.remove(uuid), tuning.timeoutPotwierdzeniaTicks());
    }

    /**
     * Ten sam komunikat dla obu wejść (GUI kosz i komenda /is usun) - obie kończą się
     * dokładnie tym samym potwierdzeniem na czacie (patrz onChat), więc gracz musi
     * wpisać "Tak zgadzam się" niezależnie od tego, którędy tu trafił.
     */
    private void wyslijOstrzezenieUsuniecia(Player player) {
        player.sendMessage(Component.text("UWAGA: Czy na pewno chcesz usunąć swoją wyspę? Tej operacji nie da się cofnąć!", NamedTextColor.RED, TextDecoration.BOLD));
        player.sendMessage(Component.text("Wpisz \"Tak zgadzam się\" na czacie w ciągu 15 sekund, aby potwierdzić (cokolwiek innego anuluje).", NamedTextColor.YELLOW));
    }

    private void obslugaUsuwaniaKomenda(Player player) {
        UUID uuid = player.getUniqueId();
        if (!playerIslandMap.containsKey(uuid)) {
            player.sendMessage(Component.text("Nie posiadasz wyspy!", NamedTextColor.RED));
            return;
        }
        if (!uuid.equals(playerIslandMap.get(uuid))) {
            player.sendMessage(Component.text("Tylko właściciel może usunąć wyspę!", NamedTextColor.RED));
            return;
        }

        ustawOczekiwanieNaPotwierdzenie(uuid);
        wyslijOstrzezenieUsuniecia(player);
    }

    private IslandData wlasnaWyspaLubKomunikat(Player player) {
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null) player.sendMessage(Component.text("Nie posiadasz wyspy!", NamedTextColor.RED));
        return data;
    }

    /** Właściciel wyspy ZAWSZE ma pełne uprawnienia zarządzania - niezależnie od (braku) wpisu w memberRoles. */
    private boolean mozeZarzadzac(UUID uuid, IslandData data) {
        return data.getOwnerUUID().equals(uuid) || data.getRole(uuid) == IslandRole.ADMIN;
    }

    /**
     * Jedyne miejsce sprawdzające "czy gracz może zarządzać SWOJĄ wyspą" (właściciel lub admin) -
     * używane identycznie przez komendy /is i kliknięcia w GUI, żeby nie duplikować tej logiki
     * w dwóch miejscach. Zwraca null i wysyła komunikat, jeśli gracz nie ma wyspy ALBO jest na niej
     * tylko zwykłym członkiem.
     */
    /** Package-private (nie tylko private) - do rozszerzenia w razie kolejnych mechanik operujących na własnej wyspie. */
    IslandData wlasnaWyspaJakoZarzadca(Player player) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return null;
        if (!mozeZarzadzac(player.getUniqueId(), data)) {
            player.sendMessage(Component.text("Tylko właściciel i administratorzy wyspy mogą to zrobić!", NamedTextColor.RED));
            return null;
        }
        return data;
    }

    public void przelaczWizualnyBorder(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setVisualBorder(!data.isVisualBorder());
        ustawWizualnyBorder(player, data);
        zapiszWyspy();
        player.sendMessage(Component.text("Wizualny border: " + (data.isVisualBorder() ? "włączony" : "wyłączony"), NamedTextColor.GREEN));
    }

    public void przelaczBudowanieDlaGosci(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowBreak(!data.isAllowBreak());
        zapiszWyspy();
        player.sendMessage(Component.text("Budowanie dla gości: " + (data.isAllowBreak() ? "otwarte" : "zamknięte"), NamedTextColor.GREEN));
    }

    public void przelaczPvP(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowPvP(!data.isAllowPvP());
        zapiszWyspy();
        player.sendMessage(Component.text("PvP na wyspie: " + (data.isAllowPvP() ? "włączone" : "wyłączone"), NamedTextColor.GREEN));
    }

    public void przelaczPotwory(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowMobs(!data.isAllowMobs());
        zapiszWyspy();
        player.sendMessage(Component.text("Potwory na wyspie: " + (data.isAllowMobs() ? "mogą się pojawiać" : "zablokowane"), NamedTextColor.GREEN));
    }

    public void przelaczZabijanieMobowPrzezGosci(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowGuestMobKill(!data.isAllowGuestMobKill());
        zapiszWyspy();
        player.sendMessage(Component.text("Zabijanie mobów przez gości: " + (data.isAllowGuestMobKill() ? "dozwolone" : "zablokowane"), NamedTextColor.GREEN));
    }

    public void przelaczZabieranieItemow(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowItemPickup(!data.isAllowItemPickup());
        zapiszWyspy();
        player.sendMessage(Component.text("Zabieranie itemów przez gości: " + (data.isAllowItemPickup() ? "dozwolone" : "zablokowane"), NamedTextColor.GREEN));
    }

    public void przelaczDostepDoKontenerow(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowContainerAccess(!data.isAllowContainerAccess());
        zapiszWyspy();
        player.sendMessage(Component.text("Dostęp gości do skrzyń/kontenerów: " + (data.isAllowContainerAccess() ? "dozwolony" : "zablokowany"), NamedTextColor.GREEN));
    }

    public void przelaczInterakcje(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowInteract(!data.isAllowInteract());
        zapiszWyspy();
        player.sendMessage(Component.text("Drzwi/dźwignie/przyciski dla gości: " + (data.isAllowInteract() ? "dozwolone" : "zablokowane"), NamedTextColor.GREEN));
    }

    public void przelaczPogodeICzas(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setWeatherLocked(!data.isWeatherLocked());
        zapiszWyspy();
        aplikujPogodeICzas(player, data);
        player.sendMessage(Component.text("Pogoda i czas na wyspie: " + (data.isWeatherLocked() ? "zablokowane (zawsze południe, czysto)" : "naturalne"), NamedTextColor.GREEN));
    }

    /**
     * Kosmetyczny efekt "Pogoda i Czas" - wymuszamy KLIENCKĄ iluzję zawsze czystego
     * nieba i południa (WeatherType/setPlayerTime działają tylko dla jednego gracza,
     * nie zmieniają realnej pogody/czasu w skyblockWorld dla nikogo innego). Dotyczy
     * każdego, kto fizycznie stoi na tej wyspie - właściciela, członków i gości -
     * odwrotnie niż allow* dotyczące wyłącznie gości. Wołane w tych samych miejscach,
     * co wizualny border (patrz ustawWizualnyBorder/aplikujBorderDlaLokalizacji), żeby
     * trzymało się gracza przy każdej zmianie świata/teleportacji tak samo jak border.
     */
    void aplikujPogodeICzas(Player player, IslandData data) {
        if (data != null && data.isWeatherLocked()) {
            player.setPlayerTime(6000L, false);
            player.setPlayerWeather(WeatherType.CLEAR);
        } else {
            player.resetPlayerTime();
            player.resetPlayerWeather();
        }
    }

    // ---- Zaproszenia na wyspę (zastępują dawne natychmiastowe dodawanie bez zgody celu) ----

    private final Map<UUID, PendingInvite> pendingInvites = new HashMap<>();

    private record PendingInvite(UUID ownerUUID, UUID inviterUUID) {}

    /**
     * Rdzeń wysyłki zaproszenia - współdzielony przez komendę /is zapros i czatowy
     * przepływ z GUI "Członkowie Wyspy" (przycisk "Zaproś gracza"). Sprawdzenie
     * playerIslandMap.containsKey tutaj to PIERWSZA z dwóch blokad (druga jest
     * w zaakceptujZaproszenie) - bez tego dałoby się zaprosić kogoś, kto już ma
     * własną wyspę albo jest członkiem innej.
     */
    private void wykonajZaproszenie(Player inviter, Player target) {
        UUID targetUUID = target.getUniqueId();
        if (target.equals(inviter)) {
            inviter.sendMessage(Component.text("Nie możesz zaprosić samego siebie!", NamedTextColor.RED));
            return;
        }
        if (playerIslandMap.containsKey(targetUUID)) {
            inviter.sendMessage(Component.text("Ten gracz już ma wyspę lub jest członkiem innej wyspy.", NamedTextColor.RED));
            return;
        }
        if (pendingInvites.containsKey(targetUUID)) {
            inviter.sendMessage(Component.text("Ten gracz ma już oczekujące zaproszenie.", NamedTextColor.RED));
            return;
        }

        UUID ownerUUID = playerIslandMap.get(inviter.getUniqueId());
        pendingInvites.put(targetUUID, new PendingInvite(ownerUUID, inviter.getUniqueId()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pendingInvites.remove(targetUUID) != null) {
                Player t = Bukkit.getPlayer(targetUUID);
                if (t != null) t.sendMessage(Component.text("Zaproszenie na wyspę wygasło.", NamedTextColor.GRAY));
            }
        }, tuning.timeoutZaproszeniaTicks());

        inviter.sendMessage(Component.text("Wysłano zaproszenie do " + target.getName() + ".", NamedTextColor.GREEN));
        target.sendMessage(Component.text("Zostałeś zaproszony na wyspę gracza " + inviter.getName() + "! Wpisz /is akceptuj lub /is odrzuc w ciągu 60 sekund.", NamedTextColor.AQUA));
    }

    private void zaprosGracza(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Użycie: /is zapros <gracz>", NamedTextColor.RED));
            return;
        }
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(Component.text("Nie znaleziono gracza o takim nicku lub jest offline.", NamedTextColor.RED));
            return;
        }

        wykonajZaproszenie(player, target);
    }

    public void zaakceptujZaproszenie(Player player) {
        UUID uuid = player.getUniqueId();
        PendingInvite invite = pendingInvites.remove(uuid);
        if (invite == null) {
            player.sendMessage(Component.text("Nie masz żadnych oczekujących zaproszeń.", NamedTextColor.RED));
            return;
        }
        // Druga blokada (patrz komentarz w wykonajZaproszenie) - stan mógł się zmienić
        // w czasie, gdy zaproszenie czekało (np. gracz założył własną wyspę w międzyczasie).
        if (playerIslandMap.containsKey(uuid)) {
            player.sendMessage(Component.text("Masz już wyspę - to zaproszenie jest już nieaktualne.", NamedTextColor.RED));
            return;
        }
        IslandData data = islandDatabase.get(invite.ownerUUID());
        if (data == null) {
            player.sendMessage(Component.text("Ta wyspa już nie istnieje.", NamedTextColor.RED));
            return;
        }

        data.getMembers().add(uuid);
        data.setRole(uuid, IslandRole.CZLONEK);
        playerIslandMap.put(uuid, invite.ownerUUID());
        zapiszWyspy();
        Bukkit.getPluginManager().callEvent(new IslandMemberJoinedEvent(player, data));

        player.sendMessage(Component.text("Dołączyłeś do wyspy!", NamedTextColor.GREEN));
        Player inviterOnline = Bukkit.getPlayer(invite.inviterUUID());
        if (inviterOnline != null) {
            inviterOnline.sendMessage(Component.text(player.getName() + " zaakceptował zaproszenie i dołączył do wyspy!", NamedTextColor.GREEN));
        }
    }

    public void odrzucZaproszenie(Player player) {
        PendingInvite invite = pendingInvites.remove(player.getUniqueId());
        if (invite == null) {
            player.sendMessage(Component.text("Nie masz żadnych oczekujących zaproszeń.", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Component.text("Odrzucono zaproszenie.", NamedTextColor.YELLOW));
        Player inviterOnline = Bukkit.getPlayer(invite.inviterUUID());
        if (inviterOnline != null) {
            inviterOnline.sendMessage(Component.text(player.getName() + " odrzucił zaproszenie na wyspę.", NamedTextColor.RED));
        }
    }

    /** Samodzielne opuszczenie wyspy przez nie-właściciela - "hermetyczność" (patrz /is opusc, przycisk w panelu). */
    public void opuscWyspe(Player player) {
        UUID uuid = player.getUniqueId();
        UUID ownerUUID = playerIslandMap.get(uuid);
        if (ownerUUID == null) {
            player.sendMessage(Component.text("Nie jesteś członkiem żadnej wyspy!", NamedTextColor.RED));
            return;
        }
        if (ownerUUID.equals(uuid)) {
            player.sendMessage(Component.text("Jesteś właścicielem tej wyspy - użyj /is usun, aby ją usunąć.", NamedTextColor.RED));
            return;
        }

        IslandData data = islandDatabase.get(ownerUUID);
        if (data == null) return;

        data.getMembers().remove(uuid);
        data.getMemberRoles().remove(uuid);
        playerIslandMap.remove(uuid);
        zapiszWyspy();

        player.sendMessage(Component.text("Opuściłeś wyspę.", NamedTextColor.YELLOW));
        Player ownerOnline = Bukkit.getPlayer(ownerUUID);
        if (ownerOnline != null) {
            ownerOnline.sendMessage(Component.text(player.getName() + " opuścił Twoją wyspę.", NamedTextColor.YELLOW));
        }
    }

    /** /is awansuj|degraduj <gracz> - wyłącznie właściciel, egzekwowane wewnątrz zmienRoleCzlonka. */
    private void zmienRoleKomenda(Player player, String[] args, IslandRole nowaRola) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Użycie: /is " + (nowaRola == IslandRole.ADMIN ? "promote" : "demote") + " <gracz>", NamedTextColor.RED));
            return;
        }
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        UUID targetUUID = null;
        for (UUID member : data.getMembers()) {
            @SuppressWarnings("deprecation")
            String nick = Bukkit.getOfflinePlayer(member).getName();
            if (args[1].equalsIgnoreCase(nick)) {
                targetUUID = member;
                break;
            }
        }
        if (targetUUID == null) {
            player.sendMessage(Component.text("Ten gracz nie jest członkiem Twojej wyspy.", NamedTextColor.RED));
            return;
        }

        zmienRoleCzlonka(player, targetUUID, nowaRola);
    }

    /** Zmiana rangi członka - wyłącznie właściciel (nawet admin nie może zarządzać rangami innych). */
    private void zmienRoleCzlonka(Player owner, UUID targetUUID, IslandRole nowaRola) {
        UUID ownerUUID = playerIslandMap.get(owner.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null || !data.getOwnerUUID().equals(owner.getUniqueId())) {
            owner.sendMessage(Component.text("Tylko właściciel wyspy może zmieniać role.", NamedTextColor.RED));
            return;
        }
        if (!data.getMembers().contains(targetUUID)) return;

        data.setRole(targetUUID, nowaRola);
        zapiszWyspy();

        String nazwaRoli = nowaRola == IslandRole.ADMIN ? "Admin" : "Członek";
        owner.sendMessage(Component.text("Zmieniono rolę na " + nazwaRoli + ".", NamedTextColor.GREEN));
        Player targetOnline = Bukkit.getPlayer(targetUUID);
        if (targetOnline != null) {
            targetOnline.sendMessage(Component.text("Twoja rola na wyspie została zmieniona na " + nazwaRoli + ".", NamedTextColor.AQUA));
        }
    }

    private void usunCzlonkaKomenda(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Użycie: /is wyrzuc <gracz>", NamedTextColor.RED));
            return;
        }
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        UUID targetUUID = null;
        for (UUID member : data.getMembers()) {
            @SuppressWarnings("deprecation")
            String nick = Bukkit.getOfflinePlayer(member).getName();
            if (args[1].equalsIgnoreCase(nick)) {
                targetUUID = member;
                break;
            }
        }

        if (targetUUID == null) {
            player.sendMessage(Component.text("Ten gracz nie jest członkiem Twojej wyspy.", NamedTextColor.RED));
            return;
        }

        usunCzlonka(player, targetUUID);
    }

    /** Formatuje pozostały czas oczekiwania jedną, największą pasującą jednostką ("X minut" itd.), z grubsza poprawną polską odmianą. */
    private static String formatujCzasOczekiwania(long millis) {
        long sekundy = (millis + 999) / 1000; // w górę, żeby nie pokazać "0 sekund" tuż przed końcem
        if (sekundy >= 86400) return odmianaLiczby(sekundy / 86400, "dzień", "dni", "dni");
        if (sekundy >= 3600) return odmianaLiczby(sekundy / 3600, "godzinę", "godziny", "godzin");
        if (sekundy >= 60) return odmianaLiczby(sekundy / 60, "minutę", "minuty", "minut");
        return odmianaLiczby(sekundy, "sekundę", "sekundy", "sekund");
    }

    private static String odmianaLiczby(long n, String jedna, String kilka, String wiele) {
        boolean pasujeKilka = n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 10 || n % 100 >= 20);
        String forma = n == 1 ? jedna : pasujeKilka ? kilka : wiele;
        return n + " " + forma;
    }

    private void stworzWyspe(Player player, boolean zMenu) {
        if (!schemFile.exists()) {
            player.sendMessage(Component.text("Błąd: Brak pliku wyspa_startowa.schem w FastAsyncWorldEdit/schematics!", NamedTextColor.RED));
            return;
        }

        HistoriaTworzenia historia = historiaTworzeniaWysp.computeIfAbsent(player.getUniqueId(), k -> new HistoriaTworzenia());
        long cooldown = tuning.cooldownDlaProby(historia.ilosc + 1);
        long odMillis = System.currentTimeMillis() - historia.ostatnieMillis;
        if (cooldown > 0 && odMillis < cooldown) {
            player.sendMessage(Component.text("Musisz jeszcze poczekać " + formatujCzasOczekiwania(cooldown - odMillis)
                    + ", zanim będziesz mógł założyć kolejną wyspę.", NamedTextColor.RED));
            return;
        }
        historia.ilosc++;
        historia.ostatnieMillis = System.currentTimeMillis();

        player.sendMessage(Component.text("Tworzenie Twojej wyspy...", NamedTextColor.YELLOW));

        int myIslandId = nextIslandId++;
        int x = (myIslandId % 100) * tuning.odstepSiatkiWysp();
        int z = (myIslandId / 100) * tuning.odstepSiatkiWysp();
        int y = 100;

        IslandData data = new IslandData(myIslandId, player.getUniqueId(), x, z, tuning.domyslnyRozmiarWyspy());
        islandDatabase.put(player.getUniqueId(), data);
        playerIslandMap.put(player.getUniqueId(), player.getUniqueId());
        zapiszWyspy();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ClipboardFormat format = ClipboardFormats.findByFile(schemFile);
                if (format == null) return;

                try (ClipboardReader reader = format.getReader(new FileInputStream(schemFile))) {
                    Clipboard clipboard = reader.read();

                    try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(skyblockWorld))) {
                        Operation operation = new ClipboardHolder(clipboard)
                                .createPaste(editSession)
                                .to(BlockVector3.at(x, y, z))
                                .ignoreAirBlocks(true)
                                .build();
                        Operations.complete(operation);
                    }
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    teleportDoWyspy(player);
                    player.sendMessage(Component.text("Twoja wyspa została utworzona!", NamedTextColor.GREEN));
                    Bukkit.getPluginManager().callEvent(new IslandCreatedEvent(player, data));
                    otworzMenuWyspy(player, zMenu);
                });

            } catch (Exception e) {
                e.printStackTrace();
                player.sendMessage(Component.text("Wystąpił błąd podczas generowania wyspy!", NamedTextColor.RED));
            }
        });
    }

    /** Cel /is dom (i /dom, /home) - punkt ustawiony przez /is ustawdom, fallback = środek wyspy. */
    public void teleportDoWyspy(Player player) {
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        if (ownerUUID == null) {
            stworzWyspe(player, false);
            return;
        }

        IslandData data = islandDatabase.get(ownerUUID);
        if (data == null) {
            player.sendMessage(Component.text("Twoja wyspa nie istnieje!", NamedTextColor.RED));
            return;
        }

        // Domyślnie środek wyspy (ten sam punkt, w który wklejany jest schemat startowy)
        // - jeśli gracz ustawił własny punkt przez /is ustawdom, używamy tego zamiast.
        Location loc = data.hasCustomHome()
                ? new Location(skyblockWorld, data.getHomeX(), data.getHomeY(), data.getHomeZ(), data.getHomeYaw(), data.getHomePitch())
                : new Location(skyblockWorld, data.getCenterX() + 0.5, 101, data.getCenterZ() + 0.5);
        zabezpieczPunktSpawnu(loc);
        player.teleport(loc);
        ustawWizualnyBorder(player, data);
        aplikujPogodeICzas(player, data);
        player.sendMessage(Component.text("Przeteleportowano na wyspę!", NamedTextColor.AQUA));
    }

    /**
     * Cel gołego /is - DRUGI, niezależny punkt teleportu ustawiony przez /is ustawspawn,
     * osobny od /is ustawdom (patrz teleportDoWyspy wyżej). Fallback identyczny - środek
     * wyspy, jeśli gracz jeszcze nic nie ustawił.
     */
    public void teleportDoSpawnuWyspy(Player player) {
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        if (ownerUUID == null) {
            stworzWyspe(player, false);
            return;
        }

        IslandData data = islandDatabase.get(ownerUUID);
        if (data == null) {
            player.sendMessage(Component.text("Twoja wyspa nie istnieje!", NamedTextColor.RED));
            return;
        }

        Location loc = data.hasCustomSpawn()
                ? new Location(skyblockWorld, data.getSpawnX(), data.getSpawnY(), data.getSpawnZ(), data.getSpawnYaw(), data.getSpawnPitch())
                : new Location(skyblockWorld, data.getCenterX() + 0.5, 101, data.getCenterZ() + 0.5);
        zabezpieczPunktSpawnu(loc);
        player.teleport(loc);
        ustawWizualnyBorder(player, data);
        aplikujPogodeICzas(player, data);
        player.sendMessage(Component.text("Przeteleportowano na wyspę!", NamedTextColor.AQUA));
    }

    /**
     * Gracz mógł wyczyścić cały teren pod punktem teleportu (ręcznie albo np. wybuchem
     * TNT), albo grunt wyspy po prostu leży niżej niż spodziewane Y=101 - bez tego
     * gracz teleportowałby się w pustkę i spadał aż do obrażeń od "pustki" (void).
     * Kolejność prób: 1) grunt prosto pod celem (do tuning.maxGlebokoscSzukaniaWDol bloków
     * w dół) - typowy przypadek, najtańszy; 2) najbliższy solidny blok w promieniu
     * tuning.promienSzukaniaObok dookoła, gdy w dół jest za głęboko; 3) w ostateczności -
     * postaw ziemię dokładnie w oryginalnym miejscu.
     */
    private void zabezpieczPunktSpawnu(Location loc) {
        Location wDol = szukajGruntuWDol(loc, tuning.maxGlebokoscSzukaniaWDol());
        if (wDol != null) {
            przeniesXYZ(loc, wDol);
            return;
        }

        Location obok = szukajNajblizszegoGruntu(loc, tuning.promienSzukaniaObok());
        if (obok != null) {
            przeniesXYZ(loc, obok);
            return;
        }

        loc.clone().subtract(0, 1, 0).getBlock().setType(Material.DIRT);
    }

    /** Nadpisuje X/Y/Z celu znalezioną lokalizacją, zachowując oryginalny kierunek patrzenia (yaw/pitch). */
    private void przeniesXYZ(Location cel, Location znaleziona) {
        cel.setX(znaleziona.getX());
        cel.setY(znaleziona.getY());
        cel.setZ(znaleziona.getZ());
    }

    /** Pierwszy solidny blok prosto pod `loc`, maks. `maxGlebokosc` bloków w dół - albo null, jeśli nic nie ma. */
    private Location szukajGruntuWDol(Location loc, int maxGlebokosc) {
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int startY = loc.getBlockY();

        for (int dy = 1; dy <= maxGlebokosc; dy++) {
            int y = startY - dy;
            if (y < world.getMinHeight()) break;
            if (world.getBlockAt(x, y, z).getType().isSolid()) {
                return new Location(world, x + 0.5, y + 1, z + 0.5);
            }
        }
        return null;
    }

    /** Najbliższy (w linii prostej) solidny blok z dwoma wolnymi kratkami nad sobą, w promieniu `promien` we wszystkich kierunkach - albo null. */
    private Location szukajNajblizszegoGruntu(Location loc, int promien) {
        World world = loc.getWorld();
        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();

        Location najlepsza = null;
        int najlepszyDystansKw = Integer.MAX_VALUE;

        for (int dx = -promien; dx <= promien; dx++) {
            for (int dy = -promien; dy <= promien; dy++) {
                for (int dz = -promien; dz <= promien; dz++) {
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (y < world.getMinHeight() || y + 2 >= world.getMaxHeight()) continue;
                    if (!world.getBlockAt(x, y, z).getType().isSolid()) continue;
                    if (!world.getBlockAt(x, y + 1, z).getType().isAir()) continue;
                    if (!world.getBlockAt(x, y + 2, z).getType().isAir()) continue;

                    int dystansKw = dx * dx + dy * dy + dz * dz;
                    if (dystansKw < najlepszyDystansKw) {
                        najlepszyDystansKw = dystansKw;
                        najlepsza = new Location(world, x + 0.5, y + 1, z + 0.5);
                    }
                }
            }
        }
        return najlepsza;
    }

    /**
     * /is ustawdom - nadpisuje domyślny punkt teleportu (środek wyspy) własnym,
     * ustawionym tam, gdzie gracz akurat stoi - to jest cel /dom i /home. To ustawienie
     * WSPÓLNE dla całej wyspy (dotyczy każdego, kto potem wpisze /is dom albo /dom/home),
     * więc - tak jak border/PvP/ulepszenia - może to zrobić tylko właściciel albo admin
     * (wlasnaWyspaJakoZarzadca), nie każdy zwykły członek. Wymagamy też, żeby stał na
     * SWOJEJ wyspie - bez tego dałoby się ustawić dom gdziekolwiek w świecie (np. na
     * cudzej wyspie). Osobny, niezależny punkt (dla gołego /is) - patrz ustawSpawnWyspy niżej.
     */
    private void ustawDomek(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        Location loc = player.getLocation();
        if (loc.getWorld() == null || !loc.getWorld().equals(skyblockWorld)
                || Math.abs(loc.getBlockX() - data.getCenterX()) > data.getBorderSize()
                || Math.abs(loc.getBlockZ() - data.getCenterZ()) > data.getBorderSize()) {
            player.sendMessage(Component.text("Musisz stać na własnej wyspie, żeby tu ustawić punkt teleportu!", NamedTextColor.RED));
            return;
        }

        data.setHome(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        zapiszWyspy();
        player.sendMessage(Component.text("Ustawiono nowy punkt teleportacji (/dom, /home) na Twojej wyspie!", NamedTextColor.GREEN));
    }

    /**
     * /is ustawspawn - to samo co ustawDomek wyżej, ale dla DRUGIEGO, niezależnego
     * punktu - celu gołego /is (patrz teleportDoSpawnuWyspy). Te same ograniczenia:
     * tylko właściciel/admin, tylko stojąc na własnej wyspie.
     */
    private void ustawSpawnWyspy(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        Location loc = player.getLocation();
        if (loc.getWorld() == null || !loc.getWorld().equals(skyblockWorld)
                || Math.abs(loc.getBlockX() - data.getCenterX()) > data.getBorderSize()
                || Math.abs(loc.getBlockZ() - data.getCenterZ()) > data.getBorderSize()) {
            player.sendMessage(Component.text("Musisz stać na własnej wyspie, żeby tu ustawić punkt teleportu!", NamedTextColor.RED));
            return;
        }

        data.setSpawn(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        zapiszWyspy();
        player.sendMessage(Component.text("Ustawiono nowy punkt teleportacji (/is) na Twojej wyspie!", NamedTextColor.GREEN));
    }

    /**
     * /is wplac <kwota> - wpłaca do banku WŁASNEJ wyspy gracza, niezależnie gdzie
     * fizycznie stoi w danym momencie. Bank jest JEDYNYM źródłem pieniędzy na
     * ulepszenia (patrz uprosGranice/ulepszSpawnerStatystyke).
     *
     * Wcześniej wpłata leciała do banku wyspy, na której gracz fizycznie stał (pomysł:
     * goście mogą wesprzeć cudzą wyspę) - w praktyce to było zbyt łatwe do pomylenia:
     * gracz odwiedzający kolegę i wpisujący /is wplac z odruchu wpłacał kasę na
     * JEGO bank, nie swój, bez żadnego ostrzeżenia. Zmienione na zawsze-własną wyspę.
     */
    private void wplacDoBankuKomenda(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Użycie: /is wplac <kwota>", NamedTextColor.RED));
            return;
        }

        double kwota = sparsujKwote(player, args[1]);
        if (Double.isNaN(kwota)) return;

        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        if (!economyManager.maWystarczajaco(player.getUniqueId(), kwota)) {
            player.sendMessage(Component.text("Nie masz wystarczająco pieniędzy!", NamedTextColor.RED));
            return;
        }

        economyManager.odejmijKase(player.getUniqueId(), kwota);
        data.dodajDoBanku(kwota);
        zapiszWyspy();
        Bukkit.getPluginManager().callEvent(new IslandBankDepositEvent(player, data, kwota));

        player.sendMessage(Component.text("Wpłacono " + formatKwote(kwota) + " $ do banku wyspy. Nowy stan: " + formatKwote(data.getBankBalance()) + " $.", NamedTextColor.GREEN));

        if (!data.getOwnerUUID().equals(player.getUniqueId())) {
            Player ownerOnline = Bukkit.getPlayer(data.getOwnerUUID());
            if (ownerOnline != null) {
                ownerOnline.sendMessage(Component.text(player.getName() + " wpłacił " + formatKwote(kwota) + " $ do banku Twojej wyspy!", NamedTextColor.GREEN));
            }
        }
    }

    /** /is wyplac <kwota> - wyłącznie właściciel/admin WŁASNEJ wyspy (patrz ustalenie: wypłaca tylko zarządca). */
    private void wyplacZBankuKomenda(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Użycie: /is wyplac <kwota>", NamedTextColor.RED));
            return;
        }

        double kwota = sparsujKwote(player, args[1]);
        if (Double.isNaN(kwota)) return;

        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        if (!data.odejmijZBanku(kwota)) {
            player.sendMessage(Component.text("Bank wyspy nie ma tyle pieniędzy! Stan: " + formatKwote(data.getBankBalance()) + " $.", NamedTextColor.RED));
            return;
        }

        economyManager.dodajKase(player.getUniqueId(), kwota);
        zapiszWyspy();
        player.sendMessage(Component.text("Wypłacono " + formatKwote(kwota) + " $ z banku wyspy. Nowy stan: " + formatKwote(data.getBankBalance()) + " $.", NamedTextColor.GREEN));
    }

    private double sparsujKwote(Player player, String tekst) {
        double kwota;
        try {
            kwota = Double.parseDouble(tekst);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Podaj poprawną kwotę (liczbę).", NamedTextColor.RED));
            return Double.NaN;
        }
        if (kwota <= 0 || !Double.isFinite(kwota)) {
            player.sendMessage(Component.text("Kwota musi być większa od zera.", NamedTextColor.RED));
            return Double.NaN;
        }
        return kwota;
    }

    private String formatKwote(double kwota) {
        return String.format(java.util.Locale.US, "%.2f", kwota);
    }

    private void ustawWizualnyBorder(Player player, IslandData data) {
        if (!data.isVisualBorder()) {
            wyczyscBorder(player);
            return;
        }

        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(data.getCenterX() + 0.5, data.getCenterZ() + 0.5);
        border.setSize(data.getBorderSize() * 2);
        border.setWarningDistance(0);
        player.setWorldBorder(border);
    }

    /** Border "wyłączony" - wspólne dla przełącznika w panelu i dla opuszczenia terenu wysp. */
    void wyczyscBorder(Player player) {
        WorldBorder clearBorder = Bukkit.createWorldBorder();
        clearBorder.setSize(ROZMIAR_BEZ_BORDERU);
        player.setWorldBorder(clearBorder);
    }

    /**
     * Stosuje border wyspy, na której obszarze fizycznie stoi gracz (właściciel, członek
     * LUB gość odwiedzający - patrz znajdzWyspePod, dopasowanie jest po współrzędnych,
     * nie po członkostwie), albo brak borderu, jeśli stoi w pustce między wyspami. Wołane
     * przez BorderManager przy zmianie świata i teleportacji w obrębie świata wysp, żeby
     * border faktycznie trzymał się gracza cały czas, a nie tylko bezpośrednio po /is.
     */
    void aplikujBorderDlaLokalizacji(Player player, Location loc) {
        IslandData data = znajdzWyspePod(loc);
        if (data == null) {
            wyczyscBorder(player);
        } else {
            ustawWizualnyBorder(player, data);
        }
        aplikujPogodeICzas(player, data);
    }

    // ---- Wspólne budulce GUI - wszystkie renderujące metody niżej korzystają z tych helperów ----

    private void wypelnijTlo(Inventory inv, IslandScreen screen) {
        ItemStack tlo = new ItemStack(screen.tlo());
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        for (int i = 0; i < screen.size(); i++) inv.setItem(i, tlo);
    }

    private ItemStack ikonaZPrzycisku(IslandGuiButton btn, List<Component> dodatkoweLore) {
        return ikonaZPrzycisku(btn, btn.material(), dodatkoweLore);
    }

    private ItemStack ikonaZPrzycisku(IslandGuiButton btn, Material material, List<Component> dodatkoweLore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(btn.nazwa(), btn.kolor(), TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        for (String linia : btn.lore()) lore.add(Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (dodatkoweLore != null) lore.addAll(dodatkoweLore);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void ustawPrzycisk(Inventory inv, IslandScreen screen, String akcja, List<Component> dodatkoweLore) {
        IslandGuiButton btn = screen.przycisk(akcja);
        if (btn == null) return;
        inv.setItem(btn.slot(), ikonaZPrzycisku(btn, dodatkoweLore));
    }

    private ItemStack ikonaPrzelacznika(IslandGuiButton btn, boolean on) {
        Material material = on ? btn.material() : (btn.materialWylaczone() != null ? btn.materialWylaczone() : btn.material());
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(btn.nazwa(), btn.kolor(), TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Stan: " + (on ? "Włączone" : "Wyłączone"), on ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        for (String linia : btn.lore()) lore.add(Component.text(linia, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Kliknij, aby przełączyć", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void ustawPrzelacznik(Inventory inv, IslandScreen screen, String akcja, boolean on) {
        IslandGuiButton btn = screen.przycisk(akcja);
        if (btn == null) return;
        inv.setItem(btn.slot(), ikonaPrzelacznika(btn, on));
    }

    private boolean jestSlotem(IslandScreen screen, String akcja, int slot) {
        IslandGuiButton btn = screen.przycisk(akcja);
        return btn != null && btn.slot() == slot;
    }

    // ---- Renderowanie GUI (patrz wyspy-gui.yml po pelny uklad) ----

    public void otworzMenuWyspy(Player player, boolean zMenu) {
        otwartoZMenu.put(player.getUniqueId(), zMenu);
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;

        if (data == null) {
            player.sendMessage(Component.text("Nie posiadasz wyspy!", NamedTextColor.RED));
            return;
        }

        boolean isOwner = data.getOwnerUUID().equals(player.getUniqueId());
        boolean canManage = mozeZarzadzac(player.getUniqueId(), data);

        IslandScreen screen = gui.panelWyspy();
        Inventory inv = Bukkit.createInventory(null, screen.size(), Component.text("Panel Wyspy", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
        wypelnijTlo(inv, screen);

        // Kolejność kafelków w panelu odzwierciedla częstotliwość użycia (najczęstsze
        // pierwsze) - Teleport, Ulepszenia i Bank to codzienne akcje, Informacje/Topka
        // to głównie wgląd, Ustawienia/Permisje to rzadko zmieniane ustawienia jednorazowe.

        ustawPrzycisk(inv, screen, "TELEPORT", null);

        if (canManage) {
            ustawPrzycisk(inv, screen, "ULEPSZENIA", null);
        }

        IslandGuiButton bank = screen.przycisk("BANK");
        if (bank != null) {
            List<Component> loreBank = new ArrayList<>();
            loreBank.add(Component.text("Stan: " + formatKwote(data.getBankBalance()) + " $", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            loreBank.add(Component.text("Ulepszenia płacone WYŁĄCZNIE z tego banku", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            loreBank.add(Component.empty());
            loreBank.add(Component.text("/is wplac <kwota> - wpłać (może każdy)", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            loreBank.add(Component.text("/is wyplac <kwota> - wypłać (właściciel/admin)", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            inv.setItem(bank.slot(), ikonaZPrzycisku(bank, loreBank));
        }

        IslandGuiButton info = screen.przycisk("INFO");
        if (info != null) {
            List<Component> loreInfo = new ArrayList<>();
            Player owner = Bukkit.getPlayer(data.getOwnerUUID());
            String ownerName = owner != null ? owner.getName() : "Nieznany";
            String nazwaRoliGracza = isOwner ? "Właściciel" : (data.getRole(player.getUniqueId()) == IslandRole.ADMIN ? "Admin" : "Członek");
            if (data.getCustomName() != null) {
                loreInfo.add(Component.text("Nazwa: " + data.getCustomName(), NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            }
            loreInfo.add(Component.text("Właściciel: " + ownerName, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            loreInfo.add(Component.text("Rozmiar: " + data.getBorderSize() + "x" + data.getBorderSize(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            loreInfo.add(Component.text("Członkowie: " + data.getMembers().size(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            loreInfo.add(Component.text("Wartość wyspy: " + formatKwote(data.getWorth()) + " $", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            loreInfo.add(Component.text("Twoja rola: " + nazwaRoliGracza, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            inv.setItem(info.slot(), ikonaZPrzycisku(info, loreInfo));
        }

        if (canManage) {
            ustawPrzycisk(inv, screen, "USTAWIENIA", null);
            ustawPrzycisk(inv, screen, "PERMISJE", null);
        }

        ustawPrzycisk(inv, screen, "TOPKA", null);

        ustawPrzycisk(inv, screen, isOwner ? "USUN_WYSPE" : "OPUSC_WYSPE", null);
        ustawPrzycisk(inv, screen, zMenu ? "WROC_DO_MENU" : "ZAMKNIJ_PANEL", null);

        player.openInventory(inv);
    }

    /** Podmenu "Permisje" - dodatkowe uprawnienia gości (poza budowaniem/PvP/mobami - patrz Ustawienia Wyspy). Wyłącznie dla właściciela i adminów. */
    public void otworzMenuPermisji(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        IslandScreen screen = gui.permisjeWyspy();
        Inventory inv = Bukkit.createInventory(null, screen.size(), Component.text("Permisje Wyspy", NamedTextColor.RED, TextDecoration.BOLD));
        wypelnijTlo(inv, screen);

        ustawPrzelacznik(inv, screen, "ZABIERANIE_ITEMOW", data.isAllowItemPickup());
        ustawPrzelacznik(inv, screen, "DOSTEP_KONTENEROW", data.isAllowContainerAccess());
        ustawPrzelacznik(inv, screen, "INTERAKCJE", data.isAllowInteract());
        ustawPrzycisk(inv, screen, "POWROT", null);

        player.openInventory(inv);
    }

    /**
     * Podmenu "Ustawienia Wyspy" - ogólna konfiguracja terenu (border, budowanie/PvP/moby,
     * pogoda i czas, nazwa) + dostęp do zarządzania członkami. Wyłącznie dla właściciela i adminów.
     */
    public void otworzMenuUstawienWyspy(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        IslandScreen screen = gui.ustawieniaWyspy();
        Inventory inv = Bukkit.createInventory(null, screen.size(), Component.text("Ustawienia Wyspy", NamedTextColor.BLUE, TextDecoration.BOLD));
        wypelnijTlo(inv, screen);

        ustawPrzelacznik(inv, screen, "WIZUALNY_BORDER", data.isVisualBorder());
        ustawPrzelacznik(inv, screen, "BUDOWANIE_GOSCI", data.isAllowBreak());
        ustawPrzelacznik(inv, screen, "PVP", data.isAllowPvP());
        ustawPrzelacznik(inv, screen, "POTWORY", data.isAllowMobs());
        ustawPrzelacznik(inv, screen, "ZABIJANIE_MOBOW_GOSCI", data.isAllowGuestMobKill());
        ustawPrzelacznik(inv, screen, "POGODA_CZAS", data.isWeatherLocked());

        IslandGuiButton nazwaBtn = screen.przycisk("NAZWA_WYSPY");
        if (nazwaBtn != null) {
            List<Component> lore = List.of(
                    Component.text("Aktualnie: " + (data.getCustomName() != null ? data.getCustomName() : "(brak)"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Kliknij, aby ustawić przez czat", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            );
            inv.setItem(nazwaBtn.slot(), ikonaZPrzycisku(nazwaBtn, lore));
        }

        IslandGuiButton czlonkowieBtn = screen.przycisk("CZLONKOWIE");
        if (czlonkowieBtn != null) {
            List<Component> lore = List.of(
                    Component.text("Aktualnie: " + data.getMembers().size() + " członków", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Kliknij, aby zarządzać", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            );
            inv.setItem(czlonkowieBtn.slot(), ikonaZPrzycisku(czlonkowieBtn, lore));
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        player.openInventory(inv);
    }

    /**
     * Ranking wysp na serwerze wg WARTOŚCI (postawione bloki - patrz IslandData.worth /
     * IslandProtectionManager), NIE po promieniu (to inny wymiar niż getTopIslands()/
     * IslandSummary używane przez HUD - celowo osobne, żeby nie dotykać współdzielonego
     * API w mainplugins-core). Dostępny dla każdego mieszkańca wyspy, niezależnie od roli.
     */
    public void otworzMenuTopkiWysp(Player player) {
        IslandScreen screen = gui.topkaWysp();
        Inventory inv = Bukkit.createInventory(null, screen.size(), Component.text("Topka Wysp", NamedTextColor.GOLD, TextDecoration.BOLD));
        wypelnijTlo(inv, screen);

        List<IslandData> top = new ArrayList<>(islandDatabase.values());
        top.sort((a, b) -> Double.compare(b.getWorth(), a.getWorth()));

        int[] sloty = gui.topkaSlotyRankingu();
        for (int i = 0; i < top.size() && i < sloty.length; i++) {
            IslandData wyspa = top.get(i);
            int miejsce = i + 1;

            NamedTextColor kolorMiejsca = switch (miejsce) {
                case 1 -> NamedTextColor.GOLD;
                case 2 -> NamedTextColor.GRAY;
                case 3 -> NamedTextColor.RED;
                default -> NamedTextColor.YELLOW;
            };

            @SuppressWarnings("deprecation")
            OfflinePlayer ownerOffline = Bukkit.getOfflinePlayer(wyspa.getOwnerUUID());
            String ownerNick = ownerOffline.getName() != null ? ownerOffline.getName() : wyspa.getOwnerUUID().toString().substring(0, 8);
            String nazwaWyswietlana = wyspa.getCustomName() != null ? wyspa.getCustomName() : ownerNick;

            ItemStack glowa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) glowa.getItemMeta();
            meta.setOwningPlayer(ownerOffline);
            meta.displayName(Component.text("#" + miejsce + " - " + nazwaWyswietlana, kolorMiejsca, TextDecoration.BOLD));
            meta.lore(List.of(
                    Component.text("Wartość: " + formatKwote(wyspa.getWorth()) + " $", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                    Component.text("Rozmiar: " + wyspa.getBorderSize() + "x" + wyspa.getBorderSize(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Członkowie: " + wyspa.getMembers().size(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            glowa.setItemMeta(meta);
            inv.setItem(sloty[i], glowa);
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        player.openInventory(inv);
    }

    public void otworzMenuUlepszen(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        int currentSize = data.getBorderSize();
        int cost = currentSize * tuning.borderKosztZaBlok();
        boolean maksimum = currentSize >= tuning.borderMaxRozmiar();

        IslandScreen screen = gui.ulepszeniaWyspy();
        Inventory inv = Bukkit.createInventory(null, screen.size(), Component.text("Ulepszenia Wyspy", NamedTextColor.GOLD, TextDecoration.BOLD));
        wypelnijTlo(inv, screen);

        IslandGuiButton powieksz = screen.przycisk("POWIEKSZ_TEREN");
        if (powieksz != null) {
            List<Component> lore = maksimum
                    ? List.of(
                            Component.text("Aktualny promień: " + currentSize + " bloków", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("Osiągnięto maksymalny rozmiar wyspy!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
                    )
                    : List.of(
                            Component.text("Aktualny promień: " + currentSize + " bloków", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                            Component.text("Koszt (z banku wyspy): " + cost + " $", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                            Component.text("Stan banku: " + formatKwote(data.getBankBalance()) + " $", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            Component.text("Kliknij, aby powiększyć o " + tuning.borderPrzyrostNaUlepszenie() + " bloków!", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)
                    );
            Material material = (maksimum && powieksz.materialWylaczone() != null) ? powieksz.materialWylaczone() : powieksz.material();
            inv.setItem(powieksz.slot(), ikonaZPrzycisku(powieksz, material, lore));
        }

        IslandGuiButton spawnery = screen.przycisk("ULEPSZENIE_SPAWNEROW");
        if (spawnery != null) {
            ItemStack itemSpawnery = ikonaZPrzycisku(spawnery, null);
            // SPAWNER jako item ma własny wanilijski dopisek w tooltipie ("Interakcja z jajem
            // przyzywającym: Ustawia typ stworzenia") - gracz o to pytał, więc jawnie go ukrywamy.
            itemSpawnery.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                    TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.BLOCK_DATA));
            inv.setItem(spawnery.slot(), itemSpawnery);
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        player.openInventory(inv);
    }

    /**
     * Poziomy (1-5 domyślnie, patrz wyspy-config.yml: spawnery.max-poziom) customowych
     * spawnerów mainplugins-spawners, per wyspa. Sam moduł spawnerów o tym nic nie wie
     * poza odczytem IslandSummary.spawnerLevels() - cała logika ulepszania (koszt, limit)
     * żyje tutaj, w panelu wyspy.
     */
    public void otworzMenuWzrostuDropow(Player player) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        IslandScreen screen = gui.ulepszenieSpawnerow();
        Inventory inv = Bukkit.createInventory(null, screen.size(), Component.text("Ulepszenie Spawnerów", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        wypelnijTlo(inv, screen);

        int[] sloty = gui.ulepszenieSpawnerowSlotyTypow();
        List<SpawnerTyp> typy = tuning.spawnerTypy();
        for (int i = 0; i < typy.size() && i < sloty.length; i++) {
            SpawnerTyp typ = typy.get(i);
            int poziomIlosci = data.getSpawnerLevel(typ.id() + SUFIKS_ILOSC);
            int poziomSzybkosci = data.getSpawnerLevel(typ.id() + SUFIKS_SZYBKOSC);

            ItemStack item = new ItemStack(typ.ikona());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("Spawner: " + typ.nazwaOdmieniona(), NamedTextColor.YELLOW, TextDecoration.BOLD));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Ilość: " + poziomIlosci + "/" + tuning.spawnerMaxPoziom(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Szybkość: " + poziomSzybkosci + "/" + tuning.spawnerMaxPoziom(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("Kliknij, aby zarządzać ulepszeniami", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);

            inv.setItem(sloty[i], item);
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        player.openInventory(inv);
    }

    /** Podmenu jednego typu spawnera - osobne ulepszanie Ilości (mobków/cykl) i Szybkości (odstęp między cyklami). */
    public void otworzMenuUlepszenSpawnera(Player player, String typId) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        SpawnerTyp typ = tuning.spawnerTyp(typId);
        if (typ == null) return;

        otwartySpawnerTyp.put(player.getUniqueId(), typId);

        IslandScreen screen = gui.spawnerPodmenu();
        Inventory inv = Bukkit.createInventory(null, screen.size(), Component.text("Spawner: " + typ.nazwaOdmieniona(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        wypelnijTlo(inv, screen);

        int poziomIlosci = data.getSpawnerLevel(typId + SUFIKS_ILOSC);
        int poziomSzybkosci = data.getSpawnerLevel(typId + SUFIKS_SZYBKOSC);

        IslandGuiButton iloscBtn = screen.przycisk("ILOSC");
        if (iloscBtn != null) {
            // Ikona ILOSC zawsze bierze się z ikony aktualnie otwartego typu spawnera,
            // niezależnie od "material" w wyspy-gui.yml - patrz komentarz tam.
            inv.setItem(iloscBtn.slot(), itemUlepszeniaStatystyki(iloscBtn, typId, typ.ikona(), poziomIlosci, true));
        }
        IslandGuiButton szybkoscBtn = screen.przycisk("SZYBKOSC");
        if (szybkoscBtn != null) {
            inv.setItem(szybkoscBtn.slot(), itemUlepszeniaStatystyki(szybkoscBtn, typId, szybkoscBtn.material(), poziomSzybkosci, false));
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        player.openInventory(inv);
    }

    private ItemStack itemUlepszeniaStatystyki(IslandGuiButton btn, String typId, Material ikona, int level, boolean ilosc) {
        boolean maksimum = level >= tuning.spawnerMaxPoziom();

        ItemStack item = new ItemStack(ikona);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(btn.nazwa(), btn.kolor(), TextDecoration.BOLD));

        List<Component> lore = new ArrayList<>();
        for (String linia : btn.lore()) lore.add(Component.text(linia, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Poziom: " + level + "/" + tuning.spawnerMaxPoziom(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        if (maksimum) {
            lore.add(Component.text("Osiągnięto maksymalny poziom!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Koszt (z banku wyspy): " + tuning.kosztUlepszeniaSpawnera(typId, ilosc, level) + " $", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Kliknij, aby ulepszyć", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void ulepszSpawnerStatystyke(Player player, String typId, String sufiks) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        String klucz = typId + sufiks;
        int level = data.getSpawnerLevel(klucz);
        if (level >= tuning.spawnerMaxPoziom()) {
            player.sendMessage(Component.text("Ta statystyka ma już maksymalny poziom!", NamedTextColor.RED));
            return;
        }

        boolean ilosc = sufiks.equals(SUFIKS_ILOSC);
        int cost = tuning.kosztUlepszeniaSpawnera(typId, ilosc, level);
        if (!data.odejmijZBanku(cost)) {
            player.sendMessage(Component.text("Bank wyspy nie ma tyle pieniędzy! Potrzeba " + cost + " $, w banku jest " + formatKwote(data.getBankBalance()) + " $. Wpłać przez /is wplac.", NamedTextColor.RED));
            return;
        }

        data.setSpawnerLevel(klucz, level + 1);
        zapiszWyspy();

        player.sendMessage(Component.text("Ulepszono do poziomu " + (level + 1) + "!", NamedTextColor.GREEN));
        otworzMenuUlepszenSpawnera(player, typId);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().title().toString();
        if (!(event.getWhoClicked() instanceof Player player)) return;

        boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);

        if (title.contains("Panel Wyspy")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = gui.panelWyspy();

            UUID panelOwnerUUID = playerIslandMap.get(player.getUniqueId());
            IslandData panelData = panelOwnerUUID != null ? islandDatabase.get(panelOwnerUUID) : null;
            boolean panelIsOwner = panelData != null && panelData.getOwnerUUID().equals(player.getUniqueId());

            if (jestSlotem(screen, "TELEPORT", slot)) { player.closeInventory(); teleportDoWyspy(player); }
            else if (jestSlotem(screen, "ULEPSZENIA", slot)) { otworzMenuUlepszen(player); }
            else if (jestSlotem(screen, "USTAWIENIA", slot)) { otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "PERMISJE", slot)) { otworzMenuPermisji(player); }
            else if (jestSlotem(screen, "TOPKA", slot)) { otworzMenuTopkiWysp(player); }
            else if (panelIsOwner && jestSlotem(screen, "USUN_WYSPE", slot)) {
                // Jedno kliknięcie zamiast dawnego "kliknij dwa razy" - potwierdzenie idzie
                // przez czat (wpisanie "Tak zgadzam się"), żeby przypadkowy drugi klik (np. przy
                // zamykaniu GUI) nie mógł już bezpowrotnie skasować wyspy.
                player.closeInventory();
                ustawOczekiwanieNaPotwierdzenie(player.getUniqueId());
                wyslijOstrzezenieUsuniecia(player);
            }
            else if (!panelIsOwner && jestSlotem(screen, "OPUSC_WYSPE", slot)) {
                if (pendingLeaveConfirmation.contains(player.getUniqueId())) {
                    player.closeInventory();
                    pendingLeaveConfirmation.remove(player.getUniqueId());
                    opuscWyspe(player);
                } else {
                    pendingLeaveConfirmation.add(player.getUniqueId());
                    Bukkit.getScheduler().runTaskLater(plugin, () -> pendingLeaveConfirmation.remove(player.getUniqueId()), tuning.timeoutPotwierdzeniaTicks());
                    ItemStack item = event.getCurrentItem();
                    if (item != null) {
                        ItemMeta meta = item.getItemMeta();
                        meta.displayName(Component.text("KLIKNIJ PONOWNIE, ABY OPUŚCIĆ WYSPĘ!", NamedTextColor.DARK_RED, TextDecoration.BOLD));
                        item.setItemMeta(meta);
                    }
                }
            }
            else if (jestSlotem(screen, "WROC_DO_MENU", slot) || jestSlotem(screen, "ZAMKNIJ_PANEL", slot)) {
                if (zMenu) {
                    player.closeInventory();
                    player.performCommand("menu");
                } else {
                    player.closeInventory();
                }
            }
        }
        else if (title.contains("Permisje Wyspy")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = gui.permisjeWyspy();
            if (jestSlotem(screen, "ZABIERANIE_ITEMOW", slot)) { przelaczZabieranieItemow(player); otworzMenuPermisji(player); }
            else if (jestSlotem(screen, "DOSTEP_KONTENEROW", slot)) { przelaczDostepDoKontenerow(player); otworzMenuPermisji(player); }
            else if (jestSlotem(screen, "INTERAKCJE", slot)) { przelaczInterakcje(player); otworzMenuPermisji(player); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (title.contains("Ustawienia Wyspy")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = gui.ustawieniaWyspy();
            if (jestSlotem(screen, "WIZUALNY_BORDER", slot)) { przelaczWizualnyBorder(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "BUDOWANIE_GOSCI", slot)) { przelaczBudowanieDlaGosci(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "PVP", slot)) { przelaczPvP(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "POTWORY", slot)) { przelaczPotwory(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "ZABIJANIE_MOBOW_GOSCI", slot)) { przelaczZabijanieMobowPrzezGosci(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "POGODA_CZAS", slot)) { przelaczPogodeICzas(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "NAZWA_WYSPY", slot)) { rozpocznijZmianeNazwyWyspy(player); }
            else if (jestSlotem(screen, "CZLONKOWIE", slot)) { otworzMenuCzlonkow(player); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (title.contains("Topka Wysp")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (jestSlotem(gui.topkaWysp(), "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (title.contains("Ulepszenia Wyspy")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = gui.ulepszeniaWyspy();
            if (jestSlotem(screen, "POWIEKSZ_TEREN", slot)) { uprosGranice(player); }
            else if (jestSlotem(screen, "ULEPSZENIE_SPAWNEROW", slot)) { otworzMenuWzrostuDropow(player); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (title.contains("Ulepszenie Spawnerów")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = gui.ulepszenieSpawnerow();
            if (jestSlotem(screen, "POWROT", slot)) { otworzMenuUlepszen(player); return; }

            int[] sloty = gui.ulepszenieSpawnerowSlotyTypow();
            List<SpawnerTyp> typy = tuning.spawnerTypy();
            for (int i = 0; i < sloty.length && i < typy.size(); i++) {
                if (sloty[i] == slot) {
                    otworzMenuUlepszenSpawnera(player, typy.get(i).id());
                    break;
                }
            }
        }
        else if (title.contains("Spawner: ")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            String typId = otwartySpawnerTyp.get(player.getUniqueId());
            if (typId == null) return;

            IslandScreen screen = gui.spawnerPodmenu();
            if (jestSlotem(screen, "ILOSC", slot)) { ulepszSpawnerStatystyke(player, typId, SUFIKS_ILOSC); }
            else if (jestSlotem(screen, "SZYBKOSC", slot)) { ulepszSpawnerStatystyke(player, typId, SUFIKS_SZYBKOSC); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWzrostuDropow(player); }
        }
        else if (title.contains("Członkowie Wyspy")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            ItemStack clicked = event.getCurrentItem();
            IslandScreen screen = gui.czlonkowieWyspy();
            if (clicked == null || clicked.getType() == screen.tlo()) return;

            if (jestSlotem(screen, "POWROT", slot)) { // Powrót do Ustawień Wyspy
                otworzMenuUstawienWyspy(player);
            } else if (jestSlotem(screen, "ZAPROS", slot)) { // Zaproś gracza (przez czat)
                player.closeInventory();
                pendingInviteChat.add(player.getUniqueId());
                player.sendMessage(Component.text("Wpisz na czacie nick gracza, którego chcesz zaprosić na wyspę (lub wpisz 'anuluj'):", NamedTextColor.YELLOW));
            } else {
                Map<Integer, UUID> mapaSlotow = slotyCzlonkow.get(player.getUniqueId());
                UUID targetUUID = mapaSlotow != null ? mapaSlotow.get(slot) : null;
                if (targetUUID != null) {
                    if (event.getClick().isRightClick()) {
                        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
                        IslandData data = islandDatabase.get(ownerUUID);
                        if (data != null) {
                            IslandRole obecna = data.getRole(targetUUID);
                            zmienRoleCzlonka(player, targetUUID, obecna == IslandRole.ADMIN ? IslandRole.CZLONEK : IslandRole.ADMIN);
                        }
                    } else {
                        usunCzlonka(player, targetUUID);
                    }
                    otworzMenuCzlonkow(player); // odśwież listę
                }
            }
        }
    }

    /**
     * Lista aktualnych członków wyspy z możliwością usunięcia/zmiany rangi + przycisk
     * zapraszania nowych. Dostępne wyłącznie dla właściciela i adminów - zwykli
     * członkowie w ogóle nie mogą tego otworzyć (wlasnaWyspaJakoZarzadca odrzuca ich
     * z komunikatem).
     */
    public void otworzMenuCzlonkow(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        IslandScreen screen = gui.czlonkowieWyspy();
        Inventory inv = Bukkit.createInventory(null, screen.size(), Component.text("Członkowie Wyspy", NamedTextColor.YELLOW, TextDecoration.BOLD));
        wypelnijTlo(inv, screen);

        boolean viewerIsOwner = data.getOwnerUUID().equals(player.getUniqueId());

        // Nieklikalna karta właściciela, żeby lista jasno pokazywała kto nim jest.
        @SuppressWarnings("deprecation")
        OfflinePlayer ownerOffline = Bukkit.getOfflinePlayer(data.getOwnerUUID());
        String ownerNick = ownerOffline.getName() != null ? ownerOffline.getName() : data.getOwnerUUID().toString();
        ItemStack kartaWlasciciela = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta metaWlasciciela = (SkullMeta) kartaWlasciciela.getItemMeta();
        metaWlasciciela.setOwningPlayer(ownerOffline);
        metaWlasciciela.displayName(Component.text("[WŁAŚCICIEL] " + ownerNick, NamedTextColor.GOLD, TextDecoration.BOLD));
        metaWlasciciela.lore(List.of(Component.text("Właściciel wyspy", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        kartaWlasciciela.setItemMeta(metaWlasciciela);
        inv.setItem(gui.czlonkowieSlotWlasciciela(), kartaWlasciciela);

        Map<Integer, UUID> mapaSlotow = new HashMap<>();
        int slot = gui.czlonkowiePierwszySlot();
        for (UUID memberUUID : data.getMembers()) {
            if (slot > gui.czlonkowieOstatniSlot()) break; // zabezpieczenie na wypadek bardzo dużej liczby członków

            IslandRole rola = data.getRole(memberUUID);
            boolean memberIsAdmin = rola == IslandRole.ADMIN;

            @SuppressWarnings("deprecation")
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(memberUUID);
            String nick = offlinePlayer.getName() != null ? offlinePlayer.getName() : memberUUID.toString();

            ItemStack glowa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) glowa.getItemMeta();
            meta.setOwningPlayer(offlinePlayer);
            meta.displayName(memberIsAdmin
                    ? Component.text("[ADMIN] " + nick, NamedTextColor.GOLD, TextDecoration.BOLD)
                    : Component.text(nick, NamedTextColor.AQUA, TextDecoration.BOLD));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Rola: " + (memberIsAdmin ? "Administrator" : "Członek"), memberIsAdmin ? NamedTextColor.GOLD : NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            if (viewerIsOwner) {
                lore.add(Component.text("LPM: Usuń z wyspy", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text(memberIsAdmin ? "PPM: Zdegraduj do Członka" : "PPM: Awansuj na Admina", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            } else if (!memberIsAdmin) {
                lore.add(Component.text("LPM: Usuń z wyspy", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("Nie możesz zarządzać innym administratorem", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            glowa.setItemMeta(meta);

            inv.setItem(slot, glowa);
            mapaSlotow.put(slot, memberUUID);
            slot++;
        }
        slotyCzlonkow.put(player.getUniqueId(), mapaSlotow);

        ustawPrzycisk(inv, screen, "ZAPROS", null);
        ustawPrzycisk(inv, screen, "POWROT", null);

        player.openInventory(inv);
    }

    /** Jedyne miejsce egzekwujące kto kogo może wyrzucić - używane identycznie przez komendę i GUI. */
    private void usunCzlonka(Player actor, UUID targetUUID) {
        UUID ownerUUID = playerIslandMap.get(actor.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null) return;

        if (!mozeZarzadzac(actor.getUniqueId(), data)) {
            actor.sendMessage(Component.text("Tylko właściciel i administratorzy mogą usuwać członków.", NamedTextColor.RED));
            return;
        }
        boolean actorIsOwner = data.getOwnerUUID().equals(actor.getUniqueId());
        if (!actorIsOwner && data.getRole(targetUUID) == IslandRole.ADMIN) {
            actor.sendMessage(Component.text("Nie możesz usunąć innego administratora wyspy.", NamedTextColor.RED));
            return;
        }

        if (data.getMembers().remove(targetUUID)) {
            data.getMemberRoles().remove(targetUUID);
            zapiszWyspy();

            @SuppressWarnings("deprecation")
            String nick = Bukkit.getOfflinePlayer(targetUUID).getName();
            actor.sendMessage(Component.text("Usunięto gracza " + (nick != null ? nick : targetUUID) + " z wyspy.", NamedTextColor.YELLOW));

            Player targetOnline = Bukkit.getPlayer(targetUUID);
            if (targetOnline != null) {
                targetOnline.sendMessage(Component.text("Zostałeś usunięty z wyspy gracza " + actor.getName() + ".", NamedTextColor.RED));
            }
        }
    }

    private void uprosGranice(Player player) {
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null) {
            player.sendMessage(Component.text("Nie masz jeszcze wyspy!", NamedTextColor.RED));
            return;
        }

        if (data.getBorderSize() >= tuning.borderMaxRozmiar()) {
            player.sendMessage(Component.text("Osiągnięto maksymalny rozmiar wyspy (" + tuning.borderMaxRozmiar() + " bloków)!", NamedTextColor.RED));
            return;
        }

        int cost = data.getBorderSize() * tuning.borderKosztZaBlok();
        if (!data.odejmijZBanku(cost)) {
            player.sendMessage(Component.text("Bank wyspy nie ma tyle pieniędzy! Potrzeba " + cost + " $, w banku jest " + formatKwote(data.getBankBalance()) + " $. Wpłać przez /is wplac.", NamedTextColor.RED));
            return;
        }

        data.setBorderSize(Math.min(data.getBorderSize() + tuning.borderPrzyrostNaUlepszenie(), tuning.borderMaxRozmiar()));
        ustawWizualnyBorder(player, data);
        zapiszWyspy();
        Bukkit.getPluginManager().callEvent(new IslandUpgradeEvent(player, data));

        player.sendMessage(Component.text("Sukces! Powiększono teren wyspy. Nowy promień: " + data.getBorderSize() + " bloków.", NamedTextColor.GREEN));
        otworzMenuUlepszen(player);
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Nie łapiemy tych trzech promptów wcale, jeśli gracz akurat na żaden nie czeka -
        // event.message() do String tylko wtedy, gdy faktycznie trzeba go przeczytać.
        if (!pendingDeleteConfirmation.contains(uuid) && !pendingInviteChat.contains(uuid) && !pendingNameChat.contains(uuid)) {
            return;
        }
        String wiadomosc = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // Potwierdzenie usunięcia wyspy przez czat - wspólne dla kliknięcia w GUI (kosz)
        // i komendy /is usun, obie tylko uzbrajają pendingDeleteConfirmation (patrz
        // ustawOczekiwanieNaPotwierdzenie). Cokolwiek innego niż dokładnie "Tak zgadzam się"
        // anuluje. Akceptujemy też wersję bez polskich znaków ("sie") - część graczy nie
        // ma wygodnego sposobu wpisania "ę" na czacie.
        if (pendingDeleteConfirmation.contains(uuid)) {
            event.setCancelled(true);

            boolean potwierdzone = wiadomosc.equalsIgnoreCase("Tak zgadzam się")
                    || wiadomosc.equalsIgnoreCase("Tak zgadzam sie");
            if (potwierdzone) {
                // NIE usuwamy tu z pendingDeleteConfirmation - to robi samo potwierdzUsuniecie()
                // po swoim guardzie (patrz niżej). Usuwanie TUTAJ było prawdziwą przyczyną, dla
                // której usuwanie przez czat nigdy nie działało: potwierdzUsuniecie() wywoływane
                // chwilę później (na głównym wątku, przez runTask) widziało że wpis już zniknął
                // i przerywało się natychmiast na własnym guardzie - zero usunięcia, zero błędu.
                //
                // AsyncChatEvent leci na wątku czatu, nie na głównym wątku serwera -
                // potwierdzUsuniecie() teleportuje graczy i czyści chunki wyspy, więc musi
                // wrócić na główny wątek.
                Bukkit.getScheduler().runTask(plugin, () -> potwierdzUsuniecie(player));
            } else {
                pendingDeleteConfirmation.remove(uuid);
                player.sendMessage(Component.text("Anulowano usuwanie wyspy.", NamedTextColor.YELLOW));
            }
            return;
        }

        if (pendingInviteChat.contains(uuid)) {
            event.setCancelled(true);
            pendingInviteChat.remove(uuid);

            String targetName = wiadomosc;
            if (targetName.equalsIgnoreCase("anuluj")) {
                player.sendMessage(Component.text("Anulowano zapraszanie gracza.", NamedTextColor.RED));
                return;
            }

            // Ponowna walidacja uprawnień - stan mógł się zmienić w czasie, gdy okno czatu było otwarte.
            IslandData data = wlasnaWyspaJakoZarzadca(player);
            if (data == null) return;

            Player target = Bukkit.getPlayer(targetName);
            if (target == null || !target.isOnline()) {
                player.sendMessage(Component.text("Nie znaleziono gracza o takim nicku lub jest offline.", NamedTextColor.RED));
                return;
            }

            wykonajZaproszenie(player, target);
            return;
        }

        if (pendingNameChat.contains(uuid)) {
            event.setCancelled(true);
            pendingNameChat.remove(uuid);
            ustawNazweWyspyZCzatu(player, wiadomosc);
        }
    }

    /** Otwiera czatowy prompt zmiany nazwy - wołane z przycisku "Nazwa Wyspy" w Ustawieniach Wyspy. */
    public void rozpocznijZmianeNazwyWyspy(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        player.closeInventory();
        pendingNameChat.add(player.getUniqueId());
        player.sendMessage(Component.text("Wpisz na czacie nową nazwę wyspy (max " + tuning.maxDlugoscNazwyWyspy() + " znaków, lub wpisz 'anuluj'):", NamedTextColor.YELLOW));
    }

    /** Rdzeń zmiany nazwy z czatu - patrz otworzMenuUstawienWyspy (przycisk "Nazwa Wyspy"). */
    private void ustawNazweWyspyZCzatu(Player player, String wiadomosc) {
        if (wiadomosc.equalsIgnoreCase("anuluj")) {
            player.sendMessage(Component.text("Anulowano zmianę nazwy wyspy.", NamedTextColor.RED));
            return;
        }

        // Ponowna walidacja uprawnień - stan mógł się zmienić w czasie, gdy okno czatu było otwarte.
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        String nazwa = wiadomosc.trim();
        if (nazwa.isEmpty() || nazwa.length() > tuning.maxDlugoscNazwyWyspy()) {
            player.sendMessage(Component.text("Nazwa musi mieć 1-" + tuning.maxDlugoscNazwyWyspy() + " znaków.", NamedTextColor.RED));
            return;
        }

        data.setCustomName(nazwa);
        zapiszWyspy();
        player.sendMessage(Component.text("Ustawiono nazwę wyspy: " + nazwa, NamedTextColor.GREEN));
    }

    /**
     * Prawdziwy, skonfigurowany przez admina spawn serwera (/@setspawn) - nie surowy
     * spawn świata Bukkit, który w świecie skyblockowym może być gdziekolwiek. Opcjonalne
     * (mainplugins-spawn może nie być wgrany), więc z fallbackiem na wypadek jego braku.
     */
    private Location spawnLokalizacja() {
        SpawnService spawnService = CoreAPI.getSpawnService();
        return spawnService != null ? spawnService.getSpawn() : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public void potwierdzUsuniecie(Player player) {
        UUID uuid = player.getUniqueId();
        if (!pendingDeleteConfirmation.contains(uuid)) return;

        pendingDeleteConfirmation.remove(uuid);
        UUID ownerUUID = playerIslandMap.remove(uuid);
        IslandData data = islandDatabase.remove(ownerUUID);

        if (data != null) {
            Location spawnLoc = spawnLokalizacja();

            // Usuń z mapy i teleportuj na spawn wszystkich obecnie online członków - ich
            // przynależność do tej wyspy właśnie znika, więc nie mogą zostać "uwięzieni"
            // na terenie, który za chwilę jest czyszczony (patrz wyczyscTerenWyspy niżej).
            for (UUID memberUUID : data.getMembers()) {
                playerIslandMap.remove(memberUUID);
                Player memberOnline = Bukkit.getPlayer(memberUUID);
                if (memberOnline != null) {
                    memberOnline.teleport(spawnLoc);
                    memberOnline.sendMessage(Component.text("Wyspa, na której byłeś, została usunięta przez właściciela.", NamedTextColor.RED));
                }
            }
            zapiszWyspy(); // wyspa usunięta z islandDatabase wcześniej - ten zapis usuwa ją też z wyspy.yml

            player.teleport(spawnLoc);
            // Border (zarówno właściciela, jak i przeteleportowanych wyżej członków) jest
            // teraz obsługiwany automatycznie przez BorderManager.onWorldChange - spawn jest
            // w innym świecie niż skyblockWorld, więc ta zmiana świata sama wyczyści border.

            wyczyscTerenWyspy(data);

            player.sendMessage(Component.text("Twoja wyspa została bezpowrotnie usunięta ze świata.", NamedTextColor.RED));
        }
    }

    /**
     * Zwraca wyspę, której obszar (środek ± promień) obejmuje podaną lokalizację,
     * albo null jeśli lokalizacja nie leży na żadnej wyspie (np. świat inny niż
     * skyblockWorld, albo pusta przestrzeń między wyspami). Używane przez
     * IslandProtectionManager do sprawdzania, czy dana akcja gracza dzieje się
     * na cudzym terenie.
     */
    IslandData znajdzWyspePod(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().equals(skyblockWorld)) return null;

        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        for (IslandData data : islandDatabase.values()) {
            int promien = data.getBorderSize();
            if (Math.abs(x - data.getCenterX()) <= promien && Math.abs(z - data.getCenterZ()) <= promien) {
                return data;
            }
        }
        return null;
    }

    /** Czy podany świat to świat wysp - używane przez IslandProtectionManager do twardego zamykania granic. */
    boolean jestSwiatemWysp(World world) {
        return skyblockWorld.equals(world);
    }

    /** Zapasowy pełny zapis na wyłączeniu pluginu - każda zmiana i tak zapisuje się od razu. */
    public void zapiszWszystkieWyspy() {
        zapiszWyspy();
    }

    /** Package-private - zwraca wszystkie wyspy, np. do cyklicznych skanów całej bazy. */
    Collection<IslandData> wszystkieWyspy() {
        return islandDatabase.values();
    }

    /** Zwraca 0 dla materiałów spoza wyspy-config.yml: wartosci-blokow - patrz IslandTuning.wartoscBloku. */
    double wartoscBloku(Material material) {
        return tuning.wartoscBloku(material);
    }

    // ---- Implementacja IslandService (dla HUD-a i ewentualnych innych konsumentów) ----

    @Override
    public int getIslandCount() {
        return islandDatabase.size();
    }

    @Override
    public List<IslandSummary> getTopIslands(int limit) {
        List<IslandSummary> wynik = new ArrayList<>();
        for (IslandData data : islandDatabase.values()) {
            wynik.add(toSummary(data));
        }
        wynik.sort((a, b) -> Integer.compare(b.borderSize(), a.borderSize()));
        return wynik.size() > limit ? wynik.subList(0, limit) : wynik;
    }

    @Override
    public IslandSummary getIslandOf(UUID playerUUID) {
        UUID ownerUUID = playerIslandMap.get(playerUUID);
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        return data != null ? toSummary(data) : null;
    }

    private IslandSummary toSummary(IslandData data) {
        @SuppressWarnings("deprecation")
        String nick = Bukkit.getOfflinePlayer(data.getOwnerUUID()).getName();
        return new IslandSummary(
                data.getOwnerUUID(),
                nick != null ? nick : data.getOwnerUUID().toString().substring(0, 8),
                data.getBorderSize(),
                data.getMembers().size(),
                new HashMap<>(data.getSpawnerLevels())
        );
    }

    /**
     * Czyści teren wyspy: cała kolumna od dna do sufitu świata (a nie tylko wąski
     * pasek Y ±kilkadziesiąt bloków od punktu wklejenia schematu - WorldBorder nie
     * ogranicza Y, więc gracz mógł wykopać się do bedrocku albo zbudować wieżę
     * i takie bloki bez tego zostawałyby na zawsze jako "ślad" po usuniętej wyspie).
     *
     * WAŻNE: wymuszamy wczytanie (getChunkAt) każdego chunka zamiast pomijać
     * niezaładowane. Gracz w momencie wywołania tej metody jest już teleportowany
     * poza skyblockWorld (patrz potwierdzUsuniecie) - w praktyce niemal wszystkie
     * chunki jego wyspy są wtedy "niezaładowane", więc filtrowanie po isChunkLoaded
     * powodowało, że usuwanie realnie nic nie czyściło (wyspa zostawała w świecie).
     * Generowanie pustego chunka VoidGeneratorem jest praktycznie darmowe, więc
     * to bezpieczny kompromis - kosztem tego jest tylko rozłożenie pracy na tick-i.
     */
    private void wyczyscTerenWyspy(IslandData data) {
        int promien = data.getBorderSize() + tuning.zapasNaSchemat();
        int minX = data.getCenterX() - promien;
        int maxX = data.getCenterX() + promien;
        int minZ = data.getCenterZ() - promien;
        int maxZ = data.getCenterZ() + promien;
        int minY = skyblockWorld.getMinHeight();
        int maxY = skyblockWorld.getMaxHeight() - 1;

        int minChunkX = minX >> 4, maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4, maxChunkZ = maxZ >> 4;
        int chunkiNaTick = tuning.chunkiNaTick();

        new org.bukkit.scheduler.BukkitRunnable() {
            int cx = minChunkX;
            int cz = minChunkZ;

            @Override
            public void run() {
                int przetworzoneWTymTicku = 0;
                while (przetworzoneWTymTicku < chunkiNaTick) {
                    if (cx > maxChunkX) {
                        cancel();
                        return;
                    }

                    skyblockWorld.getChunkAt(cx, cz); // wymusza wczytanie/wygenerowanie
                    wyczyscChunk(cx, cz, minX, maxX, minZ, maxZ, minY, maxY);

                    cz++;
                    if (cz > maxChunkZ) {
                        cz = minChunkZ;
                        cx++;
                    }
                    przetworzoneWTymTicku++;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void wyczyscChunk(int chunkX, int chunkZ, int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
        int startX = Math.max(minX, chunkX << 4);
        int endX = Math.min(maxX, (chunkX << 4) + 15);
        int startZ = Math.max(minZ, chunkZ << 4);
        int endZ = Math.min(maxZ, (chunkZ << 4) + 15);

        for (int dx = startX; dx <= endX; dx++) {
            for (int dz = startZ; dz <= endZ; dz++) {
                for (int dy = minY; dy <= maxY; dy++) {
                    org.bukkit.block.Block block = skyblockWorld.getBlockAt(dx, dy, dz);
                    if (block.getType() != Material.AIR) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }
}
