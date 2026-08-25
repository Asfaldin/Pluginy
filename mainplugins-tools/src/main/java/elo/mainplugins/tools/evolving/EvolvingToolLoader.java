package elo.mainplugins.tools.evolving;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parser ewoluujace-narzedzia.yml -> Map&lt;id, ToolDefinition&gt; - wzorem CustomItemManager
 * (mainplugins-core): wpis z brakującym/złym polem wymaganym jest POMIJANY z ostrzeżeniem
 * w konsoli, nie wywala całego wczytywania configu.
 */
public final class EvolvingToolLoader {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private EvolvingToolLoader() {}

    public static Map<String, ToolDefinition> load(Plugin plugin) {
        File plik = new File(plugin.getDataFolder(), "ewoluujace-narzedzia.yml");
        if (!plik.exists()) {
            plugin.saveResource("ewoluujace-narzedzia.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(plik);

        Map<String, ToolDefinition> wynik = new LinkedHashMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection("narzedzia");
        if (sekcja == null) return wynik;

        for (String id : sekcja.getKeys(false)) {
            ToolDefinition def = wczytajWpis(plugin, cfg, id);
            if (def != null) wynik.put(id, def);
        }
        return wynik;
    }

    private static ToolDefinition wczytajWpis(Plugin plugin, FileConfiguration cfg, String id) {
        String path = "narzedzia." + id + ".";

        Kategoria kategoria = Kategoria.zNazwy(cfg.getString(path + "kategoria"), null);
        if (kategoria == null) {
            plugin.getLogger().warning("ewoluujace-narzedzia.yml: pomijam '" + id + "' - brak/zła kategoria.");
            return null;
        }

        String matName = cfg.getString(path + "material");
        Material material = matName != null ? Material.matchMaterial(matName) : null;
        if (material == null) {
            plugin.getLogger().warning("ewoluujace-narzedzia.yml: pomijam '" + id + "' - brak/zły material ('" + matName + "').");
            return null;
        }

        String nameRaw = cfg.getString(path + "nazwa", id);
        Component nazwa = SERIALIZER.deserialize(nameRaw).decoration(TextDecoration.ITALIC, false);

        Key model = null;
        String modelRaw = cfg.getString(path + "model");
        if (modelRaw != null) {
            try {
                model = Key.key(modelRaw);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("ewoluujace-narzedzia.yml: '" + id + "' ma niepoprawny model ('" + modelRaw + "') - pomijam to pole.");
            }
        }

        boolean glint = cfg.getBoolean(path + "glint", false);
        int maxPoziom = Math.max(1, cfg.getInt(path + "max-poziom", 30));
        int expNaPoziom = Math.max(1, cfg.getInt(path + "exp-na-poziom", 50));

        List<ToolStat> staty = wczytajStaty(plugin, cfg, path + "staty", id);
        List<EnchantProgress> enchanty = wczytajEnchanty(plugin, cfg, path + "enchanty", id);
        Map<Integer, List<ToolEffect>> kamienieMilowe = wczytajKamienieMilowe(cfg, path + "kamienie-milowe");
        List<ToolEffect> pasywne = wczytajEfekty(cfg.getMapList(path + "pasywne"));
        String czastkaOtoczenia = cfg.getString(path + "czastki-otoczenia");

        return new ToolDefinition(id, kategoria, material, nazwa, model, glint, maxPoziom, expNaPoziom,
                staty, enchanty, kamienieMilowe, pasywne, czastkaOtoczenia);
    }

    private static List<ToolStat> wczytajStaty(Plugin plugin, FileConfiguration cfg, String path, String toolId) {
        List<ToolStat> lista = new ArrayList<>();
        for (Map<?, ?> wpis : cfg.getMapList(path)) {
            String id = String.valueOf(wpis.get("id"));
            Object nazwaRaw = wpis.get("nazwa");
            String nazwa = nazwaRaw != null ? String.valueOf(nazwaRaw) : id;
            double bazowa = liczba(wpis.get("bazowa"), 0);
            double naPoziom = liczba(wpis.get("na-poziom"), 0);
            double max = liczba(wpis.get("max"), Double.MAX_VALUE);

            Enchantment enchant = null;
            Object enchantRaw = wpis.get("enchant");
            if (enchantRaw != null) {
                enchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(String.valueOf(enchantRaw).toLowerCase()));
                if (enchant == null) {
                    plugin.getLogger().warning("ewoluujace-narzedzia.yml: '" + toolId + "' staty '" + id + "' ma nieznany enchant '" + enchantRaw + "' - ignoruję podpięcie.");
                }
            }
            double enchantMnoznik = liczba(wpis.get("enchant-mnoznik"), 1);

            lista.add(new ToolStat(id, nazwa, bazowa, naPoziom, max, enchant, enchantMnoznik));
        }
        return lista;
    }

    private static List<EnchantProgress> wczytajEnchanty(Plugin plugin, FileConfiguration cfg, String path, String id) {
        List<EnchantProgress> lista = new ArrayList<>();
        for (Map<?, ?> wpis : cfg.getMapList(path)) {
            String nazwaEnchantu = String.valueOf(wpis.get("enchant"));
            Enchantment enchant = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(nazwaEnchantu.toLowerCase()));
            if (enchant == null) {
                plugin.getLogger().warning("ewoluujace-narzedzia.yml: '" + id + "' ma nieznany enchant '" + nazwaEnchantu + "' - pomijam.");
                continue;
            }
            TreeMap<Integer, Integer> progresja = new TreeMap<>();
            Object progRaw = wpis.get("progresja");
            if (progRaw instanceof Map<?, ?> progMap) {
                for (var e : progMap.entrySet()) {
                    try {
                        progresja.put(Integer.parseInt(String.valueOf(e.getKey())), Integer.parseInt(String.valueOf(e.getValue())));
                    } catch (NumberFormatException ignored) {
                        // uszkodzony próg - pomijamy pojedynczy wpis, reszta progresji zostaje
                    }
                }
            }
            if (!progresja.isEmpty()) lista.add(new EnchantProgress(enchant, progresja));
        }
        return lista;
    }

