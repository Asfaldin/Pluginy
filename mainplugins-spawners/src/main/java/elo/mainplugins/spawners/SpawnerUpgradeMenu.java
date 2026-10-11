package elo.mainplugins.spawners;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.core.util.MoneyFormat;
import elo.mainplugins.spawners.config.SpawnerConfig;
import elo.mainplugins.spawners.config.SpawnerSettings;
import elo.mainplugins.spawners.config.SpawnerTypeDef;
import elo.mainplugins.spawners.config.UpgradeDef;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Okno ulepszeń spawnerów (/spawnery albo klik w swój spawner pustą ręką). Najpierw lista rodzajów
 * spawnerów (z poziomami w opisie), klik w rodzaj otwiera jego ulepszenia - tyle, ile administrator
 * zdefiniował w ulepszenia.lista (Ilość, Szybkość, Drop, XP...), każde kupowane osobno.
 * Płaci gracz ze swojego portfela, a poziom dotyczy wszystkich spawnerów tego rodzaju jego wyspy
 * (bez Skyblocka: jego własnych).
 */
final class SpawnerUpgradeMenu implements Listener {

    private static final int NA_STRONE = 45;
    private static final DecimalFormat LICZBA = new DecimalFormat("0.##");

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

    /** Znacznik okna: lista rodzajów (typ == null) albo ulepszenia jednego rodzaju. */
    private static final class Okno implements InventoryHolder {
        final int strona;
        final String typ;
        final Map<Integer, String> pola = new HashMap<>();
        final Map<Integer, String> przyciski = new HashMap<>();
        Inventory inv;

        Okno(int strona, String typ) {
            this.strona = strona;
            this.typ = typ;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inv;
        }
    }

    // ---- lista rodzajów ----

    void otworz(Player player, int strona) {
        SpawnerConfig cfg = manager.config();
        List<SpawnerTypeDef> wszystkie = new ArrayList<>();
        for (SpawnerTypeDef t : cfg.typy().values()) if (!cfg.ulepszeniaTypu(t).isEmpty()) wszystkie.add(t);
        if (wszystkie.isEmpty()) {
            lang.send(player, plugin, "upgrade.off");
            return;
        }
        int stron = Math.max(1, (wszystkie.size() + NA_STRONE - 1) / NA_STRONE);
        strona = Math.max(0, Math.min(strona, stron - 1));
        List<SpawnerTypeDef> tu = wszystkie.subList(strona * NA_STRONE, Math.min(wszystkie.size(), (strona + 1) * NA_STRONE));

        int rozmiar = (Math.max(1, (tu.size() + 8) / 9) + 1) * 9;
        Okno okno = new Okno(strona, null);
        okno.inv = Bukkit.createInventory(okno, rozmiar,
                txt("menu.title", Map.of("page", String.valueOf(strona + 1), "pages", String.valueOf(stron))));

        UUID wlasciciel = manager.wyspaGracza(player.getUniqueId());
        for (int i = 0; i < tu.size(); i++) {
            okno.inv.setItem(i, ikonaTypu(tu.get(i), wlasciciel, cfg));
            okno.pola.put(i, tu.get(i).id());
        }

        int dol = rozmiar - 9;
        if (strona > 0) przycisk(okno, dol, Material.ARROW, "menu.prev", "prev");
        przycisk(okno, dol + 4, Material.BARRIER, "menu.close", "close");
        if (strona < stron - 1) przycisk(okno, dol + 8, Material.ARROW, "menu.next", "next");

        player.openInventory(okno.inv);
    }

