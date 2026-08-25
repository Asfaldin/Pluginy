package elo.mainplugins.quests.generator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parser generatory.yml -> Map&lt;id, GeneratorDefinition&gt; - wzorem EvolvingToolLoader/CustomItemManager: zły wpis jest pomijany z ostrzeżeniem, nie wywala reszty. */
public final class GeneratorLoader {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private GeneratorLoader() {}

    public static Map<String, GeneratorDefinition> load(Plugin plugin) {
        File plik = new File(plugin.getDataFolder(), "generatory.yml");
        if (!plik.exists()) {
            plugin.saveResource("generatory.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);

        Map<String, GeneratorDefinition> wynik = new LinkedHashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection("generatory");
        if (sekcja == null) return wynik;

        for (String id : sekcja.getKeys(false)) {
            GeneratorDefinition def = wczytajWpis(plugin, cfg, id);
            if (def != null) wynik.put(id, def);
        }
        return wynik;
    }

    private static GeneratorDefinition wczytajWpis(Plugin plugin, FileConfiguration cfg, String id) {
        String path = "generatory." + id + ".";

        TrybGeneratora tryb = TrybGeneratora.zNazwy(cfg.getString(path + "tryb"), null);
        if (tryb == null) {
            plugin.getLogger().warning("generatory.yml: pomijam '" + id + "' - brak/zły tryb.");
            return null;
        }

        Material materialGeneratora = Material.matchMaterial(String.valueOf(cfg.getString(path + "material-generatora")));
        if (materialGeneratora == null) {
            plugin.getLogger().warning("generatory.yml: pomijam '" + id + "' - brak/zły material-generatora.");
            return null;
        }

        Material materialBazowe = null;
        String bazoweRaw = cfg.getString(path + "material-bazowe");
        if (bazoweRaw != null) materialBazowe = Material.matchMaterial(bazoweRaw);
        if (tryb == TrybGeneratora.PRZEPUSZCZAJACY && materialBazowe == null) {
            plugin.getLogger().warning("generatory.yml: pomijam '" + id + "' - tryb PRZEPUSZCZAJACY wymaga material-bazowe.");
            return null;
        }

        WymaganeNarzedzie narzedzie = WymaganeNarzedzie.zNazwy(cfg.getString(path + "narzedzie"), WymaganeNarzedzie.PICKAXE);
        long odnowaTickow = Math.max(1, cfg.getLong(path + "odnowa-tickow", 15));

        String nazwaRaw = cfg.getString(path + "nazwa", id);
        Component nazwa = SERIALIZER.deserialize(nazwaRaw).decoration(TextDecoration.ITALIC, false);

        List<Component> lore = cfg.getStringList(path + "lore").stream()
                .map(linia -> (Component) SERIALIZER.deserialize(linia).decoration(TextDecoration.ITALIC, false))
                .toList();

        List<GeneratorDrop> bazaDropy = wczytajDropy(cfg.getMapList(path + "baza-dropy"));
        List<GeneratorDrop> bonusDropy = wczytajDropy(cfg.getMapList(path + "bonus-dropy"));

        if (tryb == TrybGeneratora.BEZPOSREDNI && bazaDropy.isEmpty()) {
            plugin.getLogger().warning("generatory.yml: pomijam '" + id + "' - tryb BEZPOSREDNI wymaga niepustej baza-dropy.");
            return null;
        }

        return new GeneratorDefinition(id, tryb, materialGeneratora, materialBazowe, narzedzie, odnowaTickow, nazwa, lore, bazaDropy, bonusDropy);
    }

    private static List<GeneratorDrop> wczytajDropy(List<Map<?, ?>> wpisy) {
        List<GeneratorDrop> lista = new ArrayList<>();
        for (Map<?, ?> wpis : wpisy) {
            Material material = Material.matchMaterial(String.valueOf(wpis.get("material")));
            if (material == null) continue;
            Object szansaRaw = wpis.get("szansa-procent");
            Double szansa = szansaRaw instanceof Number n ? n.doubleValue() : null;
            int iloscMin = liczbaInt(wpis.get("ilosc-min"), 1);
            int iloscMax = liczbaInt(wpis.get("ilosc-max"), iloscMin);
            lista.add(new GeneratorDrop(material, szansa, iloscMin, iloscMax));
        }
        return lista;
    }

    private static int liczbaInt(Object wartosc, int domyslna) {
        return wartosc instanceof Number n ? n.intValue() : domyslna;
    }
}