    private static Map<Integer, List<ToolEffect>> wczytajKamienieMilowe(FileConfiguration cfg, String path) {
        Map<Integer, List<ToolEffect>> wynik = new TreeMap<>();
        ConfigurationSection sekcja = cfg.getConfigurationSection(path);
        if (sekcja == null) return wynik;
        for (String poziomStr : sekcja.getKeys(false)) {
            try {
                int poziom = Integer.parseInt(poziomStr);
                wynik.put(poziom, wczytajEfekty(cfg.getMapList(path + "." + poziomStr)));
            } catch (NumberFormatException ignored) {
                // nie-liczbowy klucz kamienia milowego - pomijamy
            }
        }
        return wynik;
    }

    private static List<ToolEffect> wczytajEfekty(List<Map<?, ?>> wpisy) {
        List<ToolEffect> lista = new ArrayList<>();
        for (Map<?, ?> wpis : wpisy) {
            EffectType typ;
            try {
                typ = EffectType.valueOf(String.valueOf(wpis.get("typ")));
            } catch (IllegalArgumentException e) {
                continue;
            }
            lista.add(new ToolEffect(
                    typ,
                    liczba(wpis.get("szansa-bazowa"), 0),
                    liczba(wpis.get("szansa-na-poziom"), 0),
                    liczba(wpis.get("szansa-max"), 100),
                    liczba(wpis.get("kwota-bazowa"), 0),
                    liczba(wpis.get("kwota-na-poziom"), 0),
                    wpis.get("mikstura") != null ? String.valueOf(wpis.get("mikstura")) : null,
                    (int) liczba(wpis.get("poziom-mikstury"), 0),
                    wpis.get("czastka") != null ? String.valueOf(wpis.get("czastka")) : null,
                    wpis.get("dzwiek") != null ? String.valueOf(wpis.get("dzwiek")) : null,
                    liczba(wpis.get("promien"), 4),
                    wpis.get("przedmiot-material") != null ? String.valueOf(wpis.get("przedmiot-material")) : null,
                    wpis.get("przedmiot-custom-id") != null ? String.valueOf(wpis.get("przedmiot-custom-id")) : null,
                    wczytajProgresjeLiczb(wpis.get("szansa-progresja")),
                    wczytajProgresjeLiczb(wpis.get("kwota-progresja"))
            ));
        }
        return lista;
    }

    /** Jawna progresja POZIOM -> WARTOŚĆ (double) - patrz ToolEffect#szansaProgresja/kwotaProgresja. Pusta mapa = "nieużywana", wraca do wzoru liniowego. */
    private static java.util.NavigableMap<Integer, Double> wczytajProgresjeLiczb(Object raw) {
        TreeMap<Integer, Double> progresja = new TreeMap<>();
        if (raw instanceof Map<?, ?> mapa) {
            for (var e : mapa.entrySet()) {
                try {
                    progresja.put(Integer.parseInt(String.valueOf(e.getKey())), Double.parseDouble(String.valueOf(e.getValue())));
                } catch (NumberFormatException ignored) {
                    // uszkodzony próg - pomijamy pojedynczy wpis, reszta progresji zostaje
                }
            }
        }
        return progresja;
    }

    private static double liczba(Object wartosc, double domyslna) {
        if (wartosc instanceof Number n) return n.doubleValue();
        return domyslna;
    }
}
