package elo.mainplugins;

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
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
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
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class IslandManager implements Listener {

    private final Plugin plugin;
    private final World skyblockWorld;
    private final File schemFile;
    private final EconomyManager economyManager;

    // Plik, w którym na trwałe zapisujemy dane o wszystkich wyspach graczy
    private final File plikWysp;
    private final FileConfiguration configWysp;

    // Mapa pamiętająca, czy gracz wszedł do GUI z komendy /menu
    private final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    public static class IslandData {
        private final int id;
        private final UUID ownerUUID;
        private final int centerX;
        private final int centerZ;
        private int borderSize;
        private final Set<UUID> members = new HashSet<>();

        private boolean allowMobs = true;
        private boolean allowPvP = false;
        private boolean allowBreak = true;
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
    private int nextIslandId = 0;

    public IslandManager(Plugin plugin, EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;

        WorldCreator wc = new WorldCreator("skyblock_world");
        wc.generator(new VoidGenerator());
        this.skyblockWorld = Bukkit.createWorld(wc);

        File faweFolder = new File(plugin.getDataFolder().getParentFile(), "FastAsyncWorldEdit/schematics");
        this.schemFile = new File(faweFolder, "wyspa_startowa.schem");

        // Przygotowanie pliku wyspy.yml (tworzymy go, jeśli jeszcze nie istnieje)
        this.plikWysp = new File(plugin.getDataFolder(), "wyspy.yml");
        if (!plikWysp.exists()) {
            plikWysp.getParentFile().mkdirs();
            try { plikWysp.createNewFile(); } catch (IOException ignored) {}
        }
        this.configWysp = YamlConfiguration.loadConfiguration(plikWysp);

        // Wczytujemy z pliku wyspy zapisane podczas poprzedniego działania serwera
        wczytajWyspy();
    }

    // Wczytuje wszystkie wyspy zapisane w pliku wyspy.yml do pamięci gry (uruchamiane raz, przy starcie)
    private void wczytajWyspy() {
        nextIslandId = configWysp.getInt("nextIslandId", 0);

        ConfigurationSection sekcjaWysp = configWysp.getConfigurationSection("wyspy");
        if (sekcjaWysp == null) return;

        for (String ownerKey : sekcjaWysp.getKeys(false)) {
            UUID ownerUUID = UUID.fromString(ownerKey);
            String path = "wyspy." + ownerKey + ".";

            int id = configWysp.getInt(path + "id", 0);
            int centerX = configWysp.getInt(path + "centerX", 0);
            int centerZ = configWysp.getInt(path + "centerZ", 0);
            int borderSize = configWysp.getInt(path + "borderSize", 50);

            IslandData data = new IslandData(id, ownerUUID, centerX, centerZ, borderSize);
            data.setAllowMobs(configWysp.getBoolean(path + "allowMobs", true));
            data.setAllowPvP(configWysp.getBoolean(path + "allowPvP", false));
            data.setAllowBreak(configWysp.getBoolean(path + "allowBreak", true));
            data.setVisualBorder(configWysp.getBoolean(path + "visualBorder", true));

            for (String memberStr : configWysp.getStringList(path + "members")) {
                UUID memberUUID = UUID.fromString(memberStr);
                data.getMembers().add(memberUUID);
                playerIslandMap.put(memberUUID, ownerUUID);
            }

            islandDatabase.put(ownerUUID, data);
            playerIslandMap.put(ownerUUID, ownerUUID);
        }
    }

    // Zapisuje (lub nadpisuje) dane jednej wyspy w pliku wyspy.yml
    private void zapiszWyspe(IslandData data) {
        String path = "wyspy." + data.getOwnerUUID() + ".";
        configWysp.set(path + "id", data.getId());
        configWysp.set(path + "centerX", data.getCenterX());
        configWysp.set(path + "centerZ", data.getCenterZ());
        configWysp.set(path + "borderSize", data.getBorderSize());
        configWysp.set(path + "allowMobs", data.isAllowMobs());
        configWysp.set(path + "allowPvP", data.isAllowPvP());
        configWysp.set(path + "allowBreak", data.isAllowBreak());
        configWysp.set(path + "visualBorder", data.isVisualBorder());

        List<String> membersStr = new ArrayList<>();
        for (UUID member : data.getMembers()) membersStr.add(member.toString());
        configWysp.set(path + "members", membersStr);

        // Numer następnej wyspy też musi być zapisany, żeby po restarcie nie zaczynał się od nowa od 0
        configWysp.set("nextIslandId", nextIslandId);

        zapiszPlikWysp();
    }

    // Usuwa dane wyspy z pliku wyspy.yml (gdy gracz kasuje swoją wyspę)
    private void usunWyspeZPliku(UUID ownerUUID) {
        configWysp.set("wyspy." + ownerUUID, null);
        zapiszPlikWysp();
    }

    private void zapiszPlikWysp() {
        try {
            configWysp.save(plikWysp);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie mozna zapisac pliku wyspy.yml!");
        }
    }

    public void handleCommand(Player player, String[] args) {
        UUID uuid = player.getUniqueId();
        boolean zMenu = (args.length > 0 && args[args.length - 1].equalsIgnoreCase("zmenu"));

        if (args.length > 0 && args[0].equalsIgnoreCase("delete")) {
            if (oczekujeNaPotwierdzenie(uuid)) {
                potwierdzUsuniecie(player);
            } else {
                player.sendMessage(Component.text("Wpisz najpierw /is i kliknij kosz, aby zaznaczyć chęć usunięcia wyspy!", NamedTextColor.RED));
            }
            return;
        }

        if (!playerIslandMap.containsKey(uuid)) {
            stworzWyspe(player, zMenu);
        } else {
            otworzMenuWyspy(player, zMenu);
        }
    }

    private void stworzWyspe(Player player, boolean zMenu) {
        if (!schemFile.exists()) {
            player.sendMessage(Component.text("Błąd: Brak pliku wyspa_startowa.schem w FastAsyncWorldEdit/schematics!", NamedTextColor.RED));
            return;
        }

        player.sendMessage(Component.text("Tworzenie Twojej wyspy...", NamedTextColor.YELLOW));

        int myIslandId = nextIslandId++;
        int x = (myIslandId % 100) * 10000;
        int z = (myIslandId / 100) * 10000;
        int y = 100;

        IslandData data = new IslandData(myIslandId, player.getUniqueId(), x, z, 50);
        islandDatabase.put(player.getUniqueId(), data);
        playerIslandMap.put(player.getUniqueId(), player.getUniqueId());
        zapiszWyspe(data); // Od razu zapisujemy nową wyspę na dysk

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
            clearBorder.setSize(60000000);
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

        // 5. DODAJ GRACZA / ZARZĄDZAJ
        ItemStack dodaj = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta mDodaj = dodaj.getItemMeta();
        mDodaj.displayName(Component.text("Członkowie Wyspy", NamedTextColor.YELLOW, TextDecoration.BOLD));
        mDodaj.lore(List.of(Component.text("Dodaj znajomych do wyspy", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
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

        Inventory gui = Bukkit.createInventory(null, 27, Component.text("Ulepszenia Wyspy", NamedTextColor.GOLD, TextDecoration.BOLD));

        ItemStack tlo = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta metaTlo = tlo.getItemMeta();
        metaTlo.displayName(Component.empty());
        tlo.setItemMeta(metaTlo);
        for (int i = 0; i < 27; i++) gui.setItem(i, tlo);

        ItemStack itemUpgradeSize = new ItemStack(Material.BEACON);
        ItemMeta metaSize = itemUpgradeSize.getItemMeta();
        metaSize.displayName(Component.text("Powiększ Teren Wyspy", NamedTextColor.YELLOW, TextDecoration.BOLD));
        metaSize.lore(List.of(
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
            else if (slot == 31) { // Członkowie (dodawanie na czacie)
                player.closeInventory();
                pendingAddMember.add(player.getUniqueId());
                player.sendMessage(Component.text("Wpisz na czacie nick gracza, którego chcesz dodać do wyspy (lub wpisz 'anuluj'):", NamedTextColor.YELLOW));
            }
            else if (slot == 29) { // Border
                UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
                if (ownerUUID != null) {
                    IslandData data = islandDatabase.get(ownerUUID);
                    data.setVisualBorder(!data.isVisualBorder());
                    ustawWizualnyBorder(player, data);
                    zapiszWyspe(data); // Zapisujemy zmianę ustawienia borderu
                    otworzMenuWyspy(player, zMenu); // odśwież
                }
            }
            else if (slot == 33) { // Kosz / Usunięcie
                if (oczekujeNaPotwierdzenie(player.getUniqueId())) {
                    player.closeInventory();
                    potwierdzUsuniecie(player);
                } else {
                    pendingDeleteConfirmation.add(player.getUniqueId());
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
    }

    private void uprosGranice(Player player) {
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null) {
            player.sendMessage(Component.text("Nie masz jeszcze wyspy!", NamedTextColor.RED));
            return;
        }

        int cost = data.getBorderSize() * 1000;
        if (!economyManager.maWystarczajaco(player.getUniqueId(), cost)) {
            player.sendMessage(Component.text("Nie masz wystarczająco pieniędzy! Potrzebujesz " + cost + " $.", NamedTextColor.RED));
            return;
        }

        economyManager.odejmijKase(player.getUniqueId(), cost);
        data.setBorderSize(data.getBorderSize() + 25);
        ustawWizualnyBorder(player, data);
        zapiszWyspe(data); // Zapisujemy nowy, większy rozmiar wyspy

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
        zapiszWyspe(data); // Zapisujemy nową listę członków wyspy

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

            // Usuwamy wyspę też z pliku wyspy.yml, żeby po restarcie serwera
            // gra na pewno pamiętała, że ta wyspa już nie istnieje
            usunWyspeZPliku(ownerUUID);

            // Teleport na główny spawn (świat główny)
            player.teleport(new Location(Bukkit.getWorlds().get(0), 0, 100, 0));

            // Usunięcie borderu
            WorldBorder clearBorder = Bukkit.createWorldBorder();
            clearBorder.setSize(60000000);
            player.setWorldBorder(clearBorder);

            // Czyszczenie bloków ze świata (Zamiana na powietrze wokół środka)
            // Robimy to przez WorldEdit/FAWE - tym samym systemem, którym wklejamy wyspę.
            // Dzięki temu nie mieszamy dwóch różnych systemów edycji świata na tym samym terenie
            // (mieszanie ich powodowało, że usunięte bloki czasem "wracały").
            int promien = data.getBorderSize() + 10;
            int x = data.getCenterX();
            int y = 100; // Środek Y dla schematu
            int z = data.getCenterZ();

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(skyblockWorld))) {
                    Region region = new CuboidRegion(
                            BukkitAdapter.adapt(skyblockWorld),
                            BlockVector3.at(x - promien, y - 40, z - promien),
                            BlockVector3.at(x + promien, y + 60, z + promien)
                    );
                    editSession.setBlocks(region, BlockTypes.AIR.getDefaultState());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            player.sendMessage(Component.text("Twoja wyspa została bezpowrotnie usunięta ze świata.", NamedTextColor.RED));
        }
    }

    public boolean oczekujeNaPotwierdzenie(UUID uuid) {
        return pendingDeleteConfirmation.contains(uuid);
    }
}