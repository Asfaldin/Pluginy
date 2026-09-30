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

/** Zapis i odczyt wysp oraz historii ich tworzenia (plik wyspy.yml). */
final class IslandStorage {

    /** Ile razy gracz w sumie utworzył wyspę (create+delete się liczy) i kiedy ostatnio - patrz historiaTworzeniaWysp. */
    static final class HistoriaTworzenia {
        int ilosc;
        long ostatnieMillis;
    }

    private final IslandManager m;
    private final File plikWysp;
    private final FileConfiguration configWysp;
    private final AsyncConfigSaver saverWysp;

    IslandStorage(IslandManager m) {
        this.m = m;
        this.plikWysp = new File(m.plugin.getDataFolder(), "wyspy.yml");
        if (!plikWysp.exists()) {
            plikWysp.getParentFile().mkdirs();
            try { plikWysp.createNewFile(); } catch (IOException ignored) {}
        }
        this.configWysp = YamlConfiguration.loadConfiguration(plikWysp);
        this.saverWysp = new AsyncConfigSaver(m.plugin, configWysp, plikWysp, 30);
    }

    /** Wywołaj w onDisable() modułu skyblock - zapisuje natychmiast, zatrzymuje cykl. */
    void zamknij() {
        saverWysp.zamknij();
    }

