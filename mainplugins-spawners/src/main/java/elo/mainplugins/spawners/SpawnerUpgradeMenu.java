package elo.mainplugins.spawners;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.util.MoneyFormat;
import elo.mainplugins.spawners.config.SpawnerSettings;
import elo.mainplugins.spawners.config.SpawnerTypeDef;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Okno ulepszeń spawnerów (/spawnery albo klik w swój spawner pustą ręką). Każdy rodzaj spawnera
 * to jedna ikona: LPM ulepsza Ilość, PPM Szybkość. Płaci gracz ze swojego portfela, a poziom
 * dotyczy wszystkich spawnerów tego rodzaju jego wyspy (bez Skyblocka: jego własnych).
 */
final class SpawnerUpgradeMenu implements Listener {

    private static final int NA_STRONE = 45;

    private final Plugin plugin;
    private final SpawnerManager manager;
    private final LangService lang;
    private final EconomyService ekonomia;

    SpawnerUpgradeMenu(Plugin plugin, SpawnerManager manager, LangService lang, EconomyService ekonomia) {
        this.plugin = plugin;
        this.manager = manager;
        this.lang = lang;
        this.ekonomia = ekonomia;
    }

    /** Znacznik okna + który rodzaj spawnera leży na którym polu. */
    private static final class Okno implements InventoryHolder {
        final int strona;
        final Map<Integer, String> typy = new HashMap<>();
        final Map<Integer, String> przyciski = new HashMap<>();
        Inventory inv;

        Okno(int strona) {
            this.strona = strona;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    void otworz(Player player, int strona) {
        SpawnerSettings u = manager.config().ustawienia();
        if (!u.ulepszeniaWlaczone() || u.maxPoziom() <= 1) {
            lang.send(player, plugin, "upgrade.off");
            return;
        }
        List<SpawnerTypeDef> wszystkie = new ArrayList<>(manager.config().typy().values());
        int stron = Math.max(1, (wszystkie.size() + NA_STRONE - 1) / NA_STRONE);
        strona = Math.max(0, Math.min(strona, stron - 1));
        List<SpawnerTypeDef> tu = wszystkie.subList(strona * NA_STRONE, Math.min(wszystkie.size(), (strona + 1) * NA_STRONE));

        int rzedyTypow = Math.max(1, (tu.size() + 8) / 9);
        int rozmiar = (rzedyTypow + 1) * 9;
        Okno okno = new Okno(strona);
        okno.inv = Bukkit.createInventory(okno, rozmiar,
                txt("menu.title", Map.of("page", String.valueOf(strona + 1), "pages", String.valueOf(stron))));

        UUID wlasciciel = manager.wyspaGracza(player.getUniqueId());
        for (int i = 0; i < tu.size(); i++) {
            okno.inv.setItem(i, ikona(tu.get(i), wlasciciel, u));
            okno.typy.put(i, tu.get(i).id());
        }

        int dol = rozmiar - 9;
        if (strona > 0) przycisk(okno, dol, Material.ARROW, "menu.prev", "prev");
        przycisk(okno, dol + 4, Material.BARRIER, "menu.close", "close");
        if (strona < stron - 1) przycisk(okno, dol + 8, Material.ARROW, "menu.next", "next");

        player.openInventory(okno.inv);
    }

    private void przycisk(Okno okno, int slot, Material material, String klucz, String id) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(txt(klucz, Map.of()));
        item.setItemMeta(meta);
        okno.inv.setItem(slot, item);
        okno.przyciski.put(slot, id);
    }

    private ItemStack ikona(SpawnerTypeDef typ, UUID wlasciciel, SpawnerSettings u) {
        int ilosc = manager.levels().poziom(wlasciciel, typ.id(), SpawnerLevels.Rodzaj.ILOSC);
        int szybkosc = manager.levels().poziom(wlasciciel, typ.id(), SpawnerLevels.Rodzaj.SZYBKOSC);
        String max = String.valueOf(u.maxPoziom());

        ItemStack item = new ItemStack(typ.ikona());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(txt("menu.item-name", Map.of("type", typ.nazwaOdmieniona())));
        List<Component> lore = new ArrayList<>();
        lore.add(txt("menu.amount", Map.of("level", String.valueOf(ilosc), "max", max, "mobs", String.valueOf(u.iloscNaCykl(ilosc)))));
        lore.add(ilosc >= u.maxPoziom()
                ? txt("menu.max-reached", Map.of())
                : txt("menu.amount-price", Map.of("price", MoneyFormat.pelna(u.kosztUlepszenia(typ.mnoznik(), true, ilosc)))));
        lore.add(Component.empty());
        lore.add(txt("menu.speed", Map.of("level", String.valueOf(szybkosc), "max", max, "seconds", String.valueOf(u.interwalSekund(szybkosc)))));
        lore.add(szybkosc >= u.maxPoziom()
                ? txt("menu.max-reached", Map.of())
                : txt("menu.speed-price", Map.of("price", MoneyFormat.pelna(u.kosztUlepszenia(typ.mnoznik(), false, szybkosc)))));
        lore.add(Component.empty());
        lore.add(txt("menu.click-hint", Map.of()));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Okno okno)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getInventory()) return;
        int slot = event.getRawSlot();

        String przycisk = okno.przyciski.get(slot);
        if (przycisk != null) {
            switch (przycisk) {
                case "prev" -> otworz(player, okno.strona - 1);
                case "next" -> otworz(player, okno.strona + 1);
                default -> player.closeInventory();
            }
            return;
        }

        String typId = okno.typy.get(slot);
        if (typId == null) return;
        ClickType klik = event.getClick();
        if (klik.isLeftClick()) ulepsz(player, typId, SpawnerLevels.Rodzaj.ILOSC, okno.strona);
        else if (klik.isRightClick()) ulepsz(player, typId, SpawnerLevels.Rodzaj.SZYBKOSC, okno.strona);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Okno) event.setCancelled(true);
    }

    private void ulepsz(Player player, String typId, SpawnerLevels.Rodzaj rodzaj, int strona) {
        SpawnerSettings u = manager.config().ustawienia();
        SpawnerTypeDef typ = manager.config().typ(typId);
        if (typ == null || !u.ulepszeniaWlaczone()) return;

        UUID wlasciciel = manager.wyspaGracza(player.getUniqueId());
        int poziom = manager.levels().poziom(wlasciciel, typId, rodzaj);
        if (poziom >= u.maxPoziom()) {
            lang.send(player, plugin, "upgrade.max");
            return;
        }
        int koszt = u.kosztUlepszenia(typ.mnoznik(), rodzaj == SpawnerLevels.Rodzaj.ILOSC, poziom);
        if (!ekonomia.pobierzGrosze(player.getUniqueId(), koszt * 100L)) {
            lang.send(player, plugin, "upgrade.no-money", Map.of("price", MoneyFormat.pelna(koszt)));
            return;
        }
        manager.levels().ustaw(wlasciciel, typId, rodzaj, poziom + 1);
        lang.send(player, plugin, rodzaj == SpawnerLevels.Rodzaj.ILOSC ? "upgrade.done-amount" : "upgrade.done-speed",
                Map.of("type", typ.nazwaOdmieniona(), "level", String.valueOf(poziom + 1), "price", MoneyFormat.pelna(koszt)));
        otworz(player, strona);
    }

    private Component txt(String klucz, Map<String, String> ph) {
        return lang.msg(plugin, klucz, ph).decoration(TextDecoration.ITALIC, false);
    }
}