    private ItemStack ikonaTypu(SpawnerTypeDef typ, UUID wlasciciel, SpawnerConfig cfg) {
        ItemStack item = new ItemStack(typ.ikona());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(txt("menu.item-name", Map.of("type", typ.nazwaOdmieniona())));
        List<Component> lore = new ArrayList<>();
        for (UpgradeDef u : cfg.ulepszeniaTypu(typ)) {
            lore.add(txt("menu.upgrade-line", Map.of("name", u.nazwa(),
                    "level", String.valueOf(manager.poziomUlepszenia(wlasciciel, typ.id(), u)), "max", String.valueOf(u.maxPoziom()))));
        }
        lore.add(Component.empty());
        lore.add(txt("menu.open-hint", Map.of()));
        lore.add(txt("menu.click-hint", Map.of()));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    // ---- ulepszenia jednego rodzaju ----

    private void otworzTyp(Player player, String typId, int stronaListy) {
        SpawnerConfig cfg = manager.config();
        SpawnerTypeDef typ = cfg.typ(typId);
        if (typ == null) {
            otworz(player, stronaListy);
            return;
        }
        List<UpgradeDef> ulepszenia = cfg.ulepszeniaTypu(typ);
        int rozmiar = (Math.max(1, (ulepszenia.size() + 8) / 9) + 1) * 9;
        Okno okno = new Okno(stronaListy, typId);
        okno.inv = Bukkit.createInventory(okno, rozmiar, txt("menu.type-title", Map.of("type", typ.nazwaOdmieniona())));
        UUID wlasciciel = manager.wyspaGracza(player.getUniqueId());
        for (int i = 0; i < ulepszenia.size(); i++) {
            okno.inv.setItem(i, ikonaUlepszenia(typ, ulepszenia.get(i), wlasciciel, cfg.ustawienia()));
            okno.pola.put(i, ulepszenia.get(i).id());
        }
        int dol = rozmiar - 9;
        przycisk(okno, dol, Material.ARROW, "menu.back", "back");
        przycisk(okno, dol + 4, Material.BARRIER, "menu.close", "close");
        player.openInventory(okno.inv);
    }

    private ItemStack ikonaUlepszenia(SpawnerTypeDef typ, UpgradeDef u, UUID wlasciciel, SpawnerSettings s) {
        int poziom = manager.poziomUlepszenia(wlasciciel, typ.id(), u);
        ItemStack item = new ItemStack(u.ikona());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(txt("menu.upgrade-name", Map.of("name", u.nazwa())));
        List<Component> lore = new ArrayList<>();
        lore.add(txt("menu.level", Map.of("level", String.valueOf(poziom), "max", String.valueOf(u.maxPoziom()))));
        lore.add(txt("menu.effect", Map.of("value", efekt(typ, u, poziom))));
        if (poziom >= u.maxPoziom()) {
            lore.add(txt("menu.max-reached", Map.of()));
        } else {
            lore.add(txt("menu.next", Map.of("value", efekt(typ, u, poziom + 1))));
            lore.add(Component.empty());
            lore.add(txt("menu.buy", Map.of("price", MoneyFormat.pelna(u.koszt(typ.mnoznik(), poziom)))));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    /** Co daje ulepszenie na danym poziomie, np. "co 24 s", "drop ×1,5" (tekst z lang: effect.*). */
    private String efekt(SpawnerTypeDef typ, UpgradeDef u, int poziom) {
        int kupione = poziom - 1;
        String wartosc = switch (u.efekt()) {
            case ILOSC -> String.valueOf(typ.iloscNaCykl(poziom));
            case SZYBKOSC -> String.valueOf(typ.interwalSekund(poziom));
            case DROP -> LICZBA.format(Math.max(0, typ.mnoznikDropu() + u.naPoziom() * kupione));
            case XP -> LICZBA.format(u.naPoziom() * kupione);
            case MAX_NARAZ -> String.valueOf(typ.maxNaRaz() + Math.round(u.naPoziom() * kupione));
            case STOS -> String.valueOf(typ.limitKolejki() + Math.round(u.naPoziom() * kupione));
            case ZASIEG -> String.valueOf(typ.promienAktywnosci() + Math.round(u.naPoziom() * kupione));
        };
        // Tekst z lang (effect.*) bez kolorów - wstawiany do linii menu.effect / menu.next.
        return PlainTextComponentSerializer.plainText().serialize(lang.msg(plugin, "effect." + u.efekt().name().toLowerCase(java.util.Locale.ROOT), Map.of("value", wartosc)));
    }

    private void przycisk(Okno okno, int slot, Material material, String klucz, String id) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(txt(klucz, Map.of()));
        item.setItemMeta(meta);
        okno.inv.setItem(slot, item);
        okno.przyciski.put(slot, id);
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
                case "back" -> otworz(player, okno.strona);
                default -> player.closeInventory();
            }
            return;
        }

        String pole = okno.pola.get(slot);
        if (pole == null) return;
        if (okno.typ == null) otworzTyp(player, pole, okno.strona);
        else ulepsz(player, okno.typ, pole, okno.strona);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Okno) event.setCancelled(true);
    }

    private void ulepsz(Player player, String typId, String ulepszenieId, int stronaListy) {
        SpawnerConfig cfg = manager.config();
        SpawnerTypeDef typ = cfg.typ(typId);
        if (typ == null) return;
        UpgradeDef u = null;
        for (UpgradeDef x : cfg.ulepszeniaTypu(typ)) if (x.id().equals(ulepszenieId)) u = x;
        if (u == null) {
            lang.send(player, plugin, "upgrade.off");
            return;
        }

        UUID wlasciciel = manager.wyspaGracza(player.getUniqueId());
        int poziom = manager.poziomUlepszenia(wlasciciel, typId, u);
        if (poziom >= u.maxPoziom()) {
            lang.send(player, plugin, "upgrade.max");
            return;
        }
        int koszt = u.koszt(typ.mnoznik(), poziom);
        if (!ekonomia.pobierzGrosze(player.getUniqueId(), koszt * 100L)) {
            lang.send(player, plugin, "upgrade.no-money", Map.of("price", MoneyFormat.pelna(koszt)));
            return;
        }
        manager.levels().ustaw(wlasciciel, typId, u.id(), poziom + 1);
        lang.send(player, plugin, "upgrade.done",
                Map.of("upgrade", u.nazwa(), "type", typ.nazwaOdmieniona(), "level", String.valueOf(poziom + 1), "price", MoneyFormat.pelna(koszt)));
        otworzTyp(player, typId, stronaListy);
    }

    private Component txt(String klucz, Map<String, String> ph) {
        return lang.msg(plugin, klucz, ph).decoration(TextDecoration.ITALIC, false);
    }
}
