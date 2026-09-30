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

/** Dane jednej wyspy (zapisywane w wyspy.yml przez IslandStorage). */
public class IslandData {
    private final int id;
    private UUID ownerUUID;
    private final int centerX;
    private final int centerZ;
    private int borderSize;
    private final Set<UUID> members = new HashSet<>();
    // Gracze, którzy nie mogą wejść na tę wyspę (/is zbanuj) - ani pieszo, ani teleportem.
    private final Set<UUID> banned = new HashSet<>();

    // allowBreak/allowPvP/allowMobs dotyczą WYŁĄCZNIE gości - właściciel i członkowie
    // zawsze mogą budować/niszczyć na własnej wyspie niezależnie od tych ustawień
    // (patrz IslandProtectionManager). allowBreak domyślnie false - wyspa jest
    // prywatna od razu po stworzeniu, właściciel musi ją świadomie otworzyć.
    private boolean allowPvP = false;
    private boolean allowBreak = false;
    private boolean visualBorder = true; // Nowa zmienna do wizualnego borderu

    // Podobnie jak allowBreak/allowPvP/allowMobs wyżej - dotyczą WYŁĄCZNIE gości,
    // domyślnie zablokowane (wyspa prywatna od razu po stworzeniu).
    private boolean allowGuestMobKill = false;
    private boolean allowItemPickup = false;
    private boolean allowContainerAccess = false;
    private boolean allowInteract = false;

    // Czy inni gracze mogą wejść na wyspę przez /is odwiedz <gracz>.
    private boolean openForVisitors = true;
    // Goście: zbieranie plonów, sadzenie, karmienie/rozmnażanie/strzyżenie zwierząt.
    private boolean allowGuestFarming = false;
    // Goście: wylewanie i nabieranie wody/lawy wiadrem.
    private boolean allowGuestBuckets = false;

    // Co mogą zwykli członkowie (rola CZLONEK) - właściciel i admini wyspy mogą zawsze wszystko.
    private boolean memberBuild = true;
    private boolean memberContainers = true;
    private boolean memberInvite = false;
    private boolean memberBankUpgrade = false;

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

    // Biom wybrany w oknie "Biom wyspy" (null = biom świata, nic nie zmieniane).
    private String biom = null;

    // Poziom (domyślnie 1) każdego typu customowego spawnera wykupionego przez
    // właściciela wyspy - klucz to SpawnerType.name() z mainplugins-spawners,
    // ale IslandData celowo trzyma go jako zwykły String (patrz komentarz w
    // IslandSummary) - skyblock nie ma i nie powinien mieć zależności na moduł spawnerów.
    private final Map<String, Integer> spawnerLevels = new HashMap<>();

    // Ile bloków z listy limitów (wyspy-config.yml: limity.bloki) stoi na wyspie - liczone przy stawianiu/niszczeniu.
    private final Map<String, Integer> blockCounts = new HashMap<>();
    public Map<String, Integer> getBlockCounts() { return blockCounts; }
    public int getBlockCount(Material m) { return blockCounts.getOrDefault(m.name(), 0); }
    public void addBlockCount(Material m, int delta) { blockCounts.merge(m.name(), delta, (a, b) -> Math.max(0, a + b)); }

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
    /** Tylko dla /is przekaz - IslandManager przepina przy tym wszystkie mapy wysp. */
    void setOwnerUUID(UUID ownerUUID) { this.ownerUUID = ownerUUID; }
    public Set<UUID> getBanned() { return banned; }
    public int getCenterX() { return centerX; }
    public int getCenterZ() { return centerZ; }
    public int getBorderSize() { return borderSize; }
    public void setBorderSize(int borderSize) { this.borderSize = borderSize; }
    public Set<UUID> getMembers() { return members; }

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
    public boolean isOpenForVisitors() { return openForVisitors; }
    public void setOpenForVisitors(boolean openForVisitors) { this.openForVisitors = openForVisitors; }
    public boolean isAllowGuestFarming() { return allowGuestFarming; }
    public void setAllowGuestFarming(boolean allowGuestFarming) { this.allowGuestFarming = allowGuestFarming; }
    public boolean isAllowGuestBuckets() { return allowGuestBuckets; }
    public void setAllowGuestBuckets(boolean allowGuestBuckets) { this.allowGuestBuckets = allowGuestBuckets; }
    public boolean isMemberBuild() { return memberBuild; }
    public void setMemberBuild(boolean v) { this.memberBuild = v; }
    public boolean isMemberContainers() { return memberContainers; }
    public void setMemberContainers(boolean v) { this.memberContainers = v; }
    public boolean isMemberInvite() { return memberInvite; }
    public void setMemberInvite(boolean v) { this.memberInvite = v; }
    public boolean isMemberBankUpgrade() { return memberBankUpgrade; }
    public void setMemberBankUpgrade(boolean v) { this.memberBankUpgrade = v; }
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

    public String getBiom() { return biom; }
    public void setBiom(String biom) { this.biom = biom; }
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
