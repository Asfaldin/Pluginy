package elo.mainplugins.menu.gui;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje menu-gui.yml (układ Głównego Menu Serwera) do niemutowalnego {@link MenuGuiContent}.
 * Ten sam wzorzec co ShopGuiLoader w mainplugins-shop / QuestContentLoader w mainplugins-quests:
 * plik kopiowany z zasobu TYLKO przy pierwszym uruchomieniu, każdy zły/nieznany wpis dostaje
 * warning i jest POMIJANY zamiast crashować cały serwer przy starcie.
 */
public final class MenuGuiLoader {

    private static final int DOMYSLNY_ROZMIAR = 45;
    private static final Material DOMYSLNE_TLO = Material.GRAY_STAINED_GLASS_PANE;

    private MenuGuiLoader() {}

    public static MenuGuiContent load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "menu-gui.yml");
        if (!file.exists()) {
            plugin.saveResource("menu-gui.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        int size = cfg.getInt("size", DOMYSLNY_ROZMIAR);
        if (size < 9 || size > 54 || size % 9 != 0) {
            log.warning("menu-gui.yml: 'size' = " + size + " nie jest wielokrotnoscia 9 w zakresie 9-54 - uzywam " + DOMYSLNY_ROZMIAR + ".");
            size = DOMYSLNY_ROZMIAR;
        }

        Material tlo = DOMYSLNE_TLO;
        String tloRaw = cfg.getString("tlo");
        if (tloRaw != null) {
            Material parsed = Material.matchMaterial(tloRaw);
            if (parsed != null) {
                tlo = parsed;
            } else {
                log.warning("menu-gui.yml: 'tlo' ma zly material ('" + tloRaw + "') - uzywam domyslnego.");
            }
        }

        List<MenuButton> przyciski = parsePrzyciski(cfg.getMapList("przyciski"), log, size);

        log.info("menu-gui.yml: wczytano uklad menu (" + przyciski.size() + " przyciskow).");
        return new MenuGuiContent(size, tlo, przyciski);
    }

    private static List<MenuButton> parsePrzyciski(List<Map<?, ?>> raw, Logger log, int size) {
        List<MenuButton> list = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            int slot = asInt(m.get("slot"), -1);
            if (slot < 0 || slot >= size) {
                log.warning("menu-gui.yml: przycisk z nieprawidlowym slotem (" + m.get("slot") + ") - pomijam wpis.");
                continue;
            }

            Object matRaw = m.get("material");
            Material material = matRaw != null ? Material.matchMaterial(String.valueOf(matRaw)) : null;
            if (material == null) {
                log.warning("menu-gui.yml: slot " + slot + " ma zly/brakujacy material ('" + matRaw + "') - pomijam wpis.");
                continue;
            }

            String nazwa = m.get("nazwa") != null ? String.valueOf(m.get("nazwa")) : "";
            String komenda = m.get("komenda") != null ? String.valueOf(m.get("komenda")) : null;
            if (komenda == null || komenda.isBlank()) {
                log.warning("menu-gui.yml: slot " + slot + " ('" + nazwa + "') nie ma 'komenda' - pomijam wpis.");
                continue;
            }

            List<String> lore = new ArrayList<>();
            Object loreRaw = m.get("lore");
            if (loreRaw instanceof List<?> loreList) {
                for (Object line : loreList) lore.add(String.valueOf(line));
            }

            list.add(new MenuButton(slot, material, nazwa, lore, komenda));
        }
        return list;
    }

    private static int asInt(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }
}
