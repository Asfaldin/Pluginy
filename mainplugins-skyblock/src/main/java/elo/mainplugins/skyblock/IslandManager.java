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
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.world.VoidGenerator;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class IslandManager implements Listener, IslandService {

    private final Plugin plugin;
    private final World skyblockWorld;
    private final File schemFile;
    private final EconomyService economyManager;

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

        // Poziom (domyślnie 1) każdego typu customowego spawnera wykupionego przez
        // właściciela wyspy - klucz to SpawnerType.name() z mainplugins-spawners,
        // ale IslandData celowo trzyma go jako zwykły String (patrz komentarz w
        // IslandSummary) - skyblock nie ma i nie powinien mieć zależności na moduł spawnerów.
        private final Map<String, Integer> spawnerLevels = new HashMap<>();

        // Własny punkt teleportu ustawiony przez /is sethome - null dopóki gracz go nie
        // ustawi, wtedy teleportDoWyspy() używa domyślnego środka wyspy zamiast tego.
        private Double homeX;
        private Double homeY;
        private Double homeZ;
        private float homeYaw;
        private float homePitch;

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

        // Rola WYŁĄCZNIE dla członków spoza właściciela - właściciel nigdy nie jest kluczem
        // w tej mapie, jego status wynika zawsze z porównania UUID z ownerUUID (patrz mozeZarzadzac).
        private final Map<UUID, IslandRole> memberRoles = new HashMap<>();
        public IslandRole getRole(UUID uuid) { return memberRoles.getOrDefault(uuid, IslandRole.CZLONEK); }
        public void setRole(UUID uuid, IslandRole role) { memberRoles.put(uuid, role); }
        public Map<UUID, IslandRole> getMemberRoles() { return memberRoles; }
    }

    /** Ranga członka wyspy (nie dotyczy właściciela - patrz komentarz przy IslandData.memberRoles). */
    public enum IslandRole { CZLONEK, ADMIN }

    /** Identyfikator musi się zgadzać z SpawnerType.name() w mainplugins-spawners - patrz komentarz przy IslandData.spawnerLevels. */
    private record SpawnerTypInfo(String id, String nazwaOdmieniona, Material ikona) {}

    private static final int SPAWNER_MAX_LEVEL = 5;
    private static final int SPAWNER_KOSZT_ZA_POZIOM = 5000;

    // MUSZĄ się zgadzać 1:1 z tymi samymi literałami w SpawnerManager (mainplugins-spawners).
    private static final String SUFIKS_ILOSC = "_ILOSC";
    private static final String SUFIKS_SZYBKOSC = "_SZYBKOSC";

    private static final List<SpawnerTypInfo> SPAWNER_TYPY = List.of(
            new SpawnerTypInfo("PIGLIN", "Piglinów", Material.GOLD_NUGGET),
            new SpawnerTypInfo("SHEEP", "Owiec", Material.WHITE_WOOL),
            new SpawnerTypInfo("RABBIT", "Królików", Material.RABBIT_HIDE),
            new SpawnerTypInfo("BREEZE", "Breeze'ów", Material.BREEZE_ROD),
            new SpawnerTypInfo("GLOW_SQUID", "Świetlistych Kałamarnic", Material.GLOW_INK_SAC)
    );

    private final Map<UUID, IslandData> islandDatabase = new HashMap<>();
    private final Map<UUID, UUID> playerIslandMap = new HashMap<>();
    private final Set<UUID> pendingDeleteConfirmation = new HashSet<>();
    private final Set<UUID> pendingLeaveConfirmation = new HashSet<>();
    private final Set<UUID> pendingInviteChat = new HashSet<>();
    // Zapamiętuje, który slot w GUI "Członkowie Wyspy" odpowiada za którego gracza
    private final Map<UUID, Map<Integer, UUID>> slotyCzlonkow = new HashMap<>();
    // Który typ spawnera gracz aktualnie ma otwarty w podmenu "Spawner: X" (Ilość/Szybkość)
    private final Map<UUID, String> otwartySpawnerTyp = new HashMap<>();
    private int nextIslandId = 0;

    private final File plikWysp;
    private final FileConfiguration configWysp;

    // Maksymalny rozmiar WorldBordera dopuszczalny przez samego Minecrafta to
    // 5.9999968E7 (59 999 968) - wartość NIECO mniejsza niż okrągłe 60 milionów.
    // Wcześniejsze 60000000 przekraczało ten limit o 32 i wywalało IllegalArgumentException
    // przy każdym /is border i usuwaniu wyspy. Używamy tego jako "brak borderu".
    private static final double ROZMIAR_BEZ_BORDERU = 59999900;

    // Odstęp między środkami sąsiednich wysp na siatce (patrz stworzWyspe()).
    // MAX_BORDER_SIZE musi zostać wyraźnie poniżej połowy tej wartości, inaczej
    // dwie maksymalnie rozbudowane, sąsiadujące wyspy mogłyby się kiedyś zetknąć.
    private static final int ODSTEP_SIATKI_WYSP = 10000;

    // Twardy limit promienia wyspy (niezależny od tego, ile gracz ma pieniędzy).
    // Przy odstępie siatki 10000 zostawia to min. 8500 bloków pustki między
    // granicami dwóch sąsiednich, maksymalnie rozbudowanych wysp - więcej niż
    // jakikolwiek realny zasięg renderowania gracza, więc wyspy nigdy się nie
    // zetkną ani nie będą się nawzajem widoczne "z daleka".
    private static final int MAX_BORDER_SIZE = 750;

    public IslandManager(Plugin plugin, EconomyService economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;

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
        wczytajWyspy();
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
            int borderSize = configWysp.getInt(path + "borderSize", 50);

            IslandData data = new IslandData(id, ownerUUID, centerX, centerZ, borderSize);
            data.setAllowMobs(configWysp.getBoolean(path + "allowMobs", true));
            data.setAllowPvP(configWysp.getBoolean(path + "allowPvP", false));
            data.setAllowBreak(configWysp.getBoolean(path + "allowBreak", false));
            data.setVisualBorder(configWysp.getBoolean(path + "visualBorder", true));

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

            if (data.hasCustomHome()) {
                configWysp.set(path + "home.x", data.getHomeX());
                configWysp.set(path + "home.y", data.getHomeY());
                configWysp.set(path + "home.z", data.getHomeZ());
                configWysp.set(path + "home.yaw", data.getHomeYaw());
                configWysp.set(path + "home.pitch", data.getHomePitch());
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

        try {
            configWysp.save(plikWysp);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie można zapisać wyspy.yml: " + e.getMessage());
        }
    }

    /**
     * /is [subkomenda]. Bez argumentów (albo z samym "zmenu") - stwórz/otwórz panel
     * wyspy, jak dawniej. Reszta subkomend to odpowiedniki przycisków z GUI, dla
     * graczy, którzy wolą wpisać komendę niż klikać w menu.
     */
    public void handleCommand(Player player, String[] args) {
        UUID uuid = player.getUniqueId();
        boolean zMenu = (args.length > 0 && args[args.length - 1].equalsIgnoreCase("zmenu"));
        String sub = args.length > 0 ? args[0].toLowerCase() : "";

        switch (sub) {
            case "menu" -> otworzMenuWyspy(player, zMenu);
            case "delete" -> obslugaUsuwaniaKomenda(player, args);
            case "border" -> przelaczWizualnyBorder(player);
            case "guests", "build" -> przelaczBudowanieDlaGosci(player);
            case "pvp" -> przelaczPvP(player);
            case "mobs" -> przelaczPotwory(player);
            case "upgrade" -> otworzMenuUlepszen(player);
            case "members" -> otworzMenuCzlonkow(player);
            case "add", "invite" -> zaprosGracza(player, args);
            case "accept" -> zaakceptujZaproszenie(player);
            case "deny" -> odrzucZaproszenie(player);
            case "leave" -> opuscWyspe(player);
            case "promote" -> zmienRoleKomenda(player, args, IslandRole.ADMIN);
            case "demote" -> zmienRoleKomenda(player, args, IslandRole.CZLONEK);
            case "remove" -> usunCzlonkaKomenda(player, args);
            case "home" -> teleportDoWyspy(player);
            case "sethome" -> ustawDomek(player);
            default -> {
                if (!playerIslandMap.containsKey(uuid)) {
                    stworzWyspe(player, zMenu);
                } else {
                    teleportDoWyspy(player);
                }
            }
        }
    }

    private static final long TIMEOUT_POTWIERDZENIA_TICKS = 15 * 20L;

    /** Wspólne dla GUI (kosz) i komendy (/is delete) - potwierdzenie wygasa po 15s. */
    private void ustawOczekiwanieNaPotwierdzenie(UUID uuid) {
        pendingDeleteConfirmation.add(uuid);
        Bukkit.getScheduler().runTaskLater(plugin, () -> pendingDeleteConfirmation.remove(uuid), TIMEOUT_POTWIERDZENIA_TICKS);
    }

    private void obslugaUsuwaniaKomenda(Player player, String[] args) {
        UUID uuid = player.getUniqueId();
        if (!playerIslandMap.containsKey(uuid)) {
            player.sendMessage(Component.text("Nie posiadasz wyspy!", NamedTextColor.RED));
            return;
        }
        if (!uuid.equals(playerIslandMap.get(uuid))) {
            player.sendMessage(Component.text("Tylko właściciel może usunąć wyspę!", NamedTextColor.RED));
            return;
        }

        if (args.length > 1 && args[1].equalsIgnoreCase("confirm")) {
            if (oczekujeNaPotwierdzenie(uuid)) {
                potwierdzUsuniecie(player);
            } else {
                player.sendMessage(Component.text("Najpierw wpisz /is delete, aby rozpocząć usuwanie.", NamedTextColor.RED));
            }
            return;
        }

        ustawOczekiwanieNaPotwierdzenie(uuid);
        player.sendMessage(Component.text("UWAGA: Twoja wyspa zostanie usunięta bezpowrotnie!", NamedTextColor.RED, TextDecoration.BOLD));
        player.sendMessage(Component.text("Wpisz /is delete confirm w ciągu 15 sekund, aby potwierdzić.", NamedTextColor.YELLOW));
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
    private IslandData wlasnaWyspaJakoZarzadca(Player player) {
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

    // ---- Zaproszenia na wyspę (zastępują dawne natychmiastowe dodawanie bez zgody celu) ----

    private final Map<UUID, PendingInvite> pendingInvites = new HashMap<>();
    private static final long TIMEOUT_ZAPROSZENIA_TICKS = 60 * 20L;

    private record PendingInvite(UUID ownerUUID, UUID inviterUUID) {}

    /**
     * Rdzeń wysyłki zaproszenia - współdzielony przez komendę /is invite i czatowy
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
        }, TIMEOUT_ZAPROSZENIA_TICKS);

        inviter.sendMessage(Component.text("Wysłano zaproszenie do " + target.getName() + ".", NamedTextColor.GREEN));
        target.sendMessage(Component.text("Zostałeś zaproszony na wyspę gracza " + inviter.getName() + "! Wpisz /is accept lub /is deny w ciągu 60 sekund.", NamedTextColor.AQUA));
    }

    private void zaprosGracza(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Użycie: /is invite <gracz>", NamedTextColor.RED));
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

    /** Samodzielne opuszczenie wyspy przez nie-właściciela - "hermetyczność" (patrz /is leave, przycisk w panelu). */
    public void opuscWyspe(Player player) {
        UUID uuid = player.getUniqueId();
        UUID ownerUUID = playerIslandMap.get(uuid);
        if (ownerUUID == null) {
            player.sendMessage(Component.text("Nie jesteś członkiem żadnej wyspy!", NamedTextColor.RED));
            return;
        }
        if (ownerUUID.equals(uuid)) {
            player.sendMessage(Component.text("Jesteś właścicielem tej wyspy - użyj /is delete, aby ją usunąć.", NamedTextColor.RED));
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

    /** /is promote|demote <gracz> - wyłącznie właściciel, egzekwowane wewnątrz zmienRoleCzlonka. */
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
            player.sendMessage(Component.text("Użycie: /is remove <gracz>", NamedTextColor.RED));
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

    private void stworzWyspe(Player player, boolean zMenu) {
        if (!schemFile.exists()) {
            player.sendMessage(Component.text("Błąd: Brak pliku wyspa_startowa.schem w FastAsyncWorldEdit/schematics!", NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("Tworzenie Twojej wyspy...", NamedTextColor.YELLOW));

        int myIslandId = nextIslandId++;
        int x = (myIslandId % 100) * ODSTEP_SIATKI_WYSP;
        int z = (myIslandId / 100) * ODSTEP_SIATKI_WYSP;
        int y = 100;

        IslandData data = new IslandData(myIslandId, player.getUniqueId(), x, z, 50);
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
                    otworzMenuWyspy(player, zMenu);
                });

            } catch (Exception e) {
                e.printStackTrace();
                player.sendMessage(Component.text("Wystąpił błąd podczas generowania wyspy!", NamedTextColor.RED));
            }
        });
    }

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
        // - jeśli gracz ustawił własny punkt przez /is sethome, używamy tego zamiast.
        Location loc = data.hasCustomHome()
                ? new Location(skyblockWorld, data.getHomeX(), data.getHomeY(), data.getHomeZ(), data.getHomeYaw(), data.getHomePitch())
                : new Location(skyblockWorld, data.getCenterX() + 0.5, 101, data.getCenterZ() + 0.5);
        zabezpieczPunktSpawnu(loc);
        player.teleport(loc);
        ustawWizualnyBorder(player, data);
        player.sendMessage(Component.text("Przeteleportowano na wyspę!", NamedTextColor.AQUA));
    }

    // Ile bloków w dół szukamy gruntu pod punktem teleportu, zanim uznamy że tam
    // po prostu jest za głęboka dziura i trzeba szukać gdzie indziej.
    private static final int MAX_GLEBOKOSC_SZUKANIA_W_DOL = 10;
    // Promień (we wszystkich kierunkach) szukania najbliższego bloku, gdy w dół nic nie ma.
    private static final int PROMIEN_SZUKANIA_OBOK = 5;

    /**
     * Gracz mógł wyczyścić cały teren pod punktem teleportu (ręcznie albo np. wybuchem
     * TNT), albo grunt wyspy po prostu leży niżej niż spodziewane Y=101 - bez tego
     * gracz teleportowałby się w pustkę i spadał aż do obrażeń od "pustki" (void).
     * Kolejność prób: 1) grunt prosto pod celem (do 10 bloków w dół) - typowy przypadek,
     * najtańszy; 2) najbliższy solidny blok w promieniu 5 dookoła, gdy w dół jest za
     * głęboko; 3) w ostateczności - postaw ziemię dokładnie w oryginalnym miejscu.
     */
    private void zabezpieczPunktSpawnu(Location loc) {
        Location wDol = szukajGruntuWDol(loc, MAX_GLEBOKOSC_SZUKANIA_W_DOL);
        if (wDol != null) {
            przeniesXYZ(loc, wDol);
            return;
        }

        Location obok = szukajNajblizszegoGruntu(loc, PROMIEN_SZUKANIA_OBOK);
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
     * /is sethome - nadpisuje domyślny punkt teleportu (środek wyspy) własnym,
     * ustawionym tam, gdzie gracz akurat stoi. To ustawienie WSPÓLNE dla całej wyspy
     * (dotyczy każdego, kto potem wpisze /is albo /is home), więc - tak jak border/PvP/
     * ulepszenia - może to zrobić tylko właściciel albo admin (wlasnaWyspaJakoZarzadca),
     * nie każdy zwykły członek. Wymagamy też, żeby stał na SWOJEJ wyspie - bez tego
     * dałoby się ustawić dom gdziekolwiek w świecie (np. na cudzej wyspie).
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
        player.sendMessage(Component.text("Ustawiono nowy punkt teleportacji (/is home) na Twojej wyspie!", NamedTextColor.GREEN));
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
    }

    // Główne zaktualizowane GUI (54 sloty)
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

        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Panel Wyspy", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta metaTlo = tlo.getItemMeta();
        metaTlo.displayName(Component.empty());
        tlo.setItemMeta(metaTlo);
        for (int i = 0; i < 54; i++) gui.setItem(i, tlo);

        // 1. INFORMACJE
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta mInfo = info.getItemMeta();
        mInfo.displayName(Component.text("Informacje o Wyspie", NamedTextColor.AQUA, TextDecoration.BOLD));
        List<Component> loreInfo = new ArrayList<>();
        Player owner = Bukkit.getPlayer(data.getOwnerUUID());
        String ownerName = owner != null ? owner.getName() : "Nieznany";
        String nazwaRoliGracza = isOwner ? "Właściciel" : (data.getRole(player.getUniqueId()) == IslandRole.ADMIN ? "Admin" : "Członek");
        loreInfo.add(Component.text("Właściciel: " + ownerName, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        loreInfo.add(Component.text("Rozmiar: " + data.getBorderSize() + "x" + data.getBorderSize(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        loreInfo.add(Component.text("Członkowie: " + data.getMembers().size(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        loreInfo.add(Component.text("Twoja rola: " + nazwaRoliGracza, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        mInfo.lore(loreInfo);
        info.setItemMeta(mInfo);
        gui.setItem(11, info);

        // 2. TELEPORT
        ItemStack dom = new ItemStack(Material.OAK_DOOR);
        ItemMeta mDom = dom.getItemMeta();
        mDom.displayName(Component.text("Teleport na Wyspę", NamedTextColor.GREEN, TextDecoration.BOLD));
        mDom.lore(List.of(Component.text("Kliknij, aby wrócić do siebie", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        dom.setItemMeta(mDom);
        gui.setItem(13, dom);

        if (canManage) {
            // 3. ULEPSZENIA
            ItemStack upg = new ItemStack(Material.BEACON);
            ItemMeta mUpg = upg.getItemMeta();
            mUpg.displayName(Component.text("Ulepszenia Wyspy", NamedTextColor.GOLD, TextDecoration.BOLD));
            mUpg.lore(List.of(Component.text("Zwiększ rozmiar wyspy", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            upg.setItemMeta(mUpg);
            gui.setItem(15, upg);

            // 4. BORDER WIZUALNY
            boolean borderOn = data.isVisualBorder();
            ItemStack border = new ItemStack(borderOn ? Material.BLUE_STAINED_GLASS : Material.RED_STAINED_GLASS);
            ItemMeta mBorder = border.getItemMeta();
            mBorder.displayName(Component.text("Wizualny Border", NamedTextColor.BLUE, TextDecoration.BOLD));
            mBorder.lore(List.of(
                    Component.text("Stan: " + (borderOn ? "Włączony" : "Wyłączony"), borderOn ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                    Component.text("Kliknij, aby przełączyć", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            border.setItemMeta(mBorder);
            gui.setItem(29, border);

            // 4b. BUDOWANIE DLA GOŚCI
            boolean breakOn = data.isAllowBreak();
            ItemStack breakItem = new ItemStack(breakOn ? Material.GRASS_BLOCK : Material.BEDROCK);
            ItemMeta mBreak = breakItem.getItemMeta();
            mBreak.displayName(Component.text("Budowanie dla Gości", NamedTextColor.GREEN, TextDecoration.BOLD));
            mBreak.lore(List.of(
                    Component.text("Stan: " + (breakOn ? "Otwarte" : "Zamknięte"), breakOn ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                    Component.text("Właściciel i członkowie budują zawsze", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Kliknij, aby przełączyć", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            breakItem.setItemMeta(mBreak);
            gui.setItem(20, breakItem);

            // 4c. PVP NA WYSPIE
            boolean pvpOn = data.isAllowPvP();
            ItemStack pvpItem = new ItemStack(pvpOn ? Material.IRON_SWORD : Material.SHIELD);
            ItemMeta mPvp = pvpItem.getItemMeta();
            mPvp.displayName(Component.text("PvP na Wyspie", NamedTextColor.RED, TextDecoration.BOLD));
            mPvp.lore(List.of(
                    Component.text("Stan: " + (pvpOn ? "Włączone" : "Wyłączone"), pvpOn ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                    Component.text("Kliknij, aby przełączyć", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            pvpItem.setItemMeta(mPvp);
            gui.setItem(22, pvpItem);

            // 4d. POTWORY NA WYSPIE
            boolean mobyOn = data.isAllowMobs();
            ItemStack mobyItem = new ItemStack(mobyOn ? Material.ZOMBIE_HEAD : Material.TOTEM_OF_UNDYING);
            ItemMeta mMoby = mobyItem.getItemMeta();
            mMoby.displayName(Component.text("Potwory na Wyspie", NamedTextColor.DARK_GREEN, TextDecoration.BOLD));
            mMoby.lore(List.of(
                    Component.text("Stan: " + (mobyOn ? "Mogą się pojawiać" : "Zablokowane"), mobyOn ? NamedTextColor.GREEN : NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                    Component.text("Kliknij, aby przełączyć", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            mobyItem.setItemMeta(mMoby);
            gui.setItem(24, mobyItem);

            // 5. CZŁONKOWIE / ZARZĄDZAJ
            ItemStack dodaj = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta mDodaj = dodaj.getItemMeta();
            mDodaj.displayName(Component.text("Członkowie Wyspy", NamedTextColor.YELLOW, TextDecoration.BOLD));
            mDodaj.lore(List.of(
                    Component.text("Aktualnie: " + data.getMembers().size() + " członków", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Kliknij, aby zarządzać", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            dodaj.setItemMeta(mDodaj);
            gui.setItem(31, dodaj);
        }

        // 6. USUŃ WYSPĘ (właściciel) / OPUŚĆ WYSPĘ (reszta)
        if (isOwner) {
            ItemStack usun = new ItemStack(Material.TNT);
            ItemMeta mUsun = usun.getItemMeta();
            mUsun.displayName(Component.text("Usuń Wyspę", NamedTextColor.DARK_RED, TextDecoration.BOLD));
            mUsun.lore(List.of(Component.text("Ostrzeżenie: Wyspa zniknie bezpowrotnie!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
            usun.setItemMeta(mUsun);
            gui.setItem(33, usun);
        } else {
            ItemStack opusc = new ItemStack(Material.OAK_BOAT);
            ItemMeta mOpusc = opusc.getItemMeta();
            mOpusc.displayName(Component.text("Opuść Wyspę", NamedTextColor.RED, TextDecoration.BOLD));
            mOpusc.lore(List.of(Component.text("Samodzielnie zrezygnujesz z członkostwa", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            opusc.setItemMeta(mOpusc);
            gui.setItem(33, opusc);
        }

        // PRZYCISK POWROTU
        ItemStack wyjscie = new ItemStack(zMenu ? Material.NETHER_STAR : Material.BARRIER);
        ItemMeta mWyjscie = wyjscie.getItemMeta();
        mWyjscie.displayName(Component.text(zMenu ? "« Wróć do Menu głównego" : "Zamknij Panel", NamedTextColor.RED, TextDecoration.BOLD));
        wyjscie.setItemMeta(mWyjscie);
        gui.setItem(49, wyjscie);

        player.openInventory(gui);
    }

    public void otworzMenuUlepszen(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        int currentSize = data.getBorderSize();
        int cost = currentSize * 1000;
        boolean maksimum = currentSize >= MAX_BORDER_SIZE;

        Inventory gui = Bukkit.createInventory(null, 27, Component.text("Ulepszenia Wyspy", NamedTextColor.GOLD, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta metaTlo = tlo.getItemMeta();
        metaTlo.displayName(Component.empty());
        tlo.setItemMeta(metaTlo);
        for (int i = 0; i < 27; i++) gui.setItem(i, tlo);

        ItemStack itemUpgradeSize = new ItemStack(maksimum ? Material.BEDROCK : Material.BEACON);
        ItemMeta metaSize = itemUpgradeSize.getItemMeta();
        metaSize.displayName(Component.text("Powiększ Teren Wyspy", NamedTextColor.YELLOW, TextDecoration.BOLD));
        metaSize.lore(maksimum
                ? List.of(
                        Component.text("Aktualny promień: " + currentSize + " bloków", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("Osiągnięto maksymalny rozmiar wyspy!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)
                )
                : List.of(
                        Component.text("Aktualny promień: " + currentSize + " bloków", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.text("Koszt: " + cost + " $", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("Kliknij, aby powiększyć o 25 bloków!", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)
                ));
        itemUpgradeSize.setItemMeta(metaSize);
        gui.setItem(11, itemUpgradeSize);

        ItemStack itemDropy = new ItemStack(Material.SPAWNER);
        ItemMeta metaDropy = itemDropy.getItemMeta();
        metaDropy.displayName(Component.text("Wzrost Dropów", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
        metaDropy.lore(List.of(
                Component.text("Ulepszaj poziomy customowych spawnerów", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("postawionych na Twojej wyspie", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Kliknij, aby otworzyć", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)
        ));
        itemDropy.setItemMeta(metaDropy);
        gui.setItem(13, itemDropy);

        ItemStack itemBack = new ItemStack(Material.ARROW);
        ItemMeta metaBack = itemBack.getItemMeta();
        metaBack.displayName(Component.text("Powrót do Menu", NamedTextColor.RED, TextDecoration.BOLD));
        itemBack.setItemMeta(metaBack);
        gui.setItem(15, itemBack);

        player.openInventory(gui);
    }

    /**
     * Poziomy (1-5) customowych spawnerów mainplugins-spawners, per wyspa. Sam moduł
     * spawnerów o tym nic nie wie poza odczytem IslandSummary.spawnerLevels() -
     * cała logika ulepszania (koszt, limit) żyje tutaj, w panelu wyspy.
     */
    public void otworzMenuWzrostuDropow(Player player) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        Inventory gui = Bukkit.createInventory(null, 27, Component.text("Wzrost Dropów", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta metaTlo = tlo.getItemMeta();
        metaTlo.displayName(Component.empty());
        tlo.setItemMeta(metaTlo);
        for (int i = 0; i < 27; i++) gui.setItem(i, tlo);

        int[] sloty = {10, 11, 12, 13, 14};
        for (int i = 0; i < SPAWNER_TYPY.size(); i++) {
            SpawnerTypInfo typ = SPAWNER_TYPY.get(i);
            int poziomIlosci = data.getSpawnerLevel(typ.id() + SUFIKS_ILOSC);
            int poziomSzybkosci = data.getSpawnerLevel(typ.id() + SUFIKS_SZYBKOSC);

            ItemStack item = new ItemStack(typ.ikona());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text("Spawner: " + typ.nazwaOdmieniona(), NamedTextColor.YELLOW, TextDecoration.BOLD));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Ilość: " + poziomIlosci + "/" + SPAWNER_MAX_LEVEL, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("Szybkość: " + poziomSzybkosci + "/" + SPAWNER_MAX_LEVEL, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("Kliknij, aby zarządzać ulepszeniami", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);

            gui.setItem(sloty[i], item);
        }

        ItemStack itemBack = new ItemStack(Material.ARROW);
        ItemMeta metaBack = itemBack.getItemMeta();
        metaBack.displayName(Component.text("Powrót do Ulepszeń", NamedTextColor.RED, TextDecoration.BOLD));
        itemBack.setItemMeta(metaBack);
        gui.setItem(22, itemBack);

        player.openInventory(gui);
    }

    private SpawnerTypInfo znajdzTypSpawnera(String id) {
        for (SpawnerTypInfo typ : SPAWNER_TYPY) {
            if (typ.id().equals(id)) return typ;
        }
        return null;
    }

    /** Podmenu jednego typu spawnera - osobne ulepszanie Ilości (mobków/cykl) i Szybkości (odstęp między cyklami). */
    public void otworzMenuUlepszenSpawnera(Player player, String typId) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        SpawnerTypInfo typ = znajdzTypSpawnera(typId);
        if (typ == null) return;

        otwartySpawnerTyp.put(player.getUniqueId(), typId);

        Inventory gui = Bukkit.createInventory(null, 27, Component.text("Spawner: " + typ.nazwaOdmieniona(), NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta metaTlo = tlo.getItemMeta();
        metaTlo.displayName(Component.empty());
        tlo.setItemMeta(metaTlo);
        for (int i = 0; i < 27; i++) gui.setItem(i, tlo);

        int poziomIlosci = data.getSpawnerLevel(typId + SUFIKS_ILOSC);
        int poziomSzybkosci = data.getSpawnerLevel(typId + SUFIKS_SZYBKOSC);

        gui.setItem(11, itemUlepszeniaStatystyki("Ilość", "Więcej mobków na jeden cykl spawnu", typ.ikona(), poziomIlosci));
        gui.setItem(15, itemUlepszeniaStatystyki("Szybkość", "Krótszy odstęp między cyklami spawnu", Material.CLOCK, poziomSzybkosci));

        ItemStack itemBack = new ItemStack(Material.ARROW);
        ItemMeta metaBack = itemBack.getItemMeta();
        metaBack.displayName(Component.text("Powrót do Wzrostu Dropów", NamedTextColor.RED, TextDecoration.BOLD));
        itemBack.setItemMeta(metaBack);
        gui.setItem(22, itemBack);

        player.openInventory(gui);
    }

    private ItemStack itemUlepszeniaStatystyki(String nazwa, String opis, Material ikona, int level) {
        boolean maksimum = level >= SPAWNER_MAX_LEVEL;

        ItemStack item = new ItemStack(ikona);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(nazwa, NamedTextColor.YELLOW, TextDecoration.BOLD));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(opis, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Poziom: " + level + "/" + SPAWNER_MAX_LEVEL, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        if (maksimum) {
            lore.add(Component.text("Osiągnięto maksymalny poziom!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("Koszt ulepszenia: " + (level * SPAWNER_KOSZT_ZA_POZIOM) + " $", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
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
        if (level >= SPAWNER_MAX_LEVEL) {
            player.sendMessage(Component.text("Ta statystyka ma już maksymalny poziom!", NamedTextColor.RED));
            return;
        }

        int cost = level * SPAWNER_KOSZT_ZA_POZIOM;
        if (!economyManager.maWystarczajaco(player.getUniqueId(), cost)) {
            player.sendMessage(Component.text("Nie masz wystarczająco pieniędzy! Potrzebujesz " + cost + " $.", NamedTextColor.RED));
            return;
        }

        economyManager.odejmijKase(player.getUniqueId(), cost);
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

            UUID panelOwnerUUID = playerIslandMap.get(player.getUniqueId());
            IslandData panelData = panelOwnerUUID != null ? islandDatabase.get(panelOwnerUUID) : null;
            boolean panelIsOwner = panelData != null && panelData.getOwnerUUID().equals(player.getUniqueId());

            if (slot == 13) { player.closeInventory(); teleportDoWyspy(player); } // Teleport
            else if (slot == 15) { otworzMenuUlepszen(player); } // Ulepszenia
            else if (slot == 31) { otworzMenuCzlonkow(player); } // Członkowie - lista + zarządzanie
            else if (slot == 29) { przelaczWizualnyBorder(player); otworzMenuWyspy(player, zMenu); } // Border
            else if (slot == 20) { przelaczBudowanieDlaGosci(player); otworzMenuWyspy(player, zMenu); }
            else if (slot == 22) { przelaczPvP(player); otworzMenuWyspy(player, zMenu); }
            else if (slot == 24) { przelaczPotwory(player); otworzMenuWyspy(player, zMenu); }
            else if (slot == 33 && panelIsOwner) { // Kosz / Usunięcie - decyzja po serwerowym isOwner, nie po ikonie klienta
                if (oczekujeNaPotwierdzenie(player.getUniqueId())) {
                    player.closeInventory();
                    potwierdzUsuniecie(player);
                } else {
                    ustawOczekiwanieNaPotwierdzenie(player.getUniqueId());
                    ItemStack item = event.getCurrentItem();
                    if (item != null) {
                        ItemMeta meta = item.getItemMeta();
                        meta.displayName(Component.text("KLIKNIJ PONOWNIE BY USUNĄĆ!", NamedTextColor.DARK_RED, TextDecoration.BOLD));
                        item.setItemMeta(meta);
                    }
                }
            }
            else if (slot == 33) { // Opuść Wyspę (nie-właściciel)
                if (pendingLeaveConfirmation.contains(player.getUniqueId())) {
                    player.closeInventory();
                    pendingLeaveConfirmation.remove(player.getUniqueId());
                    opuscWyspe(player);
                } else {
                    pendingLeaveConfirmation.add(player.getUniqueId());
                    Bukkit.getScheduler().runTaskLater(plugin, () -> pendingLeaveConfirmation.remove(player.getUniqueId()), TIMEOUT_POTWIERDZENIA_TICKS);
                    ItemStack item = event.getCurrentItem();
                    if (item != null) {
                        ItemMeta meta = item.getItemMeta();
                        meta.displayName(Component.text("KLIKNIJ PONOWNIE, ABY OPUŚCIĆ WYSPĘ!", NamedTextColor.DARK_RED, TextDecoration.BOLD));
                        item.setItemMeta(meta);
                    }
                }
            }
            else if (slot == 49) {
                if (zMenu) {
                    player.closeInventory();
                    player.performCommand("menu");
                } else {
                    player.closeInventory();
                }
            }
        }
        else if (title.contains("Ulepszenia Wyspy")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 11) { uprosGranice(player); }
            else if (slot == 13) { otworzMenuWzrostuDropow(player); }
            else if (slot == 15) { otworzMenuWyspy(player, zMenu); }
        }
        else if (title.contains("Wzrost Dropów")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 22) { otworzMenuUlepszen(player); return; }

            int[] sloty = {10, 11, 12, 13, 14};
            for (int i = 0; i < sloty.length; i++) {
                if (sloty[i] == slot) {
                    otworzMenuUlepszenSpawnera(player, SPAWNER_TYPY.get(i).id());
                    break;
                }
            }
        }
        else if (title.contains("Spawner: ")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            String typId = otwartySpawnerTyp.get(player.getUniqueId());
            if (typId == null) return;

            if (slot == 11) { ulepszSpawnerStatystyke(player, typId, SUFIKS_ILOSC); }
            else if (slot == 15) { ulepszSpawnerStatystyke(player, typId, SUFIKS_SZYBKOSC); }
            else if (slot == 22) { otworzMenuWzrostuDropow(player); }
        }
        else if (title.contains("Członkowie Wyspy")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

            if (slot == 49) { // Powrót do panelu wyspy
                otworzMenuWyspy(player, zMenu);
            } else if (slot == 53) { // Zaproś gracza (przez czat)
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

        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Członkowie Wyspy", NamedTextColor.YELLOW, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta metaTlo = tlo.getItemMeta();
        metaTlo.displayName(Component.empty());
        tlo.setItemMeta(metaTlo);
        for (int i = 0; i < 54; i++) gui.setItem(i, tlo);

        boolean viewerIsOwner = data.getOwnerUUID().equals(player.getUniqueId());

        // Slot 0 - nieklikalna karta właściciela, żeby lista jasno pokazywała kto nim jest.
        @SuppressWarnings("deprecation")
        OfflinePlayer ownerOffline = Bukkit.getOfflinePlayer(data.getOwnerUUID());
        String ownerNick = ownerOffline.getName() != null ? ownerOffline.getName() : data.getOwnerUUID().toString();
        ItemStack kartaWlasciciela = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta metaWlasciciela = (SkullMeta) kartaWlasciciela.getItemMeta();
        metaWlasciciela.setOwningPlayer(ownerOffline);
        metaWlasciciela.displayName(Component.text("[WŁAŚCICIEL] " + ownerNick, NamedTextColor.GOLD, TextDecoration.BOLD));
        metaWlasciciela.lore(List.of(Component.text("Właściciel wyspy", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        kartaWlasciciela.setItemMeta(metaWlasciciela);
        gui.setItem(0, kartaWlasciciela);

        Map<Integer, UUID> mapaSlotow = new HashMap<>();
        int slot = 1;
        for (UUID memberUUID : data.getMembers()) {
            if (slot >= 45) break; // zabezpieczenie na wypadek bardzo dużej liczby członków

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

            gui.setItem(slot, glowa);
            mapaSlotow.put(slot, memberUUID);
            slot++;
        }
        slotyCzlonkow.put(player.getUniqueId(), mapaSlotow);

        ItemStack zapros = new ItemStack(Material.EMERALD);
        ItemMeta mZapros = zapros.getItemMeta();
        mZapros.displayName(Component.text("Zaproś gracza", NamedTextColor.GREEN, TextDecoration.BOLD));
        mZapros.lore(List.of(Component.text("Wpisz nick na czacie po kliknięciu", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        zapros.setItemMeta(mZapros);
        gui.setItem(53, zapros);

        ItemStack powrot = new ItemStack(Material.ARROW);
        ItemMeta mPowrot = powrot.getItemMeta();
        mPowrot.displayName(Component.text("Powrót do Panelu Wyspy", NamedTextColor.RED, TextDecoration.BOLD));
        powrot.setItemMeta(mPowrot);
        gui.setItem(49, powrot);

        player.openInventory(gui);
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

        if (data.getBorderSize() >= MAX_BORDER_SIZE) {
            player.sendMessage(Component.text("Osiągnięto maksymalny rozmiar wyspy (" + MAX_BORDER_SIZE + " bloków)!", NamedTextColor.RED));
            return;
        }

        int cost = data.getBorderSize() * 1000;
        if (!economyManager.maWystarczajaco(player.getUniqueId(), cost)) {
            player.sendMessage(Component.text("Nie masz wystarczająco pieniędzy! Potrzebujesz " + cost + " $.", NamedTextColor.RED));
            return;
        }

        economyManager.odejmijKase(player.getUniqueId(), cost);
        data.setBorderSize(Math.min(data.getBorderSize() + 25, MAX_BORDER_SIZE));
        ustawWizualnyBorder(player, data);
        zapiszWyspy();

        player.sendMessage(Component.text("Sukces! Powiększono teren wyspy. Nowy promień: " + data.getBorderSize() + " bloków.", NamedTextColor.GREEN));
        otworzMenuUlepszen(player);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!pendingInviteChat.contains(player.getUniqueId())) return;

        event.setCancelled(true);
        pendingInviteChat.remove(player.getUniqueId());

        String targetName = event.getMessage();
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
    }

    public void potwierdzUsuniecie(Player player) {
        UUID uuid = player.getUniqueId();
        if (!pendingDeleteConfirmation.contains(uuid)) return;

        pendingDeleteConfirmation.remove(uuid);
        UUID ownerUUID = playerIslandMap.remove(uuid);
        IslandData data = islandDatabase.remove(ownerUUID);

        if (data != null) {
            Location spawnLoc = Bukkit.getWorlds().get(0).getSpawnLocation();

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

    public boolean oczekujeNaPotwierdzenie(UUID uuid) {
        return pendingDeleteConfirmation.contains(uuid);
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

    // Zapas wokół granicy budowania na wypadek, gdyby wklejony schemat startowy
    // (wyspa_startowa.schem) był fizycznie szerszy niż nominalny promień 50 - border
    // to tylko miękkie ograniczenie ruchu gracza, nie ma nic wspólnego z rozmiarem schematu.
    private static final int ZAPAS_NA_SCHEMAT = 20;

    // Ile pojedynczych chunków sprzątamy w jednym ticku - reguluje jak bardzo
    // rozkładamy usuwanie wyspy w czasie, żeby nie zamrozić serwera na jeden tick.
    private static final int CHUNKI_NA_TICK = 4;

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
        int promien = data.getBorderSize() + ZAPAS_NA_SCHEMAT;
        int minX = data.getCenterX() - promien;
        int maxX = data.getCenterX() + promien;
        int minZ = data.getCenterZ() - promien;
        int maxZ = data.getCenterZ() + promien;
        int minY = skyblockWorld.getMinHeight();
        int maxY = skyblockWorld.getMaxHeight() - 1;

        int minChunkX = minX >> 4, maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4, maxChunkZ = maxZ >> 4;

        new org.bukkit.scheduler.BukkitRunnable() {
            int cx = minChunkX;
            int cz = minChunkZ;

            @Override
            public void run() {
                int przetworzoneWTymTicku = 0;
                while (przetworzoneWTymTicku < CHUNKI_NA_TICK) {
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