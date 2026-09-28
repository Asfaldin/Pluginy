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

/** Wszystkie okna GUI systemu wysp (układ z wyspy-gui.yml) i obsługa kliknięć w nich. */
final class IslandMenus implements Listener {

    private final IslandManager m;

    IslandMenus(IslandManager m) {
        this.m = m;
    }

    void wypelnijTlo(Inventory inv, IslandScreen screen) {
        ItemStack tlo = new ItemStack(screen.tlo());
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        for (int i = 0; i < screen.size(); i++) inv.setItem(i, tlo);
    }

    ItemStack ikonaZPrzycisku(IslandGuiButton btn, List<Component> dodatkoweLore) {
        return ikonaZPrzycisku(btn, btn.material(), dodatkoweLore);
    }

    ItemStack ikonaZPrzycisku(IslandGuiButton btn, Material material, List<Component> dodatkoweLore) {
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

    void ustawPrzycisk(Inventory inv, IslandScreen screen, String akcja, List<Component> dodatkoweLore) {
        IslandGuiButton btn = screen.przycisk(akcja);
        if (btn == null) return;
        inv.setItem(btn.slot(), ikonaZPrzycisku(btn, dodatkoweLore));
    }

    ItemStack ikonaPrzelacznika(IslandGuiButton btn, boolean on) {
        Material material = on ? btn.material() : (btn.materialWylaczone() != null ? btn.materialWylaczone() : btn.material());
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(btn.nazwa(), btn.kolor(), TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(m.txt(on ? "m.gui.toggle.on" : "m.gui.toggle.off"));
        for (String linia : btn.lore()) lore.add(Component.text(linia, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(m.txt("m.gui.toggle.click"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    void ustawPrzelacznik(Inventory inv, IslandScreen screen, String akcja, boolean on) {
        IslandGuiButton btn = screen.przycisk(akcja);
        if (btn == null) return;
        inv.setItem(btn.slot(), ikonaPrzelacznika(btn, on));
    }

    boolean jestSlotem(IslandScreen screen, String akcja, int slot) {
        IslandGuiButton btn = screen.przycisk(akcja);
        return btn != null && btn.slot() == slot;
    }

    public void otworzMenuWyspy(Player player, boolean zMenu) {
        m.otwartoZMenu.put(player.getUniqueId(), zMenu);
        UUID ownerUUID = m.playerIslandMap.get(player.getUniqueId());
        IslandData data = ownerUUID != null ? m.islandDatabase.get(ownerUUID) : null;

        if (data == null) {
            m.msg(player, "common.no-island");
            return;
        }

        boolean isOwner = data.getOwnerUUID().equals(player.getUniqueId());
        boolean canManage = m.mozeZarzadzac(player.getUniqueId(), data);

        IslandScreen screen = m.gui.panelWyspy();
        Inventory inv = IslandGuiHolder.create("panel", screen.size(), m.txt("m.gui.title.panel"));
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
            loreBank.add(m.txt("m.gui.bank.balance", "balance", IslandTexts.kasa(data.getBankBalance())));
            loreBank.add(m.txt("m.gui.bank.note"));
            loreBank.add(Component.empty());
            loreBank.add(m.txt("m.gui.bank.deposit"));
            loreBank.add(m.txt("m.gui.bank.withdraw"));
            inv.setItem(bank.slot(), ikonaZPrzycisku(bank, loreBank));
        }

        IslandGuiButton info = screen.przycisk("INFO");
        if (info != null) {
            List<Component> loreInfo = new ArrayList<>();
            Player owner = Bukkit.getPlayer(data.getOwnerUUID());
            String ownerName = owner != null ? owner.getName() : m.plain("m.gui.info.unknown-owner");
            String nazwaRoliGracza = m.plain(isOwner ? "role.owner" : (data.getRole(player.getUniqueId()) == IslandRole.ADMIN ? "role.admin" : "role.member"));
            if (data.getCustomName() != null) {
                loreInfo.add(m.txt("m.gui.info.name", "name", data.getCustomName()));
            }
            loreInfo.add(m.txt("m.gui.info.owner", "player", ownerName));
            loreInfo.add(m.txt("m.gui.info.size", "size", String.valueOf(data.getBorderSize())));
            loreInfo.add(m.txt("m.gui.info.members", "count", String.valueOf(data.getMembers().size())));
            loreInfo.add(m.txt("m.gui.info.worth", "worth", IslandTexts.kasa(data.getWorth())));
            loreInfo.add(m.txt("m.gui.info.role", "role", nazwaRoliGracza));
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
        IslandData data = m.wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        IslandScreen screen = m.gui.permisjeWyspy();
        Inventory inv = IslandGuiHolder.create("permisje", screen.size(), m.txt("m.gui.title.permissions"));
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
        IslandData data = m.wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        IslandScreen screen = m.gui.ustawieniaWyspy();
        Inventory inv = IslandGuiHolder.create("ustawienia", screen.size(), m.txt("m.gui.title.settings"));
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
                    m.txt("m.gui.settings.name-current", "name", data.getCustomName() != null ? data.getCustomName() : m.plain("m.gui.settings.name-none")),
                    m.txt("m.gui.settings.name-click")
            );
            inv.setItem(nazwaBtn.slot(), ikonaZPrzycisku(nazwaBtn, lore));
        }

        IslandGuiButton czlonkowieBtn = screen.przycisk("CZLONKOWIE");
        if (czlonkowieBtn != null) {
            List<Component> lore = List.of(
                    m.txt("m.gui.settings.members-current", "count", String.valueOf(data.getMembers().size())),
                    m.txt("m.gui.settings.members-click")
            );
            inv.setItem(czlonkowieBtn.slot(), ikonaZPrzycisku(czlonkowieBtn, lore));
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        player.openInventory(inv);
    }

    /**
     * Ranking wysp na serwerze wg WARTOŚCI (postawione bloki - patrz IslandData.worth /
     * IslandProtectionManager), NIE po promieniu (to inny wymiar niż m.getTopIslands()/
     * IslandSummary używane przez HUD - celowo osobne, żeby nie dotykać współdzielonego
     * API w mainplugins-core). Dostępny dla każdego mieszkańca wyspy, niezależnie od roli.
     */
    public void otworzMenuTopkiWysp(Player player) {
        IslandScreen screen = m.gui.topkaWysp();
        Inventory inv = IslandGuiHolder.create("topka", screen.size(), m.txt("m.gui.title.top"));
        wypelnijTlo(inv, screen);

        List<IslandData> top = new ArrayList<>(m.islandDatabase.values());
        top.sort((a, b) -> Double.compare(b.getWorth(), a.getWorth()));

        int[] sloty = m.gui.topkaSlotyRankingu();
        for (int i = 0; i < top.size() && i < sloty.length; i++) {
            IslandData wyspa = top.get(i);
            int miejsce = i + 1;

            @SuppressWarnings("deprecation")
            OfflinePlayer ownerOffline = Bukkit.getOfflinePlayer(wyspa.getOwnerUUID());
            String ownerNick = ownerOffline.getName() != null ? ownerOffline.getName() : wyspa.getOwnerUUID().toString().substring(0, 8);
            String nazwaWyswietlana = wyspa.getCustomName() != null ? wyspa.getCustomName() : ownerNick;

            ItemStack glowa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) glowa.getItemMeta();
            meta.setOwningPlayer(ownerOffline);
            meta.displayName(m.txt(miejsce <= 3 ? "m.gui.top.place-" + miejsce : "m.gui.top.place-other",
                    "place", String.valueOf(miejsce), "name", nazwaWyswietlana));
            meta.lore(List.of(
                    m.txt("m.gui.top.worth", "worth", IslandTexts.kasa(wyspa.getWorth())),
                    m.txt("m.gui.top.size", "size", String.valueOf(wyspa.getBorderSize())),
                    m.txt("m.gui.top.members", "count", String.valueOf(wyspa.getMembers().size()))
            ));
            glowa.setItemMeta(meta);
            inv.setItem(sloty[i], glowa);
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        player.openInventory(inv);
    }

    public void otworzMenuUlepszen(Player player) {
        IslandData data = m.wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        int currentSize = data.getBorderSize();
        int cost = currentSize * m.tuning.borderKosztZaBlok();
        boolean maksimum = currentSize >= m.tuning.borderMaxRozmiar();

        IslandScreen screen = m.gui.ulepszeniaWyspy();
        Inventory inv = IslandGuiHolder.create("ulepszenia", screen.size(), m.txt("m.gui.title.upgrades"));
        wypelnijTlo(inv, screen);

        IslandGuiButton powieksz = screen.przycisk("POWIEKSZ_TEREN");
        if (powieksz != null) {
            List<Component> lore = maksimum
                    ? List.of(
                            m.txt("m.gui.upgrades.radius", "size", String.valueOf(currentSize)),
                            Component.empty(),
                            m.txt("m.gui.upgrades.max")
                    )
                    : List.of(
                            m.txt("m.gui.upgrades.radius", "size", String.valueOf(currentSize)),
                            m.txt("m.gui.upgrades.cost", "cost", IslandTexts.kasa(cost)),
                            m.txt("m.gui.upgrades.bank", "balance", IslandTexts.kasa(data.getBankBalance())),
                            Component.empty(),
                            m.txt("m.gui.upgrades.click", "step", String.valueOf(m.tuning.borderPrzyrostNaUlepszenie()))
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
        IslandData data = m.wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        IslandScreen screen = m.gui.ulepszenieSpawnerow();
        Inventory inv = IslandGuiHolder.create("spawnery", screen.size(), m.txt("m.gui.title.spawners"));
        wypelnijTlo(inv, screen);

        int[] sloty = m.gui.ulepszenieSpawnerowSlotyTypow();
        List<SpawnerTyp> typy = m.tuning.spawnerTypy();
        for (int i = 0; i < typy.size() && i < sloty.length; i++) {
            SpawnerTyp typ = typy.get(i);
            int poziomIlosci = data.getSpawnerLevel(typ.id() + IslandManager.SUFIKS_ILOSC);
            int poziomSzybkosci = data.getSpawnerLevel(typ.id() + IslandManager.SUFIKS_SZYBKOSC);

            ItemStack item = new ItemStack(typ.ikona());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(m.txt("m.gui.spawners.name", "type", typ.nazwaOdmieniona()));

            String max = String.valueOf(m.tuning.spawnerMaxPoziom());
            List<Component> lore = new ArrayList<>();
            lore.add(m.txt("m.gui.spawners.amount", "level", String.valueOf(poziomIlosci), "max", max));
            lore.add(m.txt("m.gui.spawners.speed", "level", String.valueOf(poziomSzybkosci), "max", max));
            lore.add(Component.empty());
            lore.add(m.txt("m.gui.spawners.manage"));
            meta.lore(lore);
            item.setItemMeta(meta);

            inv.setItem(sloty[i], item);
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        player.openInventory(inv);
    }

    /** Podmenu jednego typu spawnera - osobne ulepszanie Ilości (mobków/cykl) i Szybkości (odstęp między cyklami). */
    public void otworzMenuUlepszenSpawnera(Player player, String typId) {
        IslandData data = m.wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        SpawnerTyp typ = m.tuning.spawnerTyp(typId);
        if (typ == null) return;

        m.otwartySpawnerTyp.put(player.getUniqueId(), typId);

        IslandScreen screen = m.gui.spawnerPodmenu();
        Inventory inv = IslandGuiHolder.create("spawner", screen.size(), m.txt("m.gui.title.spawner", "type", typ.nazwaOdmieniona()));
        wypelnijTlo(inv, screen);

        int poziomIlosci = data.getSpawnerLevel(typId + IslandManager.SUFIKS_ILOSC);
        int poziomSzybkosci = data.getSpawnerLevel(typId + IslandManager.SUFIKS_SZYBKOSC);

        IslandGuiButton iloscBtn = screen.przycisk("ILOSC");
        if (iloscBtn != null) {
            // Ikona ILOSC zawsze bierze się z ikony aktualnie otwartego typu spawnera,
            // niezależnie od "material" w wyspy-m.gui.yml - patrz komentarz tam.
            inv.setItem(iloscBtn.slot(), itemUlepszeniaStatystyki(iloscBtn, typId, typ.ikona(), poziomIlosci, true));
        }
        IslandGuiButton szybkoscBtn = screen.przycisk("SZYBKOSC");
        if (szybkoscBtn != null) {
            inv.setItem(szybkoscBtn.slot(), itemUlepszeniaStatystyki(szybkoscBtn, typId, szybkoscBtn.material(), poziomSzybkosci, false));
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        player.openInventory(inv);
    }

    ItemStack itemUlepszeniaStatystyki(IslandGuiButton btn, String typId, Material ikona, int level, boolean ilosc) {
        boolean maksimum = level >= m.tuning.spawnerMaxPoziom();

        ItemStack item = new ItemStack(ikona);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(btn.nazwa(), btn.kolor(), TextDecoration.BOLD));

        List<Component> lore = new ArrayList<>();
        for (String linia : btn.lore()) lore.add(Component.text(linia, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(m.txt("m.gui.spawners.level", "level", String.valueOf(level), "max", String.valueOf(m.tuning.spawnerMaxPoziom())));
        lore.add(Component.empty());
        if (maksimum) {
            lore.add(m.txt("m.gui.spawners.max"));
        } else {
            lore.add(m.txt("m.gui.spawners.cost", "cost", IslandTexts.kasa(m.tuning.kosztUlepszeniaSpawnera(typId, ilosc, level))));
            lore.add(m.txt("m.gui.spawners.click"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof IslandGuiHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String ekran = holder.ekran();

        boolean zMenu = m.otwartoZMenu.getOrDefault(player.getUniqueId(), false);

        if (ekran.equals("panel")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = m.gui.panelWyspy();

            UUID panelOwnerUUID = m.playerIslandMap.get(player.getUniqueId());
            IslandData panelData = panelOwnerUUID != null ? m.islandDatabase.get(panelOwnerUUID) : null;
            boolean panelIsOwner = panelData != null && panelData.getOwnerUUID().equals(player.getUniqueId());

            if (jestSlotem(screen, "TELEPORT", slot)) { player.closeInventory(); m.teleportDoWyspy(player); }
            else if (jestSlotem(screen, "ULEPSZENIA", slot)) { otworzMenuUlepszen(player); }
            else if (jestSlotem(screen, "USTAWIENIA", slot)) { otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "PERMISJE", slot)) { otworzMenuPermisji(player); }
            else if (jestSlotem(screen, "TOPKA", slot)) { otworzMenuTopkiWysp(player); }
            else if (panelIsOwner && jestSlotem(screen, "USUN_WYSPE", slot)) {
                // Jedno kliknięcie zamiast dawnego "kliknij dwa razy" - potwierdzenie idzie
                // przez czat (wpisanie "Tak zgadzam się"), żeby przypadkowy drugi klik (np. przy
                // zamykaniu GUI) nie mógł już bezpowrotnie skasować wyspy.
                player.closeInventory();
                m.ustawOczekiwanieNaPotwierdzenie(player.getUniqueId());
                m.wyslijOstrzezenieUsuniecia(player);
            }
            else if (!panelIsOwner && jestSlotem(screen, "OPUSC_WYSPE", slot)) {
                if (m.pendingLeaveConfirmation.contains(player.getUniqueId())) {
                    player.closeInventory();
                    m.pendingLeaveConfirmation.remove(player.getUniqueId());
                    m.opuscWyspe(player);
                } else {
                    m.pendingLeaveConfirmation.add(player.getUniqueId());
                    Bukkit.getScheduler().runTaskLater(m.plugin, () -> m.pendingLeaveConfirmation.remove(player.getUniqueId()), m.tuning.timeoutPotwierdzeniaTicks());
                    ItemStack item = event.getCurrentItem();
                    if (item != null) {
                        ItemMeta meta = item.getItemMeta();
                        meta.displayName(m.txt("m.gui.panel.leave-confirm"));
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
        else if (ekran.equals("permisje")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = m.gui.permisjeWyspy();
            if (jestSlotem(screen, "ZABIERANIE_ITEMOW", slot)) { m.przelaczZabieranieItemow(player); otworzMenuPermisji(player); }
            else if (jestSlotem(screen, "DOSTEP_KONTENEROW", slot)) { m.przelaczDostepDoKontenerow(player); otworzMenuPermisji(player); }
            else if (jestSlotem(screen, "INTERAKCJE", slot)) { m.przelaczInterakcje(player); otworzMenuPermisji(player); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (ekran.equals("ustawienia")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = m.gui.ustawieniaWyspy();
            if (jestSlotem(screen, "WIZUALNY_BORDER", slot)) { m.przelaczWizualnyBorder(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "BUDOWANIE_GOSCI", slot)) { m.przelaczBudowanieDlaGosci(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "PVP", slot)) { m.przelaczPvP(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "POTWORY", slot)) { m.przelaczPotwory(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "ZABIJANIE_MOBOW_GOSCI", slot)) { m.przelaczZabijanieMobowPrzezGosci(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "POGODA_CZAS", slot)) { m.przelaczPogodeICzas(player); otworzMenuUstawienWyspy(player); }
            else if (jestSlotem(screen, "NAZWA_WYSPY", slot)) { m.chat.rozpocznijZmianeNazwyWyspy(player); }
            else if (jestSlotem(screen, "CZLONKOWIE", slot)) { otworzMenuCzlonkow(player); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (ekran.equals("topka")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (jestSlotem(m.gui.topkaWysp(), "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (ekran.equals("ulepszenia")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = m.gui.ulepszeniaWyspy();
            if (jestSlotem(screen, "POWIEKSZ_TEREN", slot)) { m.uprosGranice(player); }
            else if (jestSlotem(screen, "ULEPSZENIE_SPAWNEROW", slot)) { otworzMenuWzrostuDropow(player); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (ekran.equals("spawnery")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = m.gui.ulepszenieSpawnerow();
            if (jestSlotem(screen, "POWROT", slot)) { otworzMenuUlepszen(player); return; }

            int[] sloty = m.gui.ulepszenieSpawnerowSlotyTypow();
            List<SpawnerTyp> typy = m.tuning.spawnerTypy();
            for (int i = 0; i < sloty.length && i < typy.size(); i++) {
                if (sloty[i] == slot) {
                    otworzMenuUlepszenSpawnera(player, typy.get(i).id());
                    break;
                }
            }
        }
        else if (ekran.equals("spawner")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            String typId = m.otwartySpawnerTyp.get(player.getUniqueId());
            if (typId == null) return;

            IslandScreen screen = m.gui.spawnerPodmenu();
            if (jestSlotem(screen, "ILOSC", slot)) { m.ulepszSpawnerStatystyke(player, typId, IslandManager.SUFIKS_ILOSC); }
            else if (jestSlotem(screen, "SZYBKOSC", slot)) { m.ulepszSpawnerStatystyke(player, typId, IslandManager.SUFIKS_SZYBKOSC); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWzrostuDropow(player); }
        }
        else if (ekran.equals("czlonkowie")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            ItemStack clicked = event.getCurrentItem();
            IslandScreen screen = m.gui.czlonkowieWyspy();
            if (clicked == null || clicked.getType() == screen.tlo()) return;

            if (jestSlotem(screen, "POWROT", slot)) { // Powrót do Ustawień Wyspy
                otworzMenuUstawienWyspy(player);
            } else if (jestSlotem(screen, "ZAPROS", slot)) { // Zaproś gracza (przez czat)
                player.closeInventory();
                m.pendingInviteChat.add(player.getUniqueId());
                m.msg(player, "invite.chat-prompt", "cancel", m.plain("common.cancel-word"));
            } else {
                Map<Integer, UUID> mapaSlotow = m.slotyCzlonkow.get(player.getUniqueId());
                UUID targetUUID = mapaSlotow != null ? mapaSlotow.get(slot) : null;
                if (targetUUID != null) {
                    if (event.getClick().isRightClick()) {
                        UUID ownerUUID = m.playerIslandMap.get(player.getUniqueId());
                        IslandData data = m.islandDatabase.get(ownerUUID);
                        if (data != null) {
                            IslandRole obecna = data.getRole(targetUUID);
                            m.zmienRoleCzlonka(player, targetUUID, obecna == IslandRole.ADMIN ? IslandRole.CZLONEK : IslandRole.ADMIN);
                        }
                    } else {
                        m.usunCzlonka(player, targetUUID);
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
        IslandData data = m.wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        IslandScreen screen = m.gui.czlonkowieWyspy();
        Inventory inv = IslandGuiHolder.create("czlonkowie", screen.size(), m.txt("m.gui.title.members"));
        wypelnijTlo(inv, screen);

        boolean viewerIsOwner = data.getOwnerUUID().equals(player.getUniqueId());

        // Nieklikalna karta właściciela, żeby lista jasno pokazywała kto nim jest.
        @SuppressWarnings("deprecation")
        OfflinePlayer ownerOffline = Bukkit.getOfflinePlayer(data.getOwnerUUID());
        String ownerNick = ownerOffline.getName() != null ? ownerOffline.getName() : data.getOwnerUUID().toString();
        ItemStack kartaWlasciciela = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta metaWlasciciela = (SkullMeta) kartaWlasciciela.getItemMeta();
        metaWlasciciela.setOwningPlayer(ownerOffline);
        metaWlasciciela.displayName(m.txt("m.gui.members.owner-name", "player", ownerNick));
        metaWlasciciela.lore(List.of(m.txt("m.gui.members.owner-lore")));
        kartaWlasciciela.setItemMeta(metaWlasciciela);
        inv.setItem(m.gui.czlonkowieSlotWlasciciela(), kartaWlasciciela);

        Map<Integer, UUID> mapaSlotow = new HashMap<>();
        int slot = m.gui.czlonkowiePierwszySlot();
        for (UUID memberUUID : data.getMembers()) {
            if (slot > m.gui.czlonkowieOstatniSlot()) break; // zabezpieczenie na wypadek bardzo dużej liczby członków

            IslandRole rola = data.getRole(memberUUID);
            boolean memberIsAdmin = rola == IslandRole.ADMIN;

            @SuppressWarnings("deprecation")
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(memberUUID);
            String nick = offlinePlayer.getName() != null ? offlinePlayer.getName() : memberUUID.toString();

            ItemStack glowa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) glowa.getItemMeta();
            meta.setOwningPlayer(offlinePlayer);
            meta.displayName(memberIsAdmin
                    ? m.txt("m.gui.members.admin-name", "player", nick)
                    : m.txt("m.gui.members.member-name", "player", nick));

            List<Component> lore = new ArrayList<>();
            lore.add(m.txt(memberIsAdmin ? "m.gui.members.role-admin" : "m.gui.members.role-member"));
            lore.add(Component.empty());
            if (viewerIsOwner) {
                lore.add(m.txt("m.gui.members.kick"));
                lore.add(m.txt(memberIsAdmin ? "m.gui.members.demote" : "m.gui.members.promote"));
            } else if (!memberIsAdmin) {
                lore.add(m.txt("m.gui.members.kick"));
            } else {
                lore.add(m.txt("m.gui.members.cant-manage"));
            }
            meta.lore(lore);
            glowa.setItemMeta(meta);

            inv.setItem(slot, glowa);
            mapaSlotow.put(slot, memberUUID);
            slot++;
        }
        m.slotyCzlonkow.put(player.getUniqueId(), mapaSlotow);

        ustawPrzycisk(inv, screen, "ZAPROS", null);
        ustawPrzycisk(inv, screen, "POWROT", null);

        player.openInventory(inv);
    }
}
