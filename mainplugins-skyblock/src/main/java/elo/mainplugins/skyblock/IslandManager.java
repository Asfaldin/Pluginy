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
    }

    private final Map<UUID, IslandData> islandDatabase = new HashMap<>();
    private final Map<UUID, UUID> playerIslandMap = new HashMap<>();
    private final Set<UUID> pendingDeleteConfirmation = new HashSet<>();
    private final Set<UUID> pendingAddMember = new HashSet<>();
    // Zapamiętuje, który slot w GUI "Członkowie Wyspy" odpowiada za którego gracza
    private final Map<UUID, Map<Integer, UUID>> slotyCzlonkow = new HashMap<>();
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

            for (String memberStr : configWysp.getStringList(path + "czlonkowie")) {
                try {
                    UUID memberUUID = UUID.fromString(memberStr);
                    data.getMembers().add(memberUUID);
                    playerIslandMap.put(memberUUID, ownerUUID);
                } catch (IllegalArgumentException ignored) {}
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

            List<String> czlonkowie = new ArrayList<>();
            for (UUID member : data.getMembers()) czlonkowie.add(member.toString());
            configWysp.set(path + "czlonkowie", czlonkowie);
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
            case "delete" -> obslugaUsuwaniaKomenda(player, args);
            case "border" -> przelaczWizualnyBorder(player);
            case "guests", "build" -> przelaczBudowanieDlaGosci(player);
            case "pvp" -> przelaczPvP(player);
            case "mobs" -> przelaczPotwory(player);
            case "upgrade" -> otworzMenuUlepszen(player);
            case "members" -> otworzMenuCzlonkow(player);
            case "add" -> dodajCzlonkaKomenda(player, args);
            case "remove" -> usunCzlonkaKomenda(player, args);
            default -> {
                if (!playerIslandMap.containsKey(uuid)) {
                    stworzWyspe(player, zMenu);
                } else {
                    otworzMenuWyspy(player, zMenu);
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

    public void przelaczWizualnyBorder(Player player) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;
        data.setVisualBorder(!data.isVisualBorder());
        ustawWizualnyBorder(player, data);
        zapiszWyspy();
        player.sendMessage(Component.text("Wizualny border: " + (data.isVisualBorder() ? "włączony" : "wyłączony"), NamedTextColor.GREEN));
    }

    public void przelaczBudowanieDlaGosci(Player player) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;
        data.setAllowBreak(!data.isAllowBreak());
        zapiszWyspy();
        player.sendMessage(Component.text("Budowanie dla gości: " + (data.isAllowBreak() ? "otwarte" : "zamknięte"), NamedTextColor.GREEN));
    }

    public void przelaczPvP(Player player) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;
        data.setAllowPvP(!data.isAllowPvP());
        zapiszWyspy();
        player.sendMessage(Component.text("PvP na wyspie: " + (data.isAllowPvP() ? "włączone" : "wyłączone"), NamedTextColor.GREEN));
    }

    public void przelaczPotwory(Player player) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;
        data.setAllowMobs(!data.isAllowMobs());
        zapiszWyspy();
        player.sendMessage(Component.text("Potwory na wyspie: " + (data.isAllowMobs() ? "mogą się pojawiać" : "zablokowane"), NamedTextColor.GREEN));
    }

    private void dodajCzlonkaKomenda(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Użycie: /is add <gracz>", NamedTextColor.RED));
            return;
        }
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            player.sendMessage(Component.text("Nie znaleziono gracza o takim nicku lub jest offline.", NamedTextColor.RED));
            return;
        }

        UUID ownerUUID = data.getOwnerUUID();
        data.getMembers().add(target.getUniqueId());
        playerIslandMap.put(target.getUniqueId(), ownerUUID);
        zapiszWyspy();

        player.sendMessage(Component.text("Pomyślnie dodano gracza " + target.getName() + " do wyspy!", NamedTextColor.GREEN));
        target.sendMessage(Component.text("Zostałeś dodany do wyspy gracza " + player.getName() + "!", NamedTextColor.AQUA));
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

        Location loc = new Location(skyblockWorld, data.getCenterX() + 0.5, 101, data.getCenterZ() + 0.5);
        player.teleport(loc);
        ustawWizualnyBorder(player, data);
        player.sendMessage(Component.text("Przeteleportowano na wyspę!", NamedTextColor.AQUA));
    }

    private void ustawWizualnyBorder(Player player, IslandData data) {
        if (!data.isVisualBorder()) {
            WorldBorder clearBorder = Bukkit.createWorldBorder();
            clearBorder.setSize(ROZMIAR_BEZ_BORDERU);
            player.setWorldBorder(clearBorder);
            return;
        }

        WorldBorder border = Bukkit.createWorldBorder();
        border.setCenter(data.getCenterX() + 0.5, data.getCenterZ() + 0.5);
        border.setSize(data.getBorderSize() * 2);
        border.setWarningDistance(0);
        player.setWorldBorder(border);
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
        loreInfo.add(Component.text("Właściciel: " + ownerName, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        loreInfo.add(Component.text("Rozmiar: " + data.getBorderSize() + "x" + data.getBorderSize(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        loreInfo.add(Component.text("Członkowie: " + data.getMembers().size(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
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

        // 5. DODAJ GRACZA / ZARZĄDZAJ
        ItemStack dodaj = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta mDodaj = dodaj.getItemMeta();
        mDodaj.displayName(Component.text("Członkowie Wyspy", NamedTextColor.YELLOW, TextDecoration.BOLD));
        mDodaj.lore(List.of(
                Component.text("Aktualnie: " + data.getMembers().size() + " członków", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Kliknij, aby zarządzać", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        dodaj.setItemMeta(mDodaj);
        gui.setItem(31, dodaj);

        // 6. USUŃ WYSPĘ
        ItemStack usun = new ItemStack(Material.TNT);
        ItemMeta mUsun = usun.getItemMeta();
        mUsun.displayName(Component.text("Usuń Wyspę", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        mUsun.lore(List.of(Component.text("Ostrzeżenie: Wyspa zniknie bezpowrotnie!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        usun.setItemMeta(mUsun);
        gui.setItem(33, usun);

        // PRZYCISK POWROTU
        ItemStack wyjscie = new ItemStack(zMenu ? Material.NETHER_STAR : Material.BARRIER);
        ItemMeta mWyjscie = wyjscie.getItemMeta();
        mWyjscie.displayName(Component.text(zMenu ? "« Wróć do Menu głównego" : "Zamknij Panel", NamedTextColor.RED, TextDecoration.BOLD));
        wyjscie.setItemMeta(mWyjscie);
        gui.setItem(49, wyjscie);

        player.openInventory(gui);
    }

    public void otworzMenuUlepszen(Player player) {
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        int currentSize = data != null ? data.getBorderSize() : 50;
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

        ItemStack itemBack = new ItemStack(Material.ARROW);
        ItemMeta metaBack = itemBack.getItemMeta();
        metaBack.displayName(Component.text("Powrót do Menu", NamedTextColor.RED, TextDecoration.BOLD));
        itemBack.setItemMeta(metaBack);
        gui.setItem(15, itemBack);

        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().title().toString();
        if (!(event.getWhoClicked() instanceof Player player)) return;

        boolean zMenu = otwartoZMenu.getOrDefault(player.getUniqueId(), false);

        if (title.contains("Panel Wyspy")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 13) { player.closeInventory(); teleportDoWyspy(player); } // Teleport
            else if (slot == 15) { otworzMenuUlepszen(player); } // Ulepszenia
            else if (slot == 31) { otworzMenuCzlonkow(player); } // Członkowie - lista + zarządzanie
            else if (slot == 29) { przelaczWizualnyBorder(player); otworzMenuWyspy(player, zMenu); } // Border
            else if (slot == 20) { przelaczBudowanieDlaGosci(player); otworzMenuWyspy(player, zMenu); }
            else if (slot == 22) { przelaczPvP(player); otworzMenuWyspy(player, zMenu); }
            else if (slot == 24) { przelaczPotwory(player); otworzMenuWyspy(player, zMenu); }
            else if (slot == 33) { // Kosz / Usunięcie
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
            else if (slot == 15) { otworzMenuWyspy(player, zMenu); }
        }
        else if (title.contains("Członkowie Wyspy")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

            if (slot == 49) { // Powrót do panelu wyspy
                otworzMenuWyspy(player, zMenu);
            } else if (slot == 53) { // Dodaj gracza (ten sam mechanizm co poprzednio - przez czat)
                player.closeInventory();
                pendingAddMember.add(player.getUniqueId());
                player.sendMessage(Component.text("Wpisz na czacie nick gracza, którego chcesz dodać do wyspy (lub wpisz 'anuluj'):", NamedTextColor.YELLOW));
            } else {
                Map<Integer, UUID> mapaSlotow = slotyCzlonkow.get(player.getUniqueId());
                UUID targetUUID = mapaSlotow != null ? mapaSlotow.get(slot) : null;
                if (targetUUID != null) {
                    usunCzlonka(player, targetUUID);
                    otworzMenuCzlonkow(player); // odśwież listę
                }
            }
        }
    }

    /** Lista aktualnych członków wyspy z możliwością usunięcia + przycisk dodawania nowego. */
    public void otworzMenuCzlonkow(Player player) {
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;

        if (data == null) {
            player.sendMessage(Component.text("Nie posiadasz wyspy!", NamedTextColor.RED));
            return;
        }

        Inventory gui = Bukkit.createInventory(null, 54, Component.text("Członkowie Wyspy", NamedTextColor.YELLOW, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta metaTlo = tlo.getItemMeta();
        metaTlo.displayName(Component.empty());
        tlo.setItemMeta(metaTlo);
        for (int i = 0; i < 54; i++) gui.setItem(i, tlo);

        Map<Integer, UUID> mapaSlotow = new HashMap<>();
        int slot = 0;
        for (UUID memberUUID : data.getMembers()) {
            if (slot >= 45) break; // zabezpieczenie na wypadek bardzo dużej liczby członków

            @SuppressWarnings("deprecation")
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(memberUUID);
            String nick = offlinePlayer.getName() != null ? offlinePlayer.getName() : memberUUID.toString();

            ItemStack glowa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) glowa.getItemMeta();
            meta.setOwningPlayer(offlinePlayer);
            meta.displayName(Component.text(nick, NamedTextColor.AQUA, TextDecoration.BOLD));
            meta.lore(List.of(Component.text("Kliknij, aby usunąć z wyspy", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
            glowa.setItemMeta(meta);

            gui.setItem(slot, glowa);
            mapaSlotow.put(slot, memberUUID);
            slot++;
        }
        slotyCzlonkow.put(player.getUniqueId(), mapaSlotow);

        ItemStack dodaj = new ItemStack(Material.EMERALD);
        ItemMeta mDodaj = dodaj.getItemMeta();
        mDodaj.displayName(Component.text("Dodaj gracza", NamedTextColor.GREEN, TextDecoration.BOLD));
        mDodaj.lore(List.of(Component.text("Wpisz nick na czacie po kliknięciu", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        dodaj.setItemMeta(mDodaj);
        gui.setItem(53, dodaj);

        ItemStack powrot = new ItemStack(Material.ARROW);
        ItemMeta mPowrot = powrot.getItemMeta();
        mPowrot.displayName(Component.text("Powrót do Panelu Wyspy", NamedTextColor.RED, TextDecoration.BOLD));
        powrot.setItemMeta(mPowrot);
        gui.setItem(49, powrot);

        player.openInventory(gui);
    }

    private void usunCzlonka(Player owner, UUID targetUUID) {
        UUID ownerUUID = playerIslandMap.get(owner.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null) return;

        if (data.getMembers().remove(targetUUID)) {
            playerIslandMap.remove(targetUUID);
            zapiszWyspy();

            @SuppressWarnings("deprecation")
            String nick = Bukkit.getOfflinePlayer(targetUUID).getName();
            owner.sendMessage(Component.text("Usunięto gracza " + (nick != null ? nick : targetUUID) + " z wyspy.", NamedTextColor.YELLOW));

            Player targetOnline = Bukkit.getPlayer(targetUUID);
            if (targetOnline != null) {
                targetOnline.sendMessage(Component.text("Zostałeś usunięty z wyspy gracza " + owner.getName() + ".", NamedTextColor.RED));
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
        if (!pendingAddMember.contains(player.getUniqueId())) return;

        event.setCancelled(true);
        pendingAddMember.remove(player.getUniqueId());

        String targetName = event.getMessage();
        if (targetName.equalsIgnoreCase("anuluj")) {
            player.sendMessage(Component.text("Anulowano dodawanie gracza.", NamedTextColor.RED));
            return;
        }

        Player target = Bukkit.getPlayer(targetName);

        if (target == null || !target.isOnline()) {
            player.sendMessage(Component.text("Nie znaleziono gracza o takim nicku lub jest offline.", NamedTextColor.RED));
            return;
        }

        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        IslandData data = islandDatabase.get(ownerUUID);

        if (data == null) {
            player.sendMessage(Component.text("Nie posiadasz wyspy!", NamedTextColor.RED));
            return;
        }

        data.getMembers().add(target.getUniqueId());
        playerIslandMap.put(target.getUniqueId(), ownerUUID);
        zapiszWyspy();

        player.sendMessage(Component.text("Pomyślnie dodano gracza " + target.getName() + " do wyspy!", NamedTextColor.GREEN));
        target.sendMessage(Component.text("Zostałeś dodany do wyspy gracza " + player.getName() + "!", NamedTextColor.AQUA));
    }

    public void potwierdzUsuniecie(Player player) {
        UUID uuid = player.getUniqueId();
        if (!pendingDeleteConfirmation.contains(uuid)) return;

        pendingDeleteConfirmation.remove(uuid);
        UUID ownerUUID = playerIslandMap.remove(uuid);
        IslandData data = islandDatabase.remove(ownerUUID);

        if (data != null) {
            // Usuń z mapy wszystkich członków
            for (UUID memberUUID : data.getMembers()) {
                playerIslandMap.remove(memberUUID);
            }
            zapiszWyspy(); // wyspa usunięta z islandDatabase wcześniej - ten zapis usuwa ją też z wyspy.yml

            // Teleport na główny spawn (świat główny)
            player.teleport(new Location(Bukkit.getWorlds().get(0), 0, 100, 0));

            // Usunięcie borderu
            WorldBorder clearBorder = Bukkit.createWorldBorder();
            clearBorder.setSize(ROZMIAR_BEZ_BORDERU);
            player.setWorldBorder(clearBorder);

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
                data.getMembers().size()
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