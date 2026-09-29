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
    /** Strona okna, którą gracz ma teraz otwartą (strony dokłada właściciel w aplikacji: "strony" + "strona" przycisku). */
    private final Map<UUID, Integer> strona = new HashMap<>();
    /** Strona, którą właśnie rysujemy albo w którą gracz kliknął - przyciski z innych stron są pomijane. */
    private int biezaca = 0;

    IslandMenus(IslandManager m) {
        this.m = m;
    }

    /** Początek rysowania okna: strona w granicach okna, zapamiętana dla gracza i dla sprawdzania przycisków. */
    private int zacznijStrone(Player player, IslandScreen screen, int page) {
        int p = Math.max(0, Math.min(page, screen.strony() - 1));
        strona.put(player.getUniqueId(), p);
        biezaca = p;
        return p;
    }

    /** Strzałki stron: "Poprzednia" od 2. strony, "Następna" tylko gdy jest kolejna strona (inaczej pole zostaje tłem). */
    void ustawStrzalki(Inventory inv, IslandScreen screen, int page) {
        if (page > 0) ustawPrzycisk(inv, screen, "POPRZEDNIA_STRONA", null);
        if (page < screen.strony() - 1) ustawPrzycisk(inv, screen, "NASTEPNA_STRONA", null);
    }

    /** Przycisk z okna, ale tylko jeśli stoi na rysowanej stronie (bez "strona" = na każdej). */
    IslandGuiButton widoczny(IslandScreen screen, String akcja) {
        IslandGuiButton btn = screen.przycisk(akcja);
        return btn != null && btn.naStronie(biezaca) ? btn : null;
    }

    /** Klik w strzałkę strony: -1 / +1, 0 = to nie strzałka (albo jej nie widać, bo pole jest tłem). */
    private int kierunekStrony(IslandScreen screen, int slot, ItemStack clicked) {
        if (clicked == null || clicked.getType().isAir() || clicked.getType() == screen.tlo()) return 0;
        if (jestSlotem(screen, "NASTEPNA_STRONA", slot)) return 1;
        if (jestSlotem(screen, "POPRZEDNIA_STRONA", slot)) return -1;
        return 0;
    }

    /**
     * Które spawnery stoją na tej stronie okna Spawnery (pole -> spawner). Ta sama reguła co w aplikacji:
     * spawner ze stałym miejscem stoi na swoim polu i stronie, schowany się nie pokazuje, reszta (np. nowe)
     * wchodzi po kolei na wolne miejsca, strona po stronie, z pominięciem pól zajętych na danej stronie.
     */
    Map<Integer, SpawnerTyp> spawneryNaStronie(int page) {
        IslandScreen screen = m.gui.ulepszenieSpawnerow();
        int strony = Math.max(1, screen.strony());
        List<Map<Integer, SpawnerTyp>> naStronach = new ArrayList<>();
        for (int p = 0; p < strony; p++) naStronach.add(new HashMap<>());
        List<SpawnerTyp> luzem = new ArrayList<>();
        for (SpawnerTyp typ : m.tuning.spawnerTypy()) {
            if (m.gui.ukryteSpawnery().contains(typ.id())) continue;
            int[] pole = m.gui.spawneryPola().get(typ.id());
            if (pole == null) { luzem.add(typ); continue; }
            if (pole[1] >= 0 && pole[1] < strony && pole[0] >= 0 && pole[0] < screen.size()) naStronach.get(pole[1]).putIfAbsent(pole[0], typ);
        }
        int i = 0;
        for (int p = 0; p < strony && i < luzem.size(); p++) {
            for (int slot : m.gui.ulepszenieSpawnerowSlotyTypow()) {
                if (i >= luzem.size()) break;
                if (naStronach.get(p).containsKey(slot)) continue;
                naStronach.get(p).put(slot, luzem.get(i++));
            }
        }
        return page >= 0 && page < strony ? naStronach.get(page) : Map.of();
    }

    /** Ulepszenia spawnerów mają sens tylko z pluginem Spawnery - bez niego przycisku nie ma (pole zostaje tłem). */
    static boolean saSpawnery() {
        return Bukkit.getPluginManager().isPluginEnabled("MainpluginsSpawners");
    }

    void wypelnijTlo(Inventory inv, IslandScreen screen) {
        if (screen.tlo().isAir()) return; // tlo: AIR = puste wolne pola
        ItemStack tlo = new ItemStack(screen.tlo());
        ItemMeta meta = tlo.getItemMeta();
        meta.displayName(Component.empty());
        tlo.setItemMeta(meta);
        if (screen.tloPola() == null) {
            for (int i = 0; i < screen.size(); i++) inv.setItem(i, tlo);
        } else {
            for (int i : screen.tloPola()) inv.setItem(i, tlo);
        }
    }

    /** Nazwa przycisku z lang (buttons.<okno>.<akcja>.name). */
    Component nazwaPrzycisku(IslandGuiButton btn) {
        return m.txt(btn.tekst() + ".name");
    }

    /** Opis przycisku z lang: lore-1 i lore-2, pusta linijka = brak. */
    List<Component> opisPrzycisku(IslandGuiButton btn) {
        List<Component> out = new ArrayList<>();
        for (String k : new String[] {".lore-1", ".lore-2"}) {
            Component linia = m.txt(btn.tekst() + k);
            if (!PlainTextComponentSerializer.plainText().serialize(linia).isBlank()) out.add(linia);
        }
        return out;
    }

    ItemStack ikonaZPrzycisku(IslandGuiButton btn, List<Component> dodatkoweLore) {
        return ikonaZPrzycisku(btn, btn.material(), dodatkoweLore);
    }

    ItemStack ikonaZPrzycisku(IslandGuiButton btn, Material material, List<Component> dodatkoweLore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(nazwaPrzycisku(btn));
        List<Component> lore = new ArrayList<>();
        lore.addAll(opisPrzycisku(btn));
        if (dodatkoweLore != null) lore.addAll(dodatkoweLore);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    void ustawPrzycisk(Inventory inv, IslandScreen screen, String akcja, List<Component> dodatkoweLore) {
        IslandGuiButton btn = widoczny(screen, akcja);
        if (btn == null) return;
        inv.setItem(btn.slot(), ikonaZPrzycisku(btn, dodatkoweLore));
    }

    ItemStack ikonaPrzelacznika(IslandGuiButton btn, boolean on) {
        Material material = on ? btn.material() : (btn.materialWylaczone() != null ? btn.materialWylaczone() : btn.material());
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(nazwaPrzycisku(btn));
        List<Component> lore = new ArrayList<>();
        lore.add(m.txt(on ? "gui.toggle.on" : "gui.toggle.off"));
        lore.addAll(opisPrzycisku(btn));
        lore.add(m.txt("gui.toggle.click"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    void ustawPrzelacznik(Inventory inv, IslandScreen screen, String akcja, boolean on) {
        IslandGuiButton btn = widoczny(screen, akcja);
        if (btn == null) return;
        inv.setItem(btn.slot(), ikonaPrzelacznika(btn, on));
    }

    boolean jestSlotem(IslandScreen screen, String akcja, int slot) {
        IslandGuiButton btn = widoczny(screen, akcja);
        return btn != null && btn.slot() == slot;
    }

    public void otworzMenuWyspy(Player player, boolean zMenu) {
        otworzMenuWyspy(player, zMenu, 0);
    }

    public void otworzMenuWyspy(Player player, boolean zMenu, int page) {
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
        page = zacznijStrone(player, screen, page);
        Inventory inv = IslandGuiHolder.create("panel", screen.size(), m.txt("gui.title.panel"));
        wypelnijTlo(inv, screen);

        // Kolejność kafelków w panelu odzwierciedla częstotliwość użycia (najczęstsze
        // pierwsze) - Teleport, Ulepszenia i Bank to codzienne akcje, Informacje/Topka
        // to głównie wgląd, Ustawienia/Permisje to rzadko zmieniane ustawienia jednorazowe.

        ustawPrzycisk(inv, screen, "TELEPORT", null);

        if (canManage) {
            ustawPrzycisk(inv, screen, "ULEPSZENIA", null);
        }

        IslandGuiButton bank = widoczny(screen, "BANK");
        if (bank != null) {
            List<Component> loreBank = new ArrayList<>();
            loreBank.add(m.txt("gui.bank.balance", "balance", IslandTexts.kasa(data.getBankBalance())));
            loreBank.add(m.txt("gui.bank.note"));
            loreBank.add(Component.empty());
            loreBank.add(m.txt("gui.bank.deposit"));
            loreBank.add(m.txt("gui.bank.withdraw"));
            inv.setItem(bank.slot(), ikonaZPrzycisku(bank, loreBank));
        }

        IslandGuiButton info = widoczny(screen, "INFO");
        if (info != null) {
            List<Component> loreInfo = new ArrayList<>();
            Player owner = Bukkit.getPlayer(data.getOwnerUUID());
            String ownerName = owner != null ? owner.getName() : m.plain("gui.info.unknown-owner");
            String nazwaRoliGracza = m.plain(isOwner ? "role.owner" : (data.getRole(player.getUniqueId()) == IslandRole.ADMIN ? "role.admin" : "role.member"));
            if (data.getCustomName() != null) {
                loreInfo.add(m.txt("gui.info.name", "name", data.getCustomName()));
            }
            loreInfo.add(m.txt("gui.info.owner", "player", ownerName));
            loreInfo.add(m.txt("gui.info.size", "size", String.valueOf(data.getBorderSize())));
            loreInfo.add(m.txt("gui.info.members", "count", String.valueOf(data.getMembers().size())));
            loreInfo.add(m.txt("gui.info.worth", "worth", IslandTexts.kasa(data.getWorth())));
            loreInfo.add(m.txt("gui.info.role", "role", nazwaRoliGracza));
            inv.setItem(info.slot(), ikonaZPrzycisku(info, loreInfo));
        }

        if (canManage) {
            ustawPrzycisk(inv, screen, "USTAWIENIA", null);
            ustawPrzycisk(inv, screen, "PERMISJE", null);
        }

        ustawPrzycisk(inv, screen, "TOPKA", null);

        ustawPrzycisk(inv, screen, isOwner ? "USUN_WYSPE" : "OPUSC_WYSPE", null);
        ustawPrzycisk(inv, screen, zMenu ? "WROC_DO_MENU" : "ZAMKNIJ_PANEL", null);

        ustawStrzalki(inv, screen, page);
        player.openInventory(inv);
    }

    /** Podmenu "Permisje" - dodatkowe uprawnienia gości (poza budowaniem/PvP/mobami - patrz Ustawienia Wyspy). Wyłącznie dla właściciela i adminów. */
    public void otworzMenuPermisji(Player player) {
        otworzMenuPermisji(player, 0);
    }

    public void otworzMenuPermisji(Player player, int page) {
        IslandData data = m.wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        IslandScreen screen = m.gui.permisjeWyspy();
        page = zacznijStrone(player, screen, page);
        Inventory inv = IslandGuiHolder.create("permisje", screen.size(), m.txt("gui.title.permissions"));
        wypelnijTlo(inv, screen);

        ustawPrzelacznik(inv, screen, "ZABIERANIE_ITEMOW", data.isAllowItemPickup());
        ustawPrzelacznik(inv, screen, "DOSTEP_KONTENEROW", data.isAllowContainerAccess());
        ustawPrzelacznik(inv, screen, "INTERAKCJE", data.isAllowInteract());
        ustawPrzelacznik(inv, screen, "ROLNICTWO_GOSCI", data.isAllowGuestFarming());
        ustawPrzelacznik(inv, screen, "WIADRA_GOSCI", data.isAllowGuestBuckets());
        ustawPrzelacznik(inv, screen, "CZLONKOWIE_BUDOWANIE", data.isMemberBuild());
        ustawPrzelacznik(inv, screen, "CZLONKOWIE_SKRZYNIE", data.isMemberContainers());
        ustawPrzelacznik(inv, screen, "CZLONKOWIE_ZAPRASZANIE", data.isMemberInvite());
        ustawPrzelacznik(inv, screen, "CZLONKOWIE_BANK", data.isMemberBankUpgrade());
        ustawPrzycisk(inv, screen, "POWROT", null);

        ustawStrzalki(inv, screen, page);
        player.openInventory(inv);
    }

    /**
     * Podmenu "Ustawienia Wyspy" - ogólna konfiguracja terenu (border, budowanie/PvP/moby,
     * pogoda i czas, nazwa) + dostęp do zarządzania członkami. Wyłącznie dla właściciela i adminów.
     */
    public void otworzMenuUstawienWyspy(Player player) {
        otworzMenuUstawienWyspy(player, 0);
    }

    public void otworzMenuUstawienWyspy(Player player, int page) {
        IslandData data = m.wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        IslandScreen screen = m.gui.ustawieniaWyspy();
        page = zacznijStrone(player, screen, page);
        Inventory inv = IslandGuiHolder.create("ustawienia", screen.size(), m.txt("gui.title.settings"));
        wypelnijTlo(inv, screen);

        ustawPrzelacznik(inv, screen, "WIZUALNY_BORDER", data.isVisualBorder());
        ustawPrzelacznik(inv, screen, "BUDOWANIE_GOSCI", data.isAllowBreak());
        ustawPrzelacznik(inv, screen, "PVP", data.isAllowPvP());
        ustawPrzelacznik(inv, screen, "ZABIJANIE_MOBOW_GOSCI", data.isAllowGuestMobKill());
        ustawPrzelacznik(inv, screen, "ODWIEDZINY", data.isOpenForVisitors());

        IslandGuiButton nazwaBtn = widoczny(screen, "NAZWA_WYSPY");
        if (nazwaBtn != null) {
            List<Component> lore = List.of(
                    m.txt("gui.settings.name-current", "name", data.getCustomName() != null ? data.getCustomName() : m.plain("gui.settings.name-none")),
                    m.txt("gui.settings.name-click")
            );
            inv.setItem(nazwaBtn.slot(), ikonaZPrzycisku(nazwaBtn, lore));
        }

        IslandGuiButton czlonkowieBtn = widoczny(screen, "CZLONKOWIE");
        if (czlonkowieBtn != null) {
            List<Component> lore = List.of(
                    m.txt("gui.settings.members-current", "count", String.valueOf(data.getMembers().size())),
                    m.txt("gui.settings.members-click")
            );
            inv.setItem(czlonkowieBtn.slot(), ikonaZPrzycisku(czlonkowieBtn, lore));
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        ustawStrzalki(inv, screen, page);
        player.openInventory(inv);
    }

    /**
     * Ranking wysp na serwerze wg WARTOŚCI (postawione bloki - patrz IslandData.worth /
     * IslandProtectionManager), NIE po promieniu (to inny wymiar niż m.getTopIslands()/
     * IslandSummary używane przez HUD - celowo osobne, żeby nie dotykać współdzielonego
     * API w mainplugins-core). Dostępny dla każdego mieszkańca wyspy, niezależnie od roli.
     */
    public void otworzMenuTopkiWysp(Player player) {
        otworzMenuTopkiWysp(player, 0);
    }

    public void otworzMenuTopkiWysp(Player player, int page) {
        IslandScreen screen = m.gui.topkaWysp();
        page = zacznijStrone(player, screen, page);
        Inventory inv = IslandGuiHolder.create("topka", screen.size(), m.txt("gui.title.top"));
        wypelnijTlo(inv, screen);

        List<IslandData> top = new ArrayList<>(m.islandDatabase.values());
        top.sort((a, b) -> Double.compare(b.getWorth(), a.getWorth()));

        int[] sloty = m.gui.topkaSlotyRankingu();
        int od = page * sloty.length;
        for (int i = 0; od + i < top.size() && i < sloty.length; i++) {
            IslandData wyspa = top.get(od + i);
            int miejsce = od + i + 1;

            @SuppressWarnings("deprecation")
            OfflinePlayer ownerOffline = Bukkit.getOfflinePlayer(wyspa.getOwnerUUID());
            String ownerNick = ownerOffline.getName() != null ? ownerOffline.getName() : wyspa.getOwnerUUID().toString().substring(0, 8);
            String nazwaWyswietlana = wyspa.getCustomName() != null ? wyspa.getCustomName() : ownerNick;

            ItemStack glowa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) glowa.getItemMeta();
            meta.setOwningPlayer(ownerOffline);
            meta.displayName(m.txt(miejsce <= 3 ? "gui.top.place-" + miejsce : "gui.top.place-other",
                    "place", String.valueOf(miejsce), "name", nazwaWyswietlana));
            meta.lore(List.of(
                    m.txt("gui.top.worth", "worth", IslandTexts.kasa(wyspa.getWorth())),
                    m.txt("gui.top.size", "size", String.valueOf(wyspa.getBorderSize())),
                    m.txt("gui.top.members", "count", String.valueOf(wyspa.getMembers().size()))
            ));
            glowa.setItemMeta(meta);
            inv.setItem(sloty[i], glowa);
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        ustawStrzalki(inv, screen, page);
        player.openInventory(inv);
    }

    public void otworzMenuUlepszen(Player player) {
        otworzMenuUlepszen(player, 0);
    }

    public void otworzMenuUlepszen(Player player, int page) {
        IslandData data = m.wlasnaWyspaZUprawnieniem(player, IslandData::isMemberBankUpgrade);
        if (data == null) return;

        int currentSize = data.getBorderSize();
        int cost = m.tuning.kosztPowiekszenia(currentSize);
        boolean maksimum = currentSize >= m.tuning.borderMaxRozmiar();

        IslandScreen screen = m.gui.ulepszeniaWyspy();
        page = zacznijStrone(player, screen, page);
        Inventory inv = IslandGuiHolder.create("ulepszenia", screen.size(), m.txt("gui.title.upgrades"));
        wypelnijTlo(inv, screen);

        IslandGuiButton powieksz = widoczny(screen, "POWIEKSZ_TEREN");
        if (powieksz != null) {
            List<Component> lore = maksimum
                    ? List.of(
                            m.txt("gui.upgrades.radius", "size", String.valueOf(currentSize)),
                            Component.empty(),
                            m.txt("gui.upgrades.max")
                    )
                    : List.of(
                            m.txt("gui.upgrades.radius", "size", String.valueOf(currentSize)),
                            m.txt("gui.upgrades.cost", "cost", IslandTexts.kasa(cost)),
                            m.txt("gui.upgrades.bank", "balance", IslandTexts.kasa(data.getBankBalance())),
                            Component.empty(),
                            m.txt("gui.upgrades.click", "step", String.valueOf(m.tuning.borderPrzyrostNaUlepszenie()))
                    );
            Material material = (maksimum && powieksz.materialWylaczone() != null) ? powieksz.materialWylaczone() : powieksz.material();
            inv.setItem(powieksz.slot(), ikonaZPrzycisku(powieksz, material, lore));
        }

        // Ulepszenia za bank: limit graczy i limity bloków (bez poziomów w ustawieniach przycisku nie ma).
        IslandGuiButton czlonkowieBtn = widoczny(screen, "ULEPSZENIE_CZLONKOW");
        if (czlonkowieBtn != null && !m.tuning.ulepszeniaCzlonkow().isEmpty() && m.tuning.limitCzlonkow() > 0) {
            var poziomy = m.tuning.ulepszeniaCzlonkow();
            int poziom = data.getPoziomCzlonkow();
            List<Component> lore = new ArrayList<>();
            lore.add(m.txt("gui.upgrades.members-now", "limit", String.valueOf(m.tuning.limitCzlonkowWyspy(poziom))));
            if (poziom >= poziomy.size()) lore.add(m.txt("gui.upgrades.max-level"));
            else {
                lore.add(m.txt("gui.upgrades.members-next", "limit", String.valueOf(poziomy.get(poziom).limit())));
                lore.add(m.txt("gui.upgrades.cost", "cost", IslandTexts.kasa(poziomy.get(poziom).koszt())));
                lore.add(m.txt("gui.upgrades.bank", "balance", IslandTexts.kasa(data.getBankBalance())));
                lore.add(Component.empty());
                lore.add(m.txt("gui.upgrades.click-upgrade"));
            }
            inv.setItem(czlonkowieBtn.slot(), ikonaZPrzycisku(czlonkowieBtn, lore));
        }
        IslandGuiButton limityBtn = widoczny(screen, "ULEPSZENIE_LIMITOW");
        if (limityBtn != null && !m.tuning.ulepszeniaLimitow().isEmpty()) {
            var poziomy = m.tuning.ulepszeniaLimitow();
            int poziom = data.getPoziomLimitow();
            List<Component> lore = new ArrayList<>();
            lore.add(m.txt("gui.upgrades.limits-now", "percent", String.valueOf(m.tuning.procentLimitow(poziom))));
            if (poziom >= poziomy.size()) lore.add(m.txt("gui.upgrades.max-level"));
            else {
                lore.add(m.txt("gui.upgrades.limits-next", "percent", String.valueOf(poziomy.get(poziom).procent())));
                lore.add(m.txt("gui.upgrades.cost", "cost", IslandTexts.kasa(poziomy.get(poziom).koszt())));
                lore.add(m.txt("gui.upgrades.bank", "balance", IslandTexts.kasa(data.getBankBalance())));
                lore.add(Component.empty());
                lore.add(m.txt("gui.upgrades.click-upgrade"));
            }
            inv.setItem(limityBtn.slot(), ikonaZPrzycisku(limityBtn, lore));
        }

        IslandGuiButton spawnery = widoczny(screen, "ULEPSZENIE_SPAWNEROW");
        if (spawnery != null && saSpawnery()) {
            ItemStack itemSpawnery = ikonaZPrzycisku(spawnery, null);
            // SPAWNER jako item ma własny wanilijski dopisek w tooltipie ("Interakcja z jajem
            // przyzywającym: Ustawia typ stworzenia") - gracz o to pytał, więc jawnie go ukrywamy.
            itemSpawnery.setData(DataComponentTypes.TOOLTIP_DISPLAY,
                    TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.BLOCK_DATA));
            inv.setItem(spawnery.slot(), itemSpawnery);
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        ustawStrzalki(inv, screen, page);
        player.openInventory(inv);
    }

    /**
     * Poziomy (1-5 domyślnie, patrz wyspy-config.yml: spawnery.max-poziom) customowych
     * spawnerów mainplugins-spawners, per wyspa. Sam moduł spawnerów o tym nic nie wie
     * poza odczytem IslandSummary.spawnerLevels() - cała logika ulepszania (koszt, limit)
     * żyje tutaj, w panelu wyspy.
     */
    public void otworzMenuWzrostuDropow(Player player) {
        otworzMenuWzrostuDropow(player, 0);
    }

    public void otworzMenuWzrostuDropow(Player player, int page) {
        IslandData data = m.wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        IslandScreen screen = m.gui.ulepszenieSpawnerow();
        page = zacznijStrone(player, screen, page);
        Inventory inv = IslandGuiHolder.create("spawnery", screen.size(), m.txt("gui.title.spawners"));
        wypelnijTlo(inv, screen);

        for (Map.Entry<Integer, SpawnerTyp> wpis : spawneryNaStronie(page).entrySet()) {
            SpawnerTyp typ = wpis.getValue();
            int poziomIlosci = data.getSpawnerLevel(typ.id() + IslandManager.SUFIKS_ILOSC);
            int poziomSzybkosci = data.getSpawnerLevel(typ.id() + IslandManager.SUFIKS_SZYBKOSC);

            ItemStack item = new ItemStack(typ.ikona());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(m.txt("gui.spawners.name", "type", typ.nazwaOdmieniona()));

            String max = String.valueOf(m.tuning.spawnerMaxPoziom());
            List<Component> lore = new ArrayList<>();
            lore.add(m.txt("gui.spawners.amount", "level", String.valueOf(poziomIlosci), "max", max));
            lore.add(m.txt("gui.spawners.speed", "level", String.valueOf(poziomSzybkosci), "max", max));
            lore.add(Component.empty());
            lore.add(m.txt("gui.spawners.manage"));
            meta.lore(lore);
            item.setItemMeta(meta);

            inv.setItem(wpis.getKey(), item);
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        ustawStrzalki(inv, screen, page);
        player.openInventory(inv);
    }

    /** Podmenu jednego typu spawnera - osobne ulepszanie Ilości (mobków/cykl) i Szybkości (odstęp między cyklami). */
    public void otworzMenuUlepszenSpawnera(Player player, String typId) {
        otworzMenuUlepszenSpawnera(player, typId, 0);
    }

    public void otworzMenuUlepszenSpawnera(Player player, String typId, int page) {
        IslandData data = m.wlasnaWyspaLubKomunikat(player);
        if (data == null) return;

        SpawnerTyp typ = m.tuning.spawnerTyp(typId);
        if (typ == null) return;

        m.otwartySpawnerTyp.put(player.getUniqueId(), typId);

        IslandScreen screen = m.gui.spawnerPodmenu();
        page = zacznijStrone(player, screen, page);
        Inventory inv = IslandGuiHolder.create("spawner", screen.size(), m.txt("gui.title.spawner", "type", typ.nazwaOdmieniona()));
        wypelnijTlo(inv, screen);

        int poziomIlosci = data.getSpawnerLevel(typId + IslandManager.SUFIKS_ILOSC);
        int poziomSzybkosci = data.getSpawnerLevel(typId + IslandManager.SUFIKS_SZYBKOSC);

        IslandGuiButton iloscBtn = widoczny(screen, "ILOSC");
        if (iloscBtn != null) {
            // Ikona ILOSC zawsze bierze się z ikony aktualnie otwartego typu spawnera,
            // niezależnie od "material" w wyspy-m.gui.yml - patrz komentarz tam.
            inv.setItem(iloscBtn.slot(), itemUlepszeniaStatystyki(iloscBtn, typId, typ.ikona(), poziomIlosci, true));
        }
        IslandGuiButton szybkoscBtn = widoczny(screen, "SZYBKOSC");
        if (szybkoscBtn != null) {
            inv.setItem(szybkoscBtn.slot(), itemUlepszeniaStatystyki(szybkoscBtn, typId, szybkoscBtn.material(), poziomSzybkosci, false));
        }

        ustawPrzycisk(inv, screen, "POWROT", null);
        ustawStrzalki(inv, screen, page);
        player.openInventory(inv);
    }

    ItemStack itemUlepszeniaStatystyki(IslandGuiButton btn, String typId, Material ikona, int level, boolean ilosc) {
        boolean maksimum = level >= m.tuning.spawnerMaxPoziom();

        ItemStack item = new ItemStack(ikona);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(nazwaPrzycisku(btn));

        List<Component> lore = new ArrayList<>();
        lore.addAll(opisPrzycisku(btn));
        lore.add(m.txt("gui.spawners.level", "level", String.valueOf(level), "max", String.valueOf(m.tuning.spawnerMaxPoziom())));
        lore.add(Component.empty());
        if (maksimum) {
            lore.add(m.txt("gui.spawners.max"));
        } else {
            lore.add(m.txt("gui.spawners.cost", "cost", IslandTexts.kasa(m.tuning.kosztUlepszeniaSpawnera(typId, ilosc, level))));
            lore.add(m.txt("gui.spawners.click"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** Sloty wzorów w oknie wyboru: środek rzędów, po 7 w rzędzie. */
    private static int slotWzoru(int i) {
        return 10 + (i / 7) * 9 + (i % 7);
    }

    /** Okno wyboru wzoru wyspy - przy zakładaniu, gdy admin zapisał więcej niż jeden wzór. */
    void otworzWyborWzoru(Player player, boolean zMenu, List<elo.mainplugins.skyblock.config.IslandTuning.WzorWyspy> wzory) {
        m.otwartoZMenu.put(player.getUniqueId(), zMenu);
        int rzedy = Math.min(6, 2 + (wzory.size() + 6) / 7);
        Inventory inv = IslandGuiHolder.create("wzory", rzedy * 9, m.txt("gui.title.choose-template"));
        for (int i = 0; i < wzory.size() && slotWzoru(i) < rzedy * 9; i++) {
            var w = wzory.get(i);
            ItemStack item = new ItemStack(w.ikona());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                    .deserialize("&e&l" + w.nazwa()).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            for (String linia : w.opis()) lore.add(Component.text(linia, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(m.txt("gui.choose-template.click"));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(slotWzoru(i), item);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof IslandGuiHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String ekran = holder.ekran();
        biezaca = strona.getOrDefault(player.getUniqueId(), 0);

        boolean zMenu = m.otwartoZMenu.getOrDefault(player.getUniqueId(), false);

        if (ekran.equals("wzory")) {
            event.setCancelled(true);
            var wzory = m.dostepneWzory();
            for (int i = 0; i < wzory.size(); i++) {
                if (slotWzoru(i) == event.getRawSlot()) {
                    player.closeInventory();
                    m.utworzWyspe(player, zMenu, wzory.get(i).id());
                    return;
                }
            }
            return;
        }

        if (ekran.equals("panel")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = m.gui.panelWyspy();

            UUID panelOwnerUUID = m.playerIslandMap.get(player.getUniqueId());
            IslandData panelData = panelOwnerUUID != null ? m.islandDatabase.get(panelOwnerUUID) : null;
            boolean panelIsOwner = panelData != null && panelData.getOwnerUUID().equals(player.getUniqueId());
            int kier = kierunekStrony(screen, slot, event.getCurrentItem());
            if (kier != 0) { otworzMenuWyspy(player, zMenu, biezaca + kier); return; }

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
                        meta.displayName(m.txt("gui.panel.leave-confirm"));
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
            int kier = kierunekStrony(screen, slot, event.getCurrentItem());
            if (kier != 0) { otworzMenuPermisji(player, biezaca + kier); return; }
            if (jestSlotem(screen, "ZABIERANIE_ITEMOW", slot)) { m.przelaczZabieranieItemow(player); otworzMenuPermisji(player, biezaca); }
            else if (jestSlotem(screen, "DOSTEP_KONTENEROW", slot)) { m.przelaczDostepDoKontenerow(player); otworzMenuPermisji(player, biezaca); }
            else if (jestSlotem(screen, "INTERAKCJE", slot)) { m.przelaczInterakcje(player); otworzMenuPermisji(player, biezaca); }
            else if (jestSlotem(screen, "ROLNICTWO_GOSCI", slot)) { m.przelaczRolnictwoGosci(player); otworzMenuPermisji(player, biezaca); }
            else if (jestSlotem(screen, "WIADRA_GOSCI", slot)) { m.przelaczWiadraGosci(player); otworzMenuPermisji(player, biezaca); }
            else if (jestSlotem(screen, "CZLONKOWIE_BUDOWANIE", slot)) { m.przelaczCzlonkowieBudowanie(player); otworzMenuPermisji(player, biezaca); }
            else if (jestSlotem(screen, "CZLONKOWIE_SKRZYNIE", slot)) { m.przelaczCzlonkowieSkrzynie(player); otworzMenuPermisji(player, biezaca); }
            else if (jestSlotem(screen, "CZLONKOWIE_ZAPRASZANIE", slot)) { m.przelaczCzlonkowieZapraszanie(player); otworzMenuPermisji(player, biezaca); }
            else if (jestSlotem(screen, "CZLONKOWIE_BANK", slot)) { m.przelaczCzlonkowieBank(player); otworzMenuPermisji(player, biezaca); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (ekran.equals("ustawienia")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = m.gui.ustawieniaWyspy();
            int kier = kierunekStrony(screen, slot, event.getCurrentItem());
            if (kier != 0) { otworzMenuUstawienWyspy(player, biezaca + kier); return; }
            if (jestSlotem(screen, "WIZUALNY_BORDER", slot)) { m.przelaczWizualnyBorder(player); otworzMenuUstawienWyspy(player, biezaca); }
            else if (jestSlotem(screen, "BUDOWANIE_GOSCI", slot)) { m.przelaczBudowanieDlaGosci(player); otworzMenuUstawienWyspy(player, biezaca); }
            else if (jestSlotem(screen, "PVP", slot)) { m.przelaczPvP(player); otworzMenuUstawienWyspy(player, biezaca); }
            else if (jestSlotem(screen, "ZABIJANIE_MOBOW_GOSCI", slot)) { m.przelaczZabijanieMobowPrzezGosci(player); otworzMenuUstawienWyspy(player, biezaca); }
            else if (jestSlotem(screen, "ODWIEDZINY", slot)) { m.przelaczOdwiedziny(player); otworzMenuUstawienWyspy(player, biezaca); }
            else if (jestSlotem(screen, "NAZWA_WYSPY", slot)) { m.chat.rozpocznijZmianeNazwyWyspy(player); }
            else if (jestSlotem(screen, "CZLONKOWIE", slot)) { otworzMenuCzlonkow(player); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (ekran.equals("topka")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            int kier = kierunekStrony(m.gui.topkaWysp(), slot, event.getCurrentItem());
            if (kier != 0) { otworzMenuTopkiWysp(player, Math.max(0, strona.getOrDefault(player.getUniqueId(), 0) + kier)); return; }
            if (jestSlotem(m.gui.topkaWysp(), "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (ekran.equals("ulepszenia")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = m.gui.ulepszeniaWyspy();
            int kier = kierunekStrony(screen, slot, event.getCurrentItem());
            if (kier != 0) { otworzMenuUlepszen(player, biezaca + kier); return; }
            if (jestSlotem(screen, "POWIEKSZ_TEREN", slot)) { m.uprosGranice(player); }
            else if (jestSlotem(screen, "ULEPSZENIE_CZLONKOW", slot) && !m.tuning.ulepszeniaCzlonkow().isEmpty()) { m.ulepszCzlonkow(player); }
            else if (jestSlotem(screen, "ULEPSZENIE_LIMITOW", slot) && !m.tuning.ulepszeniaLimitow().isEmpty()) { m.ulepszLimity(player); }
            else if (saSpawnery() && jestSlotem(screen, "ULEPSZENIE_SPAWNEROW", slot)) { otworzMenuWzrostuDropow(player); }
            else if (jestSlotem(screen, "POWROT", slot)) { otworzMenuWyspy(player, zMenu); }
        }
        else if (ekran.equals("spawnery")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            IslandScreen screen = m.gui.ulepszenieSpawnerow();
            int page = biezaca;
            if (jestSlotem(screen, "POWROT", slot)) { otworzMenuUlepszen(player); return; }
            int kier = kierunekStrony(screen, slot, event.getCurrentItem());
            if (kier != 0) { otworzMenuWzrostuDropow(player, Math.max(0, page + kier)); return; }

            SpawnerTyp typ = spawneryNaStronie(page).get(slot);
            if (typ != null) otworzMenuUlepszenSpawnera(player, typ.id());
        }
        else if (ekran.equals("spawner")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            String typId = m.otwartySpawnerTyp.get(player.getUniqueId());
            if (typId == null) return;

            IslandScreen screen = m.gui.spawnerPodmenu();
            int kier = kierunekStrony(screen, slot, event.getCurrentItem());
            if (kier != 0) { otworzMenuUlepszenSpawnera(player, typId, biezaca + kier); return; }
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

            int kier = kierunekStrony(screen, slot, clicked);
            if (kier != 0) { otworzMenuCzlonkow(player, Math.max(0, strona.getOrDefault(player.getUniqueId(), 0) + kier)); return; }
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
                    otworzMenuCzlonkow(player, strona.getOrDefault(player.getUniqueId(), 0)); // odśwież listę
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
        otworzMenuCzlonkow(player, 0);
    }

    public void otworzMenuCzlonkow(Player player, int page) {
        IslandData data = m.wlasnaWyspaJakoZarzadca(player);
        if (data == null) return;

        IslandScreen screen = m.gui.czlonkowieWyspy();
        page = zacznijStrone(player, screen, page);
        Inventory inv = IslandGuiHolder.create("czlonkowie", screen.size(), m.txt("gui.title.members"));
        wypelnijTlo(inv, screen);

        boolean viewerIsOwner = data.getOwnerUUID().equals(player.getUniqueId());

        // Same główki członków, po kolei (właściciela w tym oknie nie ma).
        int[] sloty = m.gui.czlonkowieSloty();

        Map<Integer, UUID> mapaSlotow = new HashMap<>();
        List<UUID> czlonkowie = new ArrayList<>(data.getMembers());
        int od = page * sloty.length;
        int nr = 0;
        for (UUID memberUUID : czlonkowie.subList(Math.min(od, czlonkowie.size()), czlonkowie.size())) {
            if (nr >= sloty.length) break; // więcej członków niż pól na główki - reszta się nie mieści
            int slot = sloty[nr];
            if (slot < 0 || slot >= screen.size()) { nr++; continue; }

            IslandRole rola = data.getRole(memberUUID);
            boolean memberIsAdmin = rola == IslandRole.ADMIN;

            @SuppressWarnings("deprecation")
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(memberUUID);
            String nick = offlinePlayer.getName() != null ? offlinePlayer.getName() : memberUUID.toString();

            ItemStack glowa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) glowa.getItemMeta();
            meta.setOwningPlayer(offlinePlayer);
            meta.displayName(memberIsAdmin
                    ? m.txt("gui.members.admin-name", "player", nick)
                    : m.txt("gui.members.member-name", "player", nick));

            List<Component> lore = new ArrayList<>();
            lore.add(m.txt(memberIsAdmin ? "gui.members.role-admin" : "gui.members.role-member"));
            lore.add(Component.empty());
            if (viewerIsOwner) {
                lore.add(m.txt("gui.members.kick"));
                lore.add(m.txt(memberIsAdmin ? "gui.members.demote" : "gui.members.promote"));
            } else if (!memberIsAdmin) {
                lore.add(m.txt("gui.members.kick"));
            } else {
                lore.add(m.txt("gui.members.cant-manage"));
            }
            meta.lore(lore);
            glowa.setItemMeta(meta);

            inv.setItem(slot, glowa);
            mapaSlotow.put(slot, memberUUID);
            nr++;
        }
        m.slotyCzlonkow.put(player.getUniqueId(), mapaSlotow);

        ustawPrzycisk(inv, screen, "ZAPROS", null);
        ustawPrzycisk(inv, screen, "POWROT", null);
        ustawStrzalki(inv, screen, page);

        player.openInventory(inv);
    }
}
