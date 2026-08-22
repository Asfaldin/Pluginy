package elo.mainplugins.shop;

import elo.mainplugins.shop.gui.ScreenLayout;
import elo.mainplugins.shop.gui.ShopGuiContent;
import elo.mainplugins.shop.gui.ShopSlotEntry;
import elo.mainplugins.shop.gui.ShopSlotRole;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje sklep-gui.yml (układ/rozmiar GUI sklepu - menu główne, strona kategorii, wybór
 * ilości, wyniki wyszukiwania) do niemutowalnego {@link ShopGuiContent}. Ten sam wzorzec co
 * QuestContentLoader w mainplugins-quests: plik kopiowany z zasobu TYLKO przy pierwszym
 * uruchomieniu, każdy zły/nieznany wpis dostaje warning i jest POMIJANY zamiast crashować
 * cały serwer przy starcie.
 */
final class ShopGuiLoader {

    private static final int DOMYSLNY_ROZMIAR = 54;

    private ShopGuiLoader() {}

    static ShopGuiContent load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "sklep-gui.yml");
        if (!file.exists()) {
            plugin.saveResource("sklep-gui.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        List<String> categoryOrder = cfg.getStringList("category-order");
        ScreenLayout mainMenu = parseScreen(cfg, "main-menu", log);
        ScreenLayout categoryPage = parseScreen(cfg, "category-page", log);
        ScreenLayout buyPicker = parseScreen(cfg, "buy-picker", log);
        ScreenLayout searchResults = parseScreen(cfg, "search-results", log);

        log.info("sklep-gui.yml: wczytano uklad GUI (" + categoryOrder.size() + " kategorii w kolejnosci).");
        return new ShopGuiContent(categoryOrder, mainMenu, categoryPage, buyPicker, searchResults);
    }

    private static ScreenLayout parseScreen(YamlConfiguration cfg, String key, Logger log) {
        int size = cfg.getInt(key + ".size", DOMYSLNY_ROZMIAR);
        if (size < 9 || size > 54 || size % 9 != 0) {
            log.warning("sklep-gui.yml: '" + key + ".size' = " + size + " nie jest wielokrotnoscia 9 w zakresie 9-54 - uzywam " + DOMYSLNY_ROZMIAR + ".");
            size = DOMYSLNY_ROZMIAR;
        }
        List<ShopSlotEntry> layout = parseLayout(cfg.getMapList(key + ".layout"), log, key, size);
        return new ScreenLayout(size, layout);
    }

    private static List<ShopSlotEntry> parseLayout(List<Map<?, ?>> raw, Logger log, String context, int size) {
        List<ShopSlotEntry> list = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            int slot = asInt(m.get("slot"), -1);
            if (slot < 0 || slot >= size) {
                log.warning("sklep-gui.yml: '" + context + "' ma layout z nieprawidlowym slotem (" + m.get("slot") + ") - pomijam wpis.");
                continue;
            }
            ShopSlotRole role;
            try {
                role = ShopSlotRole.valueOf(String.valueOf(m.get("role")));
            } catch (IllegalArgumentException e) {
                log.warning("sklep-gui.yml: '" + context + "' slot " + slot + " ma nieznana role ('" + m.get("role") + "') - pomijam wpis.");
                continue;
            }
            Material material = null;
            Object matRaw = m.get("material");
            if (matRaw != null) {
                material = Material.matchMaterial(String.valueOf(matRaw));
                if (material == null) {
                    log.warning("sklep-gui.yml: '" + context + "' slot " + slot + " ma zly material ('" + matRaw + "') - uzywam domyslnego.");
                }
            }
            Integer amount = m.get("amount") instanceof Number n ? n.intValue() : null;
            if (role == ShopSlotRole.AMOUNT_SLOT && (amount == null || amount <= 0)) {
                log.warning("sklep-gui.yml: '" + context + "' slot " + slot + " to AMOUNT_SLOT bez poprawnego 'amount' - pomijam wpis.");
                continue;
            }
            list.add(new ShopSlotEntry(slot, role, material, amount));
        }
        return list;
    }

    private static int asInt(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }
}
