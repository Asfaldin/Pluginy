package elo.mainplugins.skyblock.gui;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Wczytuje wyspy-gui.yml (układ wszystkich GUI systemu wysp) do niemutowalnego
 * {@link IslandGuiContent}. Ten sam wzorzec co ShopGuiLoader w mainplugins-shop: plik
 * kopiowany z zasobu TYLKO przy pierwszym uruchomieniu, każdy zły/nieznany wpis dostaje
 * warning i jest POMIJANY zamiast crashować cały serwer przy starcie.
 */
public final class IslandGuiLoader {

    private static final Material DOMYSLNE_TLO = Material.GRAY_STAINED_GLASS_PANE;

    private IslandGuiLoader() {}

    public static IslandGuiContent load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "wyspy-gui.yml");
        if (!file.exists()) {
            plugin.saveResource("wyspy-gui.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        IslandScreen panelWyspy = parseScreen(cfg, "panel-wyspy", 54, log);
        IslandScreen permisjeWyspy = parseScreen(cfg, "permisje-wyspy", 27, log);
        IslandScreen ustawieniaWyspy = parseScreen(cfg, "ustawienia-wyspy", 45, log);
        IslandScreen topkaWysp = parseScreen(cfg, "topka-wysp", 54, log);
        IslandScreen ulepszeniaWyspy = parseScreen(cfg, "ulepszenia-wyspy", 27, log);
        IslandScreen ulepszenieSpawnerow = parseScreen(cfg, "ulepszenie-spawnerow", 54, log);
        IslandScreen spawnerPodmenu = parseScreen(cfg, "spawner-podmenu", 27, log);
        IslandScreen czlonkowieWyspy = parseScreen(cfg, "czlonkowie-wyspy", 54, log);

        int[] topkaSlotyRankingu = parseIntArray(cfg.getIntegerList("topka-wysp.sloty-rankingu"),
                new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21});
        int[] ulepszenieSpawnerowSlotyTypow = parseIntArray(cfg.getIntegerList("ulepszenie-spawnerow.sloty-typow"),
                new int[]{9, 11, 13, 15, 17, 28, 30, 32, 34});

        int czlonkowieSlotWlasciciela = cfg.getInt("czlonkowie-wyspy.slot-wlasciciela", 0);
        int czlonkowiePierwszySlot = cfg.getInt("czlonkowie-wyspy.pierwszy-slot-czlonka", 1);
        int czlonkowieOstatniSlot = cfg.getInt("czlonkowie-wyspy.ostatni-slot-czlonka", 44);

        log.info("wyspy-gui.yml: wczytano uklad 8 ekranow GUI systemu wysp.");

        return new IslandGuiContent(
                panelWyspy, permisjeWyspy, ustawieniaWyspy,
                topkaWysp, topkaSlotyRankingu,
                ulepszeniaWyspy,
                ulepszenieSpawnerow, ulepszenieSpawnerowSlotyTypow,
                spawnerPodmenu,
                czlonkowieWyspy, czlonkowieSlotWlasciciela, czlonkowiePierwszySlot, czlonkowieOstatniSlot
        );
    }

    private static IslandScreen parseScreen(YamlConfiguration cfg, String key, int domyslnyRozmiar, Logger log) {
        int size = cfg.getInt(key + ".size", domyslnyRozmiar);
        if (size < 9 || size > 54 || size % 9 != 0) {
            log.warning("wyspy-gui.yml: '" + key + ".size' = " + size + " nie jest wielokrotnoscia 9 w zakresie 9-54 - uzywam " + domyslnyRozmiar + ".");
            size = domyslnyRozmiar;
        }

        Material tlo = DOMYSLNE_TLO;
        String tloRaw = cfg.getString(key + ".tlo");
        if (tloRaw != null) {
            Material parsed = Material.matchMaterial(tloRaw);
            if (parsed != null) {
                tlo = parsed;
            } else {
                log.warning("wyspy-gui.yml: '" + key + ".tlo' ma zly material ('" + tloRaw + "') - uzywam domyslnego.");
            }
        }

        List<IslandGuiButton> przyciski = parsePrzyciski(cfg.getMapList(key + ".przyciski"), log, key, size);
        return new IslandScreen(size, tlo, przyciski);
    }

    private static List<IslandGuiButton> parsePrzyciski(List<Map<?, ?>> raw, Logger log, String context, int size) {
        List<IslandGuiButton> list = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            int slot = asInt(m.get("slot"), -1);
            if (slot < 0 || slot >= size) {
                log.warning("wyspy-gui.yml: '" + context + "' ma przycisk z nieprawidlowym slotem (" + m.get("slot") + ") - pomijam wpis.");
                continue;
            }

            Object akcjaRaw = m.get("akcja");
            if (akcjaRaw == null || String.valueOf(akcjaRaw).isBlank()) {
                log.warning("wyspy-gui.yml: '" + context + "' slot " + slot + " nie ma 'akcja' - pomijam wpis.");
                continue;
            }
            String akcja = String.valueOf(akcjaRaw);

            Object matRaw = m.get("material");
            Material material = matRaw != null ? Material.matchMaterial(String.valueOf(matRaw)) : null;
            if (material == null) {
                log.warning("wyspy-gui.yml: '" + context + "' slot " + slot + " (" + akcja + ") ma zly/brakujacy material ('" + matRaw + "') - pomijam wpis.");
                continue;
            }

            Material materialWylaczone = null;
            Object matOffRaw = m.get("material-wylaczone");
            if (matOffRaw != null) {
                materialWylaczone = Material.matchMaterial(String.valueOf(matOffRaw));
                if (materialWylaczone == null) {
                    log.warning("wyspy-gui.yml: '" + context + "' slot " + slot + " (" + akcja + ") ma zly 'material-wylaczone' ('" + matOffRaw + "') - ignoruje.");
                }
            }

            String nazwa = m.get("nazwa") != null ? String.valueOf(m.get("nazwa")) : "";

            Object kolorRaw = m.get("kolor");
            NamedTextColor kolor = kolorRaw != null ? NamedTextColor.NAMES.value(String.valueOf(kolorRaw).toLowerCase()) : null;
            if (kolor == null) {
                if (kolorRaw != null) {
                    log.warning("wyspy-gui.yml: '" + context + "' slot " + slot + " (" + akcja + ") ma zly 'kolor' ('" + kolorRaw + "') - uzywam YELLOW.");
                }
                kolor = NamedTextColor.YELLOW;
            }

            List<String> lore = new ArrayList<>();
            Object loreRaw = m.get("lore");
            if (loreRaw instanceof List<?> loreList) {
                for (Object line : loreList) lore.add(String.valueOf(line));
            }

            list.add(new IslandGuiButton(slot, akcja, material, materialWylaczone, nazwa, kolor, lore));
        }
        return list;
    }

    private static int[] parseIntArray(List<Integer> raw, int[] domyslne) {
        if (raw == null || raw.isEmpty()) return domyslne;
        int[] wynik = new int[raw.size()];
        for (int i = 0; i < raw.size(); i++) wynik[i] = raw.get(i);
        return wynik;
    }

    private static int asInt(Object raw, int fallback) {
        return raw instanceof Number n ? n.intValue() : fallback;
    }
}
