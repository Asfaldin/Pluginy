package elo.mainplugins.skyblock;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.AsyncConfigSaver;
import elo.mainplugins.core.util.MoneyFormat;
import org.bukkit.command.CommandSender;
import elo.mainplugins.core.api.IslandService;
import elo.mainplugins.core.api.IslandSummary;
import elo.mainplugins.core.api.SpawnService;
import elo.mainplugins.core.world.VoidGenerator;
import elo.mainplugins.skyblock.config.IslandConfigLoader;
import elo.mainplugins.skyblock.config.IslandGrid;
import elo.mainplugins.skyblock.config.IslandTuning;
import elo.mainplugins.skyblock.config.SpawnerTyp;
import elo.mainplugins.skyblock.event.IslandBankDepositEvent;
import elo.mainplugins.skyblock.event.IslandCreatedEvent;
import elo.mainplugins.skyblock.event.IslandMemberJoinedEvent;
import elo.mainplugins.skyblock.event.IslandUpgradeEvent;
import elo.mainplugins.skyblock.gui.IslandGuiButton;
import elo.mainplugins.skyblock.gui.IslandGuiContent;
import elo.mainplugins.skyblock.gui.IslandGuiHolder;
import elo.mainplugins.skyblock.gui.IslandGuiLoader;
import elo.mainplugins.skyblock.gui.IslandScreen;
import elo.mainplugins.skyblock.template.IslandTemplate;
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
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class IslandManager implements Listener, IslandService {

    final Plugin plugin;
    final World skyblockWorld;
    final IslandTemplate template;
    final EconomyService economyManager;

    // Cala liczbowa/danych konfiguracja (koszty, promienie, timeouty, typy spawnerow...) i
    // caly uklad GUI (sloty/ikony/teksty) - wczytywane z wyspy-config.yml/wyspy-gui.yml,
    // podmieniane w calosci przy /@reloadwyspy (patrz przeladujKonfiguracje). NIE final -
    // to jedyny powod, dla ktorego to pole nie jest static final jak dawne stale.
    IslandTuning tuning;
    IslandGuiContent gui;

    // Części systemu wysp w osobnych klasach (ten sam pakiet, wspólny stan trzyma IslandManager).
    final IslandTexts teksty;
    final IslandStorage storage;
    final IslandMenus menus;
    final IslandChat chat;

    // Mapa pamiętająca, czy gracz wszedł do GUI z komendy /menu
    final Map<UUID, Boolean> otwartoZMenu = new HashMap<>();

    // MUSZĄ się zgadzać 1:1 z tymi samymi literałami w SpawnerManager (mainplugins-spawners) -
    // identyfikatory protokołu miedzy pluginami, nie "tresc" do edycji.
    static final String SUFIKS_ILOSC = "_ILOSC";
    static final String SUFIKS_SZYBKOSC = "_SZYBKOSC";

    // Maksymalny rozmiar WorldBordera dopuszczalny przez samego Minecrafta to
    // 5.9999968E7 (59 999 968) - wartość NIECO mniejsza niż okrągłe 60 milionów.
    // Wcześniejsze 60000000 przekraczało ten limit o 32 i wywalało IllegalArgumentException
    // przy każdym /is border i usuwaniu wyspy. Używamy tego jako "brak borderu". To twardy
    // limit silnika Minecrafta, nie wartość do strojenia - celowo NIE w wyspy-config.yml.
    private static final double ROZMIAR_BEZ_BORDERU = 59999900;

    final Map<UUID, IslandData> islandDatabase = new HashMap<>();
    final Map<UUID, UUID> playerIslandMap = new HashMap<>();
    // ConcurrentHashMap-backed (nie HashSet) - te trzy są czytane/zapisywane też
    // z chat.onChat(), który odpala się na wątku czatu, a nie głównym wątku serwera
    // (patrz AsyncPlayerChatEvent). pendingLeaveConfirmation nie potrzebuje tego,
    // bo dotyka go wyłącznie kod z głównego wątku (kliknięcia w GUI).
    final Set<UUID> pendingDeleteConfirmation = ConcurrentHashMap.newKeySet();
    final Set<UUID> pendingLeaveConfirmation = new HashSet<>();
    final Set<UUID> pendingInviteChat = ConcurrentHashMap.newKeySet();
    final Set<UUID> pendingNameChat = ConcurrentHashMap.newKeySet();
    // Zapamiętuje, który slot w GUI "Członkowie Wyspy" odpowiada za którego gracza
    final Map<UUID, Map<Integer, UUID>> slotyCzlonkow = new HashMap<>();
    // Który typ spawnera gracz aktualnie ma otwarty w podmenu "Spawner: X" (Ilość/Szybkość)
    final Map<UUID, String> otwartySpawnerTyp = new HashMap<>();
    // Historia tworzenia wysp per gracz - anty-spam cooldown na create/delete (patrz kolejnyCooldownTworzenia).
    // Liczba NIGDY się nie zeruje - to celowo licznik na całe życie konta, nie okno czasowe.
    final Map<UUID, IslandStorage.HistoriaTworzenia> historiaTworzeniaWysp = new HashMap<>();
    int nextIslandId = 0;


    public IslandManager(Plugin plugin, EconomyService economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;

        this.tuning = IslandConfigLoader.load(plugin);
        this.gui = IslandGuiLoader.load(plugin);

        WorldCreator wc = new WorldCreator(tuning.nazwaSwiata());
        wc.generator(new VoidGenerator());
        this.skyblockWorld = Bukkit.createWorld(wc);

        this.template = new IslandTemplate(plugin);

        this.teksty = new IslandTexts(plugin);
        this.storage = new IslandStorage(this);
        this.menus = new IslandMenus(this);
        this.chat = new IslandChat(this);
        plugin.getServer().getPluginManager().registerEvents(menus, plugin);
        plugin.getServer().getPluginManager().registerEvents(chat, plugin);
        storage.wczytajWyspy();
        storage.wczytajHistorieTworzenia();
    }

    /** Wywoływane przez /@reloadwyspy - podmienia liczbową konfigurację i cały układ GUI bez restartu serwera. */
    public void przeladujKonfiguracje() {
        this.tuning = IslandConfigLoader.load(plugin);
        this.gui = IslandGuiLoader.load(plugin);
    }

    /** Szablon wyspy startowej - dla /@islandtemplate. */
    public IslandTemplate getTemplate() {
        return template;
    }

    /** Używane przez IslandProtectionManager - żeby nie duplikować wczytywania configu w każdej klasie. */
    public IslandTuning getTuning() {
        return tuning;
    }

    /** Wywołaj w onDisable() modułu skyblock - zapisuje natychmiast, zatrzymuje cykl. */
    public void zamknij() {
        storage.zamknij();
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
            case "menu" -> menus.otworzMenuWyspy(player, zMenu);
            case "usun" -> obslugaUsuwaniaKomenda(player);
            case "granica", "border" -> przelaczWizualnyBorder(player);
            case "budowanie", "guests", "build" -> przelaczBudowanieDlaGosci(player);
            case "pvp" -> przelaczPvP(player);
            case "odwiedz", "visit" -> odwiedzWyspe(player, args);
            case "przekaz", "transfer" -> przekazWyspe(player, args);
            case "wypros", "expel" -> wyprosGosciaKomenda(player, args);
            case "zbanuj", "ban" -> zbanujKomenda(player, args);
            case "odbanuj", "unban" -> odbanujKomenda(player, args);
            case "ulepszenia", "upgrade" -> menus.otworzMenuUlepszen(player);
            case "czlonkowie", "members" -> menus.otworzMenuCzlonkow(player);
            case "ustawienia", "settings" -> menus.otworzMenuUstawienWyspy(player);
            case "permisje", "permissions" -> menus.otworzMenuPermisji(player);
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
    void ustawOczekiwanieNaPotwierdzenie(UUID uuid) {
        pendingDeleteConfirmation.add(uuid);
        Bukkit.getScheduler().runTaskLater(plugin, () -> pendingDeleteConfirmation.remove(uuid), tuning.timeoutPotwierdzeniaTicks());
    }

    /**
     * Ten sam komunikat dla obu wejść (GUI kosz i komenda /is usun) - obie kończą się
     * dokładnie tym samym potwierdzeniem na czacie (patrz onChat), więc gracz musi
     * wpisać "Tak zgadzam się" niezależnie od tego, którędy tu trafił.
     */
    void wyslijOstrzezenieUsuniecia(Player player) {
        msg(player, "delete.warning");
        msg(player, "delete.how-to-confirm", "phrase", plain("delete.confirm-phrase"), "seconds", String.valueOf(tuning.timeoutPotwierdzeniaTicks() / 20));
    }

    void obslugaUsuwaniaKomenda(Player player) {
        UUID uuid = player.getUniqueId();
        if (!playerIslandMap.containsKey(uuid)) {
            msg(player, "common.no-island");
            return;
        }
        if (!uuid.equals(playerIslandMap.get(uuid))) {
            msg(player, "delete.owner-only");
            return;
        }

        ustawOczekiwanieNaPotwierdzenie(uuid);
        wyslijOstrzezenieUsuniecia(player);
    }

    IslandData wlasnaWyspaLubKomunikat(Player player) {
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null) msg(player, "common.no-island");
        return data;
    }

    /** Właściciel wyspy ZAWSZE ma pełne uprawnienia zarządzania - niezależnie od (braku) wpisu w memberRoles. */
    boolean mozeZarzadzac(UUID uuid, IslandData data) {
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
            msg(player, "common.managers-only");
            return null;
        }
        return data;
    }

    public void przelaczWizualnyBorder(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setVisualBorder(!data.isVisualBorder());
        ustawWizualnyBorder(player, data);
        storage.zapiszWyspy();
        msg(player, data.isVisualBorder() ? "settings.border-on" : "settings.border-off");
    }

    public void przelaczBudowanieDlaGosci(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowBreak(!data.isAllowBreak());
        storage.zapiszWyspy();
        msg(player, data.isAllowBreak() ? "settings.build-on" : "settings.build-off");
    }

    public void przelaczPvP(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowPvP(!data.isAllowPvP());
        storage.zapiszWyspy();
        msg(player, data.isAllowPvP() ? "settings.pvp-on" : "settings.pvp-off");
    }

    public void przelaczZabijanieMobowPrzezGosci(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowGuestMobKill(!data.isAllowGuestMobKill());
        storage.zapiszWyspy();
        msg(player, data.isAllowGuestMobKill() ? "settings.guest-kill-on" : "settings.guest-kill-off");
    }

    public void przelaczZabieranieItemow(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowItemPickup(!data.isAllowItemPickup());
        storage.zapiszWyspy();
        msg(player, data.isAllowItemPickup() ? "settings.pickup-on" : "settings.pickup-off");
    }

    public void przelaczDostepDoKontenerow(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowContainerAccess(!data.isAllowContainerAccess());
        storage.zapiszWyspy();
        msg(player, data.isAllowContainerAccess() ? "settings.containers-on" : "settings.containers-off");
    }

    public void przelaczInterakcje(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowInteract(!data.isAllowInteract());
        storage.zapiszWyspy();
        msg(player, data.isAllowInteract() ? "settings.interact-on" : "settings.interact-off");
    }

    /** Zamknięcie wyspy od razu odsyła na spawn obcych graczy, którzy na niej stoją. */
    public void przelaczOdwiedziny(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setOpenForVisitors(!data.isOpenForVisitors());
        storage.zapiszWyspy();
        msg(player, data.isOpenForVisitors() ? "settings.visit-on" : "settings.visit-off");
        if (data.isOpenForVisitors()) return;
        for (Player inny : skyblockWorld.getPlayers()) {
            if (inny.hasPermission("mainplugins.skyblock.bypass")) continue;
            if (data.getOwnerUUID().equals(inny.getUniqueId()) || data.getMembers().contains(inny.getUniqueId())) continue;
            if (znajdzWyspePod(inny.getLocation()) != data) continue;
            inny.teleport(spawnLokalizacja());
            msg(inny, "visit.kicked-closed");
        }
    }

    /** Zwykły członek (nie właściciel, nie admin wyspy) - jego uprawnienia ustawia się w Permisjach. */
    boolean zwyklyCzlonek(IslandData data, UUID uuid) {
        return data.getMembers().contains(uuid) && data.getRole(uuid) != IslandRole.ADMIN;
    }

    /** Właściciel/admin wyspy albo zwykły członek z danym uprawnieniem; inaczej komunikat i null. */
    IslandData wlasnaWyspaZUprawnieniem(Player player, java.util.function.Predicate<IslandData> dlaCzlonka) {
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return null;
        if (mozeZarzadzac(player.getUniqueId(), data) || dlaCzlonka.test(data)) return data;
        msg(player, "common.managers-only");
        return null;
    }

    private void przelaczUprawnienie(Player player, java.util.function.Predicate<IslandData> get,
                                     java.util.function.BiConsumer<IslandData, Boolean> set, String klucz) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        set.accept(data, !get.test(data));
        storage.zapiszWyspy();
        msg(player, "settings." + klucz + (get.test(data) ? "-on" : "-off"));
    }

    public void przelaczCzlonkowieBudowanie(Player p) { przelaczUprawnienie(p, IslandData::isMemberBuild, IslandData::setMemberBuild, "member-build"); }
    public void przelaczCzlonkowieSkrzynie(Player p) { przelaczUprawnienie(p, IslandData::isMemberContainers, IslandData::setMemberContainers, "member-containers"); }
    public void przelaczCzlonkowieZapraszanie(Player p) { przelaczUprawnienie(p, IslandData::isMemberInvite, IslandData::setMemberInvite, "member-invite"); }
    public void przelaczCzlonkowieBank(Player p) { przelaczUprawnienie(p, IslandData::isMemberBankUpgrade, IslandData::setMemberBankUpgrade, "member-bank"); }

    public void przelaczRolnictwoGosci(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowGuestFarming(!data.isAllowGuestFarming());
        storage.zapiszWyspy();
        msg(player, data.isAllowGuestFarming() ? "settings.farming-on" : "settings.farming-off");
    }

    public void przelaczWiadraGosci(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        data.setAllowGuestBuckets(!data.isAllowGuestBuckets());
        storage.zapiszWyspy();
        msg(player, data.isAllowGuestBuckets() ? "settings.buckets-on" : "settings.buckets-off");
    }

    /** /is odwiedz <gracz> - teleport na punkt /is ustawspawn wyspy tego gracza, jeśli jest otwarta. */
    void odwiedzWyspe(Player player, String[] args) {
        if (!tuning.odwiedzanieWysp() && !player.hasPermission("mainplugins.skyblock.bypass")) {
            msg(player, "visit.disabled");
            return;
        }
        if (args.length < 2) {
            msg(player, "visit.usage");
            return;
        }
        IslandData data = wyspaGracza(args[1]);
        if (data == null) {
            msg(player, "visit.no-island", "player", args[1]);
            return;
        }
        UUID uuid = player.getUniqueId();
        boolean swoja = data.getOwnerUUID().equals(uuid) || data.getMembers().contains(uuid);
        if (!swoja && data.getBanned().contains(uuid) && !player.hasPermission("mainplugins.skyblock.bypass")) {
            msg(player, "visit.banned", "player", args[1]);
            return;
        }
        if (!swoja && !data.isOpenForVisitors() && !player.hasPermission("mainplugins.skyblock.bypass")) {
            msg(player, "visit.closed", "player", args[1]);
            return;
        }
        teleportNaSpawnWyspy(player, data);
        msg(player, "visit.done", "player", args[1]);
    }

    /** Wyspa (własna albo ta, na której jest członkiem) gracza o podanym nicku - online albo offline. */
    private IslandData wyspaGracza(String nick) {
        Player online = Bukkit.getPlayerExact(nick);
        UUID ownerUUID = null;
        if (online != null) {
            ownerUUID = playerIslandMap.get(online.getUniqueId());
        } else {
            for (Map.Entry<UUID, UUID> e : playerIslandMap.entrySet()) {
                String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
                if (nick.equalsIgnoreCase(name)) {
                    ownerUUID = e.getValue();
                    break;
                }
            }
        }
        return ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
    }

    // ---- Przekazanie wyspy ----

    /** Właściciel -> gracz, któremu chce oddać wyspę (czeka na drugie wpisanie tej samej komendy). */
    final Map<UUID, UUID> pendingTransfer = new HashMap<>();

    /** /is przekaz <gracz> - właściciel oddaje wyspę członkowi; sam zostaje na niej adminem. Wymaga wpisania dwa razy. */
    void przekazWyspe(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "transfer.usage");
            return;
        }
        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;
        UUID uuid = player.getUniqueId();
        if (!data.getOwnerUUID().equals(uuid)) {
            msg(player, "transfer.owner-only");
            return;
        }
        UUID target = null;
        for (UUID member : data.getMembers()) {
            if (args[1].equalsIgnoreCase(Bukkit.getOfflinePlayer(member).getName())) {
                target = member;
                break;
            }
        }
        if (target == null) {
            msg(player, "common.not-member");
            return;
        }
        if (!target.equals(pendingTransfer.get(uuid))) {
            pendingTransfer.put(uuid, target);
            UUID oczekujacy = target;
            Bukkit.getScheduler().runTaskLater(plugin, () -> pendingTransfer.remove(uuid, oczekujacy), tuning.timeoutPotwierdzeniaTicks());
            msg(player, "transfer.confirm", "player", args[1], "seconds", String.valueOf(tuning.timeoutPotwierdzeniaTicks() / 20));
            return;
        }
        pendingTransfer.remove(uuid);

        islandDatabase.remove(uuid);
        data.getMembers().remove(target);
        data.getMemberRoles().remove(target);
        data.getMembers().add(uuid);
        data.setRole(uuid, IslandRole.ADMIN);
        data.setOwnerUUID(target);
        islandDatabase.put(target, data);
        playerIslandMap.put(target, target);
        for (UUID member : data.getMembers()) playerIslandMap.put(member, target);
        storage.zapiszWyspy();

        msg(player, "transfer.done", "player", args[1]);
        Player nowy = Bukkit.getPlayer(target);
        if (nowy != null) msg(nowy, "transfer.received", "player", player.getName());
    }

    // ---- Goście: wypraszanie i bany ----

    boolean jestSwoj(IslandData data, UUID uuid) {
        return data.getOwnerUUID().equals(uuid) || data.getMembers().contains(uuid);
    }

    /** /is wypros <gracz> - gość stojący na wyspie wraca na spawn. */
    void wyprosGosciaKomenda(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "guest.expel-usage");
            return;
        }
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        Player gosc = Bukkit.getPlayerExact(args[1]);
        if (gosc == null || jestSwoj(data, gosc.getUniqueId()) || znajdzWyspePod(gosc.getLocation()) != data) {
            msg(player, "guest.not-here", "player", args[1]);
            return;
        }
        gosc.teleport(spawnLokalizacja());
        msg(gosc, "guest.expelled-notice");
        msg(player, "guest.expelled", "player", gosc.getName());
    }

    /** /is zbanuj <gracz> - gracz nie wejdzie na wyspę (pieszo, teleportem ani przez /is odwiedz). */
    void zbanujKomenda(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "guest.ban-usage");
            return;
        }
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        org.bukkit.OfflinePlayer cel = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (cel == null) {
            msg(player, "common.player-not-found");
            return;
        }
        if (jestSwoj(data, cel.getUniqueId())) {
            msg(player, "guest.ban-member");
            return;
        }
        data.getBanned().add(cel.getUniqueId());
        storage.zapiszWyspy();
        msg(player, "guest.banned", "player", args[1]);
        Player online = cel.getPlayer();
        if (online != null && znajdzWyspePod(online.getLocation()) == data) {
            online.teleport(spawnLokalizacja());
            msg(online, "guest.banned-notice");
        }
    }

    void odbanujKomenda(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "guest.unban-usage");
            return;
        }
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;
        org.bukkit.OfflinePlayer cel = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (cel == null || !data.getBanned().remove(cel.getUniqueId())) {
            msg(player, "guest.not-banned", "player", args[1]);
            return;
        }
        storage.zapiszWyspy();
        msg(player, "guest.unbanned", "player", args[1]);
    }

    /** Czy gracz ma bana na wyspie w tym miejscu (admin z bypass nigdy). */
    boolean zbanowanyTutaj(Player player, Location loc) {
        if (player.hasPermission("mainplugins.skyblock.bypass")) return false;
        IslandData data = znajdzWyspePod(loc);
        return data != null && data.getBanned().contains(player.getUniqueId());
    }

    // ---- Napis przy wejściu na wyspę ----

    private final Map<UUID, Integer> ostatniaWyspa = new HashMap<>();

    /** Pokazuje „Wyspa gracza X” na ekranie, gdy gracz wchodzi na inną wyspę niż przed chwilą. */
    void sprawdzWejscie(Player player, Location loc) {
        IslandData data = znajdzWyspePod(loc);
        int id = data == null ? -1 : data.getId();
        Integer poprzednia = ostatniaWyspa.put(player.getUniqueId(), id);
        if (data == null || !tuning.napisPrzyWejsciu() || (poprzednia != null && poprzednia == id)) return;
        String wlasciciel = Bukkit.getOfflinePlayer(data.getOwnerUUID()).getName();
        String nazwa = data.getCustomName() != null ? data.getCustomName() : PlainTextComponentSerializer.plainText().serialize(CoreAPI.getLangService().msg(plugin, "enter.default-name", IslandTexts.pary("owner", wlasciciel != null ? wlasciciel : "?")));
        player.showTitle(net.kyori.adventure.title.Title.title(
                CoreAPI.getLangService().msg(plugin, "enter.title", IslandTexts.pary("name", nazwa)),
                CoreAPI.getLangService().msg(plugin, "enter.subtitle", IslandTexts.pary("owner", wlasciciel != null ? wlasciciel : "?"))));
    }

    // ---- Komendy admina: /@is ----

    /** /@is tp|usun|rozmiar|bank <gracz> ... - działa też z konsoli (poza tp). */
    public void komendaAdmina(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, "admin.usage");
            return;
        }
        IslandData data = wyspaGracza(args[1]);
        if (data == null) {
            msg(sender, "visit.no-island", "player", args[1]);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "tp" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "common.players-only");
                    return;
                }
                teleportNaSpawnWyspy(p, data);
                msg(p, "admin.teleported", "player", args[1]);
            }
            case "usun", "delete" -> {
                Player wlasciciel = Bukkit.getPlayer(data.getOwnerUUID());
                usunWyspeCalkowicie(data);
                if (wlasciciel != null) msg(wlasciciel, "admin.deleted-notice");
                msg(sender, "admin.deleted", "player", args[1]);
            }
            case "rozmiar", "size" -> {
                if (args.length < 3) {
                    msg(sender, "admin.usage");
                    return;
                }
                int rozmiar;
                try {
                    rozmiar = Math.max(1, Integer.parseInt(args[2]));
                } catch (NumberFormatException e) {
                    msg(sender, "bank.invalid-amount");
                    return;
                }
                data.setBorderSize(rozmiar);
                storage.zapiszWyspy();
                for (Player p : skyblockWorld.getPlayers()) {
                    if (znajdzWyspePod(p.getLocation()) == data) aplikujBorderDlaLokalizacji(p, p.getLocation());
                }
                msg(sender, "admin.size-set", "player", args[1], "size", String.valueOf(rozmiar));
            }
            case "bank" -> {
                if (args.length >= 4) {
                    double kwota;
                    try {
                        kwota = Double.parseDouble(args[3].replace(",", "."));
                    } catch (NumberFormatException e) {
                        msg(sender, "bank.invalid-amount");
                        return;
                    }
                    if (args[2].equalsIgnoreCase("ustaw") || args[2].equalsIgnoreCase("set")) data.setBankBalance(Math.max(0, kwota));
                    else if (args[2].equalsIgnoreCase("dodaj") || args[2].equalsIgnoreCase("add")) data.setBankBalance(Math.max(0, data.getBankBalance() + kwota));
                    else {
                        msg(sender, "admin.usage");
                        return;
                    }
                    storage.zapiszWyspy();
                }
                msg(sender, "admin.bank", "player", args[1], "balance", IslandTexts.kasa(data.getBankBalance()));
            }
            default -> msg(sender, "admin.usage");
        }
    }

    // ---- Zaproszenia na wyspę (zastępują dawne natychmiastowe dodawanie bez zgody celu) ----

    final Map<UUID, PendingInvite> pendingInvites = new HashMap<>();

    record PendingInvite(UUID ownerUUID, UUID inviterUUID) {}

    /**
     * Rdzeń wysyłki zaproszenia - współdzielony przez komendę /is zapros i czatowy
     * przepływ z GUI "Członkowie Wyspy" (przycisk "Zaproś gracza"). Sprawdzenie
     * playerIslandMap.containsKey tutaj to PIERWSZA z dwóch blokad (druga jest
     * w zaakceptujZaproszenie) - bez tego dałoby się zaprosić kogoś, kto już ma
     * własną wyspę albo jest członkiem innej.
     */
    void wykonajZaproszenie(Player inviter, Player target) {
        UUID targetUUID = target.getUniqueId();
        if (target.equals(inviter)) {
            msg(inviter, "invite.self");
            return;
        }
        if (playerIslandMap.containsKey(targetUUID)) {
            msg(inviter, "invite.has-island");
            return;
        }
        if (pendingInvites.containsKey(targetUUID)) {
            msg(inviter, "invite.already-pending");
            return;
        }

        UUID ownerUUID = playerIslandMap.get(inviter.getUniqueId());
        IslandData wyspa = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (wyspa != null && wyspaPelna(wyspa)) {
            msg(inviter, "invite.full", "max", String.valueOf(tuning.limitCzlonkow()));
            return;
        }
        pendingInvites.put(targetUUID, new PendingInvite(ownerUUID, inviter.getUniqueId()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pendingInvites.remove(targetUUID) != null) {
                Player t = Bukkit.getPlayer(targetUUID);
                if (t != null) msg(t, "invite.expired");
            }
        }, tuning.timeoutZaproszeniaTicks());

        msg(inviter, "invite.sent", "player", target.getName());
        msg(target, "invite.received", "player", inviter.getName(), "seconds", String.valueOf(tuning.timeoutZaproszeniaTicks() / 20));
    }

    void zaprosGracza(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "invite.usage");
            return;
        }
        IslandData data = wlasnaWyspaZUprawnieniem(player, IslandData::isMemberInvite);
        if (data == null) return;

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null || !target.isOnline()) {
            msg(player, "common.player-not-found");
            return;
        }

        wykonajZaproszenie(player, target);
    }

    /** Limit liczy właściciela + członków; 0 w configu = bez limitu. */
    boolean wyspaPelna(IslandData data) {
        int limit = tuning.limitCzlonkow();
        return limit > 0 && 1 + data.getMembers().size() >= limit;
    }

    public void zaakceptujZaproszenie(Player player) {
        UUID uuid = player.getUniqueId();
        PendingInvite invite = pendingInvites.remove(uuid);
        if (invite == null) {
            msg(player, "invite.none");
            return;
        }
        // Druga blokada (patrz komentarz w wykonajZaproszenie) - stan mógł się zmienić
        // w czasie, gdy zaproszenie czekało (np. gracz założył własną wyspę w międzyczasie).
        if (playerIslandMap.containsKey(uuid)) {
            msg(player, "invite.outdated");
            return;
        }
        IslandData data = islandDatabase.get(invite.ownerUUID());
        if (data == null) {
            msg(player, "invite.island-gone");
            return;
        }
        if (wyspaPelna(data)) {
            msg(player, "invite.full", "max", String.valueOf(tuning.limitCzlonkow()));
            return;
        }

        data.getMembers().add(uuid);
        data.setRole(uuid, IslandRole.CZLONEK);
        playerIslandMap.put(uuid, invite.ownerUUID());
        storage.zapiszWyspy();
        Bukkit.getPluginManager().callEvent(new IslandMemberJoinedEvent(player, data));

        msg(player, "invite.joined");
        Player inviterOnline = Bukkit.getPlayer(invite.inviterUUID());
        if (inviterOnline != null) {
            msg(inviterOnline, "invite.accepted-notice", "player", player.getName());
        }
    }

    public void odrzucZaproszenie(Player player) {
        PendingInvite invite = pendingInvites.remove(player.getUniqueId());
        if (invite == null) {
            msg(player, "invite.none");
            return;
        }
        msg(player, "invite.declined");
        Player inviterOnline = Bukkit.getPlayer(invite.inviterUUID());
        if (inviterOnline != null) {
            msg(inviterOnline, "invite.declined-notice", "player", player.getName());
        }
    }

    /** Samodzielne opuszczenie wyspy przez nie-właściciela - "hermetyczność" (patrz /is opusc, przycisk w panelu). */
    public void opuscWyspe(Player player) {
        UUID uuid = player.getUniqueId();
        UUID ownerUUID = playerIslandMap.get(uuid);
        if (ownerUUID == null) {
            msg(player, "leave.not-member");
            return;
        }
        if (ownerUUID.equals(uuid)) {
            msg(player, "leave.owner-hint");
            return;
        }

        IslandData data = islandDatabase.get(ownerUUID);
        if (data == null) return;

        data.getMembers().remove(uuid);
        data.getMemberRoles().remove(uuid);
        playerIslandMap.remove(uuid);
        storage.zapiszWyspy();

        msg(player, "leave.done");
        Player ownerOnline = Bukkit.getPlayer(ownerUUID);
        if (ownerOnline != null) {
            msg(ownerOnline, "leave.notice", "player", player.getName());
        }
    }

    /** /is awansuj|degraduj <gracz> - wyłącznie właściciel, egzekwowane wewnątrz zmienRoleCzlonka. */
    void zmienRoleKomenda(Player player, String[] args, IslandRole nowaRola) {
        if (args.length < 2) {
            msg(player, "role.usage", "command", nowaRola == IslandRole.ADMIN ? "awansuj" : "degraduj");
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
            msg(player, "common.not-member");
            return;
        }

        zmienRoleCzlonka(player, targetUUID, nowaRola);
    }

    /** Zmiana rangi członka - wyłącznie właściciel (nawet admin nie może zarządzać rangami innych). */
    void zmienRoleCzlonka(Player owner, UUID targetUUID, IslandRole nowaRola) {
        UUID ownerUUID = playerIslandMap.get(owner.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null || !data.getOwnerUUID().equals(owner.getUniqueId())) {
            msg(owner, "role.owner-only");
            return;
        }
        if (!data.getMembers().contains(targetUUID)) return;

        data.setRole(targetUUID, nowaRola);
        storage.zapiszWyspy();

        String nazwaRoli = plain(nowaRola == IslandRole.ADMIN ? "role.admin" : "role.member");
        msg(owner, "role.changed", "role", nazwaRoli);
        Player targetOnline = Bukkit.getPlayer(targetUUID);
        if (targetOnline != null) {
            msg(targetOnline, "role.changed-notice", "role", nazwaRoli);
        }
    }

    void usunCzlonkaKomenda(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "kick.usage");
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
            msg(player, "common.not-member");
            return;
        }

        usunCzlonka(player, targetUUID);
    }

    /** Wzory z configu, dla których admin zapisał już pliki w grze (/@islandtemplate save <id>). */
    List<IslandTuning.WzorWyspy> dostepneWzory() {
        List<IslandTuning.WzorWyspy> out = new ArrayList<>();
        for (IslandTuning.WzorWyspy w : tuning.wzoryWysp()) if (template.exists(w.id())) out.add(w);
        return out;
    }

    /** Czas do końca kary za zakładanie od nowa (0 = można zakładać). */
    private long pozostalyCooldown(Player player) {
        IslandStorage.HistoriaTworzenia historia = historiaTworzeniaWysp.computeIfAbsent(player.getUniqueId(), k -> new IslandStorage.HistoriaTworzenia());
        long cooldown = tuning.cooldownDlaProby(historia.ilosc + 1);
        long odMillis = System.currentTimeMillis() - historia.ostatnieMillis;
        return cooldown > 0 && odMillis < cooldown ? cooldown - odMillis : 0;
    }

    /** Zakładanie wyspy: przy kilku wzorach najpierw okno wyboru, przy jednym od razu. */
    void stworzWyspe(Player player, boolean zMenu) {
        List<IslandTuning.WzorWyspy> wzory = dostepneWzory();
        if (wzory.isEmpty()) {
            msg(player, "template.missing");
            plugin.getLogger().warning(player.getName() + " tried to create an island, but there is no island template yet - save one with /@islandtemplate.");
            return;
        }
        long czekaj = pozostalyCooldown(player);
        if (czekaj > 0) {
            msg(player, "create.cooldown", "time", teksty.formatujCzasOczekiwania(czekaj));
            return;
        }
        if (wzory.size() == 1) utworzWyspe(player, zMenu, wzory.get(0).id());
        else menus.otworzWyborWzoru(player, zMenu, wzory);
    }

    void utworzWyspe(Player player, boolean zMenu, String wzor) {
        if (playerIslandMap.containsKey(player.getUniqueId())) return; // np. drugi klik w oknie wyboru
        if (!template.exists(wzor)) {
            msg(player, "template.missing");
            return;
        }
        long czekaj = pozostalyCooldown(player);
        if (czekaj > 0) {
            msg(player, "create.cooldown", "time", teksty.formatujCzasOczekiwania(czekaj));
            return;
        }
        IslandStorage.HistoriaTworzenia historia = historiaTworzeniaWysp.get(player.getUniqueId());
        historia.ilosc++;
        historia.ostatnieMillis = System.currentTimeMillis();

        msg(player, "create.creating");

        // Kolejne pole spirali wokół pustego środka świata; pole zajęte przez istniejącą wyspę
        // (np. po zmianie odstępu w configu) jest pomijane.
        int myIslandId, x, z;
        do {
            myIslandId = nextIslandId++;
            int[] pole = IslandGrid.pole(myIslandId);
            x = pole[0] * tuning.odstepSiatkiWysp();
            z = pole[1] * tuning.odstepSiatkiWysp();
        } while (poleZajete(x, z));
        int y = tuning.wysokoscWyspy();

        IslandData data = new IslandData(myIslandId, player.getUniqueId(), x, z, tuning.domyslnyRozmiarWyspy());
        IslandTuning.UstawieniaNowejWyspy start = tuning.nowaWyspa();
        data.setAllowPvP(start.pvp());
        data.setAllowBreak(start.budowanieGosci());
        data.setVisualBorder(start.wizualnyBorder());
        data.setAllowGuestMobKill(start.zabijanieMobowGosci());
        data.setAllowItemPickup(start.zabieranieItemowGosci());
        data.setAllowContainerAccess(start.skrzynieGosci());
        data.setAllowInteract(start.interakcjeGosci());
        data.setOpenForVisitors(start.otwartaDlaOdwiedzajacych());
        data.setAllowGuestFarming(start.rolnictwoGosci());
        data.setAllowGuestBuckets(start.wiadraGosci());
        data.setMemberBuild(start.czlonkowieBudowanie());
        data.setMemberContainers(start.czlonkowieSkrzynie());
        data.setMemberInvite(start.czlonkowieZapraszanie());
        data.setMemberBankUpgrade(start.czlonkowieBankIUlepszenia());
        islandDatabase.put(player.getUniqueId(), data);
        playerIslandMap.put(player.getUniqueId(), player.getUniqueId());
        storage.zapiszWyspy();

        // Wbudowane struktury Minecrafta wklejamy na wątku głównym (API serwera nie jest
        // bezpieczne wątkowo) - wyspa startowa jest mała, więc to chwila.
        try {
            List<org.bukkit.inventory.ItemStack> skrzynia = List.of();
            for (IslandTuning.WzorWyspy w : tuning.wzoryWysp()) if (w.id().equals(wzor)) skrzynia = w.skrzynia();
            template.paste(wzor, skyblockWorld, x, y, z, skrzynia);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not paste the island template.", e);
            msg(player, "create.error");
            return;
        }
        teleportDoWyspy(player);
        msg(player, "create.done");
        if (!tuning.nagrodyNaStart().isEmpty()) CoreAPI.getRewardService().give(player, tuning.nagrodyNaStart());
        Bukkit.getPluginManager().callEvent(new IslandCreatedEvent(player, data));
        // Most do mainplugins-announcer (events.island-created) - broadcast serwerowy
        // sterowany z ogloszenia.yml; bez announcera event przelatuje bez efektu.
        Bukkit.getPluginManager().callEvent(new elo.mainplugins.core.api.ServerAnnounceEvent(
                "island-created", player, java.util.Map.of()));
        menus.otworzMenuWyspy(player, zMenu);
    }

    /** Czy nowa wyspa w (x, z) weszłaby na teren już istniejącej (liczony z największym możliwym rozmiarem). */
    private boolean poleZajete(int x, int z) {
        int zasieg = 2 * tuning.borderMaxRozmiar();
        for (IslandData inna : islandDatabase.values()) {
            if (Math.abs(inna.getCenterX() - x) < zasieg && Math.abs(inna.getCenterZ() - z) < zasieg) return true;
        }
        return false;
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
            msg(player, "common.island-missing");
            return;
        }

        // Domyślnie środek wyspy (ten sam punkt, w który wklejany jest schemat startowy)
        // - jeśli gracz ustawił własny punkt przez /is ustawdom, używamy tego zamiast.
        Location loc = data.hasCustomHome()
                ? new Location(skyblockWorld, data.getHomeX(), data.getHomeY(), data.getHomeZ(), data.getHomeYaw(), data.getHomePitch())
                : new Location(skyblockWorld, data.getCenterX() + 0.5, tuning.wysokoscWyspy() + 1, data.getCenterZ() + 0.5);
        IslandTeleport.zabezpieczPunktSpawnu(loc, tuning.maxGlebokoscSzukaniaWDol(), tuning.promienSzukaniaObok());
        player.teleport(loc);
        ustawWizualnyBorder(player, data);
        msg(player, "common.teleported");
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
            msg(player, "common.island-missing");
            return;
        }

        teleportNaSpawnWyspy(player, data);
        msg(player, "common.teleported");
    }

    /** Punkt /is ustawspawn wyspy (albo jej środek), zabezpieczony przed pustką pod stopami. */
    Location lokalizacjaSpawnuWyspy(IslandData data) {
        Location loc = data.hasCustomSpawn()
                ? new Location(skyblockWorld, data.getSpawnX(), data.getSpawnY(), data.getSpawnZ(), data.getSpawnYaw(), data.getSpawnPitch())
                : new Location(skyblockWorld, data.getCenterX() + 0.5, tuning.wysokoscWyspy() + 1, data.getCenterZ() + 0.5);
        IslandTeleport.zabezpieczPunktSpawnu(loc, tuning.maxGlebokoscSzukaniaWDol(), tuning.promienSzukaniaObok());
        return loc;
    }

    /** Wyspa gracza (własna albo ta, na której jest członkiem) albo null. */
    IslandData wyspaGraczaPoUuid(UUID uuid) {
        UUID ownerUUID = playerIslandMap.get(uuid);
        return ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
    }

    /** Teleport na punkt /is ustawspawn danej wyspy (albo jej środek) - własnej albo odwiedzanej. */
    void teleportNaSpawnWyspy(Player player, IslandData data) {
        player.teleport(lokalizacjaSpawnuWyspy(data));
        ustawWizualnyBorder(player, data);
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
    void ustawDomek(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        Location loc = player.getLocation();
        if (loc.getWorld() == null || !loc.getWorld().equals(skyblockWorld)
                || Math.abs(loc.getBlockX() - data.getCenterX()) > data.getBorderSize()
                || Math.abs(loc.getBlockZ() - data.getCenterZ()) > data.getBorderSize()) {
            msg(player, "home.not-on-island");
            return;
        }

        data.setHome(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        storage.zapiszWyspy();
        msg(player, "home.home-set");
    }

    /**
     * /is ustawspawn - to samo co ustawDomek wyżej, ale dla DRUGIEGO, niezależnego
     * punktu - celu gołego /is (patrz teleportDoSpawnuWyspy). Te same ograniczenia:
     * tylko właściciel/admin, tylko stojąc na własnej wyspie.
     */
    void ustawSpawnWyspy(Player player) {
        IslandData data = wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        Location loc = player.getLocation();
        if (loc.getWorld() == null || !loc.getWorld().equals(skyblockWorld)
                || Math.abs(loc.getBlockX() - data.getCenterX()) > data.getBorderSize()
                || Math.abs(loc.getBlockZ() - data.getCenterZ()) > data.getBorderSize()) {
            msg(player, "home.not-on-island");
            return;
        }

        data.setSpawn(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        storage.zapiszWyspy();
        msg(player, "home.spawn-set");
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
    void wplacDoBankuKomenda(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "bank.deposit-usage");
            return;
        }

        double kwota = sparsujKwote(player, args[1]);
        if (Double.isNaN(kwota)) return;

        IslandData data = wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        if (!economyManager.maWystarczajaco(player.getUniqueId(), kwota)) {
            msg(player, "bank.not-enough-money");
            return;
        }

        economyManager.odejmijKase(player.getUniqueId(), kwota);
        data.dodajDoBanku(kwota);
        storage.zapiszWyspy();
        Bukkit.getPluginManager().callEvent(new IslandBankDepositEvent(player, data, kwota));

        msg(player, "bank.deposited", "amount", IslandTexts.kasa(kwota), "balance", IslandTexts.kasa(data.getBankBalance()));

        if (!data.getOwnerUUID().equals(player.getUniqueId())) {
            Player ownerOnline = Bukkit.getPlayer(data.getOwnerUUID());
            if (ownerOnline != null) {
                msg(ownerOnline, "bank.deposit-notice", "player", player.getName(), "amount", IslandTexts.kasa(kwota));
            }
        }
    }

    /** /is wyplac <kwota> - wyłącznie właściciel/admin WŁASNEJ wyspy (patrz ustalenie: wypłaca tylko zarządca). */
    void wyplacZBankuKomenda(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "bank.withdraw-usage");
            return;
        }

        double kwota = sparsujKwote(player, args[1]);
        if (Double.isNaN(kwota)) return;

        IslandData data = wlasnaWyspaZUprawnieniem(player, IslandData::isMemberBankUpgrade);
        if (data == null) return;

        if (!data.odejmijZBanku(kwota)) {
            msg(player, "bank.not-enough-in-bank", "balance", IslandTexts.kasa(data.getBankBalance()));
            return;
        }

        economyManager.dodajKase(player.getUniqueId(), kwota);
        storage.zapiszWyspy();
        msg(player, "bank.withdrew", "amount", IslandTexts.kasa(kwota), "balance", IslandTexts.kasa(data.getBankBalance()));
    }

    double sparsujKwote(Player player, String tekst) {
        double kwota;
        try {
            kwota = Double.parseDouble(tekst);
        } catch (NumberFormatException e) {
            msg(player, "bank.invalid-amount");
            return Double.NaN;
        }
        if (kwota <= 0 || !Double.isFinite(kwota)) {
            msg(player, "bank.amount-positive");
            return Double.NaN;
        }
        return kwota;
    }

    /** Wiadomość z lang (klucz + pary placeholderów: "player", nick, ...). */
    void msg(CommandSender to, String key, String... kv) {
        CoreAPI.getLangService().send(to, plugin, key, IslandTexts.pary(kv));
    }

    /** Tekst z lang do nazwy/opisu przedmiotu albo tytułu GUI - bez kursywy, którą gra dokłada do nazw i opisów. */
    Component txt(String key, String... kv) {
        return CoreAPI.getLangService().msg(plugin, key, IslandTexts.pary(kv)).decoration(TextDecoration.ITALIC, false);
    }

    /** Sam tekst z lang, bez kolorów - np. słowo "anuluj" do porównania z czatem albo nazwa roli. */
    String plain(String key) {
        return PlainTextComponentSerializer.plainText().serialize(CoreAPI.getLangService().msg(plugin, key));
    }

    /** Plugin wysp - do tekstów z lang w obsłudze komend. */
    Plugin plugin() {
        return plugin;
    }

    /** Komunikat z lang na czacie - dla IslandProtectionManager. */
    void komunikat(Player player, String key) {
        msg(player, key);
    }

    /** Komunikat z lang nad paskiem przedmiotów - dla IslandProtectionManager. */
    void pasek(Player player, String key) {
        player.sendActionBar(CoreAPI.getLangService().msg(plugin, key));
    }

    void ustawWizualnyBorder(Player player, IslandData data) {
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

    void ulepszSpawnerStatystyke(Player player, String typId, String sufiks) {
        IslandData data = wlasnaWyspaZUprawnieniem(player, IslandData::isMemberBankUpgrade);
        if (data == null) return;

        String klucz = typId + sufiks;
        int level = data.getSpawnerLevel(klucz);
        if (level >= tuning.spawnerMaxPoziom()) {
            msg(player, "upgrade.spawner-max");
            return;
        }

        boolean ilosc = sufiks.equals(SUFIKS_ILOSC);
        int cost = tuning.kosztUlepszeniaSpawnera(typId, ilosc, level);
        if (!data.odejmijZBanku(cost)) {
            msg(player, "bank.upgrade-no-money", "cost", IslandTexts.kasa(cost), "balance", IslandTexts.kasa(data.getBankBalance()));
            return;
        }

        data.setSpawnerLevel(klucz, level + 1);
        storage.zapiszWyspy();

        msg(player, "upgrade.spawner-done", "level", String.valueOf(level + 1));
        menus.otworzMenuUlepszenSpawnera(player, typId);
    }

    /** Jedyne miejsce egzekwujące kto kogo może wyrzucić - używane identycznie przez komendę i GUI. */
    void usunCzlonka(Player actor, UUID targetUUID) {
        UUID ownerUUID = playerIslandMap.get(actor.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null) return;

        if (!mozeZarzadzac(actor.getUniqueId(), data)) {
            msg(actor, "kick.managers-only");
            return;
        }
        boolean actorIsOwner = data.getOwnerUUID().equals(actor.getUniqueId());
        if (!actorIsOwner && data.getRole(targetUUID) == IslandRole.ADMIN) {
            msg(actor, "kick.admin-protected");
            return;
        }

        if (data.getMembers().remove(targetUUID)) {
            data.getMemberRoles().remove(targetUUID);
            storage.zapiszWyspy();

            @SuppressWarnings("deprecation")
            String nick = Bukkit.getOfflinePlayer(targetUUID).getName();
            msg(actor, "kick.done", "player", nick != null ? nick : targetUUID.toString());

            Player targetOnline = Bukkit.getPlayer(targetUUID);
            if (targetOnline != null) {
                msg(targetOnline, "kick.notice", "player", actor.getName());
            }
        }
    }

    void uprosGranice(Player player) {
        UUID ownerUUID = playerIslandMap.get(player.getUniqueId());
        IslandData data = ownerUUID != null ? islandDatabase.get(ownerUUID) : null;
        if (data == null) {
            msg(player, "common.no-island-yet");
            return;
        }

        if (!mozeZarzadzac(player.getUniqueId(), data) && !data.isMemberBankUpgrade()) {
            msg(player, "common.managers-only");
            return;
        }
        if (data.getBorderSize() >= tuning.borderMaxRozmiar()) {
            msg(player, "upgrade.max-size", "max", String.valueOf(tuning.borderMaxRozmiar()));
            return;
        }

        int cost = tuning.kosztPowiekszenia(data.getBorderSize());
        if (!data.odejmijZBanku(cost)) {
            msg(player, "bank.upgrade-no-money", "cost", IslandTexts.kasa(cost), "balance", IslandTexts.kasa(data.getBankBalance()));
            return;
        }

        data.setBorderSize(Math.min(data.getBorderSize() + tuning.borderPrzyrostNaUlepszenie(), tuning.borderMaxRozmiar()));
        ustawWizualnyBorder(player, data);
        storage.zapiszWyspy();
        Bukkit.getPluginManager().callEvent(new IslandUpgradeEvent(player, data));

        msg(player, "upgrade.border-done", "size", String.valueOf(data.getBorderSize()));
        menus.otworzMenuUlepszen(player);
    }

    /**
     * Prawdziwy, skonfigurowany przez admina spawn serwera (/@setspawn) - nie surowy
     * spawn świata Bukkit, który w świecie skyblockowym może być gdziekolwiek. Opcjonalne
     * (mainplugins-spawn może nie być wgrany), więc z fallbackiem na wypadek jego braku.
     */
    Location spawnLokalizacja() {
        SpawnService spawnService = CoreAPI.getSpawnService();
        return spawnService != null ? spawnService.getSpawn() : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public void potwierdzUsuniecie(Player player) {
        UUID uuid = player.getUniqueId();
        if (!pendingDeleteConfirmation.contains(uuid)) return;

        pendingDeleteConfirmation.remove(uuid);
        IslandData data = islandDatabase.get(playerIslandMap.get(uuid));
        if (data != null) {
            usunWyspeCalkowicie(data);
            msg(player, "delete.done");
        }
    }

    /**
     * Usuwa wyspę z bazy i ze świata: członkowie tracą przynależność, a wszyscy, którzy stoją
     * na jej terenie (też goście), wracają na spawn, zanim teren zostanie wyczyszczony.
     * Border odświeża BorderManager.onWorldChange (spawn jest w innym świecie).
     */
    void usunWyspeCalkowicie(IslandData data) {
        islandDatabase.remove(data.getOwnerUUID());
        playerIslandMap.remove(data.getOwnerUUID());
        Location spawnLoc = spawnLokalizacja();
        for (UUID memberUUID : data.getMembers()) {
            playerIslandMap.remove(memberUUID);
            Player memberOnline = Bukkit.getPlayer(memberUUID);
            if (memberOnline != null) msg(memberOnline, "delete.member-notice");
        }
        for (Player p : skyblockWorld.getPlayers()) {
            int r = data.getBorderSize() + tuning.zapasNaSchemat();
            if (Math.abs(p.getLocation().getBlockX() - data.getCenterX()) <= r && Math.abs(p.getLocation().getBlockZ() - data.getCenterZ()) <= r) {
                p.teleport(spawnLoc);
            }
        }
        storage.zapiszWyspy();
        wyczyscTerenWyspy(data);
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
        storage.zapiszWyspy();
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

    IslandSummary toSummary(IslandData data) {
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
    void wyczyscTerenWyspy(IslandData data) {
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

    void wyczyscChunk(int chunkX, int chunkZ, int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
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
