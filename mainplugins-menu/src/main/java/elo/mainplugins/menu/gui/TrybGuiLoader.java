package elo.mainplugins.menu.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Wczytuje tryb-gui.yml (ekran wyboru trybu gry) do niemutowalnego {@link TrybGuiContent} -
 * ten sam wzorzec co MenuGuiLoader, plik kopiowany z zasobu tylko przy pierwszym uruchomieniu.
 * W przeciwieństwie do menu-gui.yml (dowolna lista przycisków) tu układ jest CELOWO
 * sztywny - dokładnie dwie opcje (classic/mmo) - bo to nie nawigacyjne menu tylko
 * jednorazowy ekran wyboru trybu.
 */
public final class TrybGuiLoader {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();
    private static final int DOMYSLNY_ROZMIAR = 27;
    private static final Material DOMYSLNE_TLO = Material.BLACK_STAINED_GLASS_PANE;

    private TrybGuiLoader() {}

    public static TrybGuiContent load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "tryb-gui.yml");
        if (!file.exists()) {
            plugin.saveResource("tryb-gui.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        int size = cfg.getInt("size", DOMYSLNY_ROZMIAR);
        if (size < 9 || size > 54 || size % 9 != 0) {
            log.warning("tryb-gui.yml: 'size' = " + size + " nie jest wielokrotnością 9 w zakresie 9-54 - używam " + DOMYSLNY_ROZMIAR + ".");
            size = DOMYSLNY_ROZMIAR;
        }

        Material tlo = DOMYSLNE_TLO;
        String tloRaw = cfg.getString("tlo");
        if (tloRaw != null) {
            Material parsed = Material.matchMaterial(tloRaw);
            if (parsed != null) tlo = parsed;
            else log.warning("tryb-gui.yml: 'tlo' ma zły material ('" + tloRaw + "') - używam domyślnego.");
        }

        TrybOpcja classic = parseOpcja(cfg.getConfigurationSection("classic"), log, size, "classic", 11, Material.GRASS_BLOCK);
        TrybOpcja mmo = parseOpcja(cfg.getConfigurationSection("mmo"), log, size, "mmo", 15, Material.BARRIER);

        return new TrybGuiContent(size, tlo, classic, mmo);
    }

    private static TrybOpcja parseOpcja(ConfigurationSection sekcja, Logger log, int size, String klucz, int domyslnySlot, Material domyslnyMaterial) {
        if (sekcja == null) {
            log.warning("tryb-gui.yml: brak sekcji '" + klucz + "' - używam domyślnej.");
            return new TrybOpcja(domyslnySlot, domyslnyMaterial, Component.text(klucz), List.of(), false, null);
        }

        int slot = sekcja.getInt("slot", domyslnySlot);
        if (slot < 0 || slot >= size) {
            log.warning("tryb-gui.yml: '" + klucz + "' ma nieprawidłowy slot (" + slot + ") - używam " + domyslnySlot + ".");
            slot = domyslnySlot;
        }

        String matRaw = sekcja.getString("material");
        Material material = matRaw != null ? Material.matchMaterial(matRaw) : null;
        if (material == null) {
            if (matRaw != null) log.warning("tryb-gui.yml: '" + klucz + "' ma zły material ('" + matRaw + "') - używam domyślnego.");
            material = domyslnyMaterial;
        }

        String nazwaRaw = sekcja.getString("nazwa", klucz);
        Component nazwa = SERIALIZER.deserialize(nazwaRaw).decoration(TextDecoration.ITALIC, false);

        List<Component> lore = new ArrayList<>();
        for (String linia : sekcja.getStringList("lore")) {
            lore.add(SERIALIZER.deserialize(linia).decoration(TextDecoration.ITALIC, false));
        }

        boolean zablokowany = sekcja.getBoolean("zablokowany", false);
        String komunikatRaw = sekcja.getString("komunikatZablokowany");
        Component komunikat = komunikatRaw != null ? SERIALIZER.deserialize(komunikatRaw) : null;

        return new TrybOpcja(slot, material, nazwa, lore, zablokowany, komunikat);
    }
}