    /**
     * Wczytuje wszystkie wyspy (właścicieli, członków, rozmiar, ustawienia) z wyspy.yml
     * przy starcie pluginu - bez tego cała baza wysp żyła tylko w pamięci i znikała
     * po każdym restarcie serwera, mimo że same bloki zostawały w świecie.
     */
    void wczytajWyspy() {
        m.nextIslandId = configWysp.getInt("nextIslandId", 0);

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
            int id = configWysp.getInt(path + "id", m.nextIslandId);
            int centerX = configWysp.getInt(path + "centerX");
            int centerZ = configWysp.getInt(path + "centerZ");
            int borderSize = configWysp.getInt(path + "borderSize", m.tuning.domyslnyRozmiarWyspy());

            IslandData data = new IslandData(id, ownerUUID, centerX, centerZ, borderSize);
            data.setAllowPvP(configWysp.getBoolean(path + "allowPvP", false));
            data.setAllowBreak(configWysp.getBoolean(path + "allowBreak", false));
            data.setVisualBorder(configWysp.getBoolean(path + "visualBorder", true));
            data.setAllowGuestMobKill(configWysp.getBoolean(path + "allowGuestMobKill", false));
            data.setAllowItemPickup(configWysp.getBoolean(path + "allowItemPickup", false));
            data.setAllowContainerAccess(configWysp.getBoolean(path + "allowContainerAccess", false));
            data.setAllowInteract(configWysp.getBoolean(path + "allowInteract", false));
            data.setOpenForVisitors(configWysp.getBoolean(path + "openForVisitors", true));
            data.setAllowGuestFarming(configWysp.getBoolean(path + "allowGuestFarming", false));
            data.setAllowGuestBuckets(configWysp.getBoolean(path + "allowGuestBuckets", false));
            data.setMemberBuild(configWysp.getBoolean(path + "memberBuild", true));
            data.setMemberContainers(configWysp.getBoolean(path + "memberContainers", true));
            data.setMemberInvite(configWysp.getBoolean(path + "memberInvite", false));
            data.setMemberBankUpgrade(configWysp.getBoolean(path + "memberBankUpgrade", false));
            data.setCustomName(configWysp.getString(path + "customName"));
            data.setBankBalance(configWysp.getDouble(path + "bankBalance", 0.0));
            data.setWorth(configWysp.getDouble(path + "worth", 0.0));
            data.setBiom(configWysp.getString(path + "biom"));

            ConfigurationSection licznikiSekcja = configWysp.getConfigurationSection(path + "blockCounts");
            if (licznikiSekcja != null) {
                for (String blok : licznikiSekcja.getKeys(false)) data.getBlockCounts().put(blok, licznikiSekcja.getInt(blok));
            }

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
                    m.playerIslandMap.put(memberUUID, ownerUUID);
                } catch (IllegalArgumentException ignored) {}
            }

            for (String banStr : configWysp.getStringList(path + "zbanowani")) {
                try {
                    data.getBanned().add(UUID.fromString(banStr));
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

            m.islandDatabase.put(ownerUUID, data);
            m.playerIslandMap.put(ownerUUID, ownerUUID);
        }

        m.plugin.getLogger().info("Wczytano " + m.islandDatabase.size() + " wysp z wyspy.yml.");
    }

    /** Zapisuje pełny, aktualny stan wszystkich wysp na dysk. Wołane po każdej zmianie. */
    void zapiszWyspy() {
        configWysp.set("wyspy", null); // czyścimy stare wpisy, żeby usunięte wyspy nie zostawały w pliku
        configWysp.set("nextIslandId", m.nextIslandId);

        for (Map.Entry<UUID, IslandData> entry : m.islandDatabase.entrySet()) {
            String path = "wyspy." + entry.getKey() + ".";
            IslandData data = entry.getValue();

            configWysp.set(path + "id", data.getId());
            configWysp.set(path + "centerX", data.getCenterX());
            configWysp.set(path + "centerZ", data.getCenterZ());
            configWysp.set(path + "borderSize", data.getBorderSize());
            configWysp.set(path + "allowPvP", data.isAllowPvP());
            configWysp.set(path + "allowBreak", data.isAllowBreak());
            configWysp.set(path + "visualBorder", data.isVisualBorder());
            configWysp.set(path + "allowGuestMobKill", data.isAllowGuestMobKill());
            configWysp.set(path + "allowItemPickup", data.isAllowItemPickup());
            configWysp.set(path + "allowContainerAccess", data.isAllowContainerAccess());
            configWysp.set(path + "allowInteract", data.isAllowInteract());
            configWysp.set(path + "openForVisitors", data.isOpenForVisitors());
            configWysp.set(path + "allowGuestFarming", data.isAllowGuestFarming());
            configWysp.set(path + "allowGuestBuckets", data.isAllowGuestBuckets());
            configWysp.set(path + "memberBuild", data.isMemberBuild());
            configWysp.set(path + "memberContainers", data.isMemberContainers());
            configWysp.set(path + "memberInvite", data.isMemberInvite());
            configWysp.set(path + "memberBankUpgrade", data.isMemberBankUpgrade());
            configWysp.set(path + "customName", data.getCustomName());
            configWysp.set(path + "bankBalance", data.getBankBalance());
            configWysp.set(path + "worth", data.getWorth());
            configWysp.set(path + "biom", data.getBiom());

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

            for (Map.Entry<String, Integer> licznik : data.getBlockCounts().entrySet()) {
                configWysp.set(path + "blockCounts." + licznik.getKey(), licznik.getValue());
            }

            for (Map.Entry<String, Integer> lvl : data.getSpawnerLevels().entrySet()) {
                configWysp.set(path + "spawnerLevels." + lvl.getKey(), lvl.getValue());
            }

            List<String> czlonkowie = new ArrayList<>();
            for (UUID member : data.getMembers()) czlonkowie.add(member.toString());
            configWysp.set(path + "czlonkowie", czlonkowie);
            List<String> zbanowani = new ArrayList<>();
            for (UUID ban : data.getBanned()) zbanowani.add(ban.toString());
            configWysp.set(path + "zbanowani", zbanowani);

            configWysp.set(path + "role", null); // czyścimy, tak samo jak "wyspy" wyżej - usunięci/zdegradowani członkowie nie mają zostawać
            for (Map.Entry<UUID, IslandRole> role : data.getMemberRoles().entrySet()) {
                configWysp.set(path + "role." + role.getKey(), role.getValue().name());
            }
        }

        // Historia tworzenia (cooldown anty-spam) - NIE czyścimy sekcji przed zapisem jak "wyspy"
        // wyżej, bo wpisy tu żyją niezależnie od tego czy gracz aktualnie ma wyspę.
        for (Map.Entry<UUID, HistoriaTworzenia> entry : m.historiaTworzeniaWysp.entrySet()) {
            String path = "historiaTworzenia." + entry.getKey() + ".";
            configWysp.set(path + "ilosc", entry.getValue().ilosc);
            configWysp.set(path + "ostatnie", entry.getValue().ostatnieMillis);
        }

        saverWysp.oznaczZmiane();
    }

    /** Wczytuje historię tworzenia wysp (cooldown anty-spam) - osobno od wczytajWyspy(), bo dotyczy
     *  graczy niezależnie od tego czy aktualnie mają wyspę (ta metoda ma early-return gdy jej brak). */
    void wczytajHistorieTworzenia() {
        ConfigurationSection sekcja = configWysp.getConfigurationSection("historiaTworzenia");
        if (sekcja == null) return;

        for (String key : sekcja.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                HistoriaTworzenia historia = new HistoriaTworzenia();
                historia.ilosc = configWysp.getInt("historiaTworzenia." + key + ".ilosc", 0);
                historia.ostatnieMillis = configWysp.getLong("historiaTworzenia." + key + ".ostatnie", 0L);
                m.historiaTworzeniaWysp.put(uuid, historia);
            } catch (IllegalArgumentException ignored) {}
        }
    }
}
