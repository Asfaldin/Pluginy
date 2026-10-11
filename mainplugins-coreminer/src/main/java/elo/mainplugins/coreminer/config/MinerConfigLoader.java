package elo.mainplugins.coreminer.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Wczytuje coreminer.yml. Plik kopiowany z jara tylko przy pierwszym uruchomieniu; złe wartości dostają
 * warning i domyślną wartość zamiast wywracać plugin.
 *
 * Bloki grupy można podać na trzy sposoby: dokładna nazwa (IRON_ORE), wzór z gwiazdką (*_ORE, *_LOG)
 * albo tag z gry z # (#logs, #minecraft:leaves).
 */
public final class MinerConfigLoader {

    private MinerConfigLoader() {}

    public static MinerConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "coreminer.yml");
        if (!file.exists()) plugin.saveResource("coreminer.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();
        MinerSettings u = ustawienia(cfg.getConfigurationSection("settings"), log);
        List<MinerGroup> grupy = new ArrayList<>();
        ConfigurationSection gs = cfg.getConfigurationSection("groups");
        if (gs != null) {
            for (String id : gs.getKeys(false)) {
                ConfigurationSection g = gs.getConfigurationSection(id);
                if (g != null) grupy.add(grupa(id, g, log));
            }
        }
        log.info("coreminer.yml: " + grupy.stream().filter(MinerGroup::wlaczona).count() + " block groups enabled.");
        return new MinerConfig(u, List.copyOf(grupy));
    }

    private static MinerGroup grupa(String id, ConfigurationSection g, Logger log) {
        Set<Material> bloki = new LinkedHashSet<>();
        for (String wpis : g.getStringList("blocks")) bloki.addAll(bloki(wpis, id, log));
        Set<ToolKind> narzedzia = EnumSet.noneOf(ToolKind.class);
        for (String n : g.getStringList("tools")) {
            ToolKind k = ToolKind.z(n);
            if (k == null) log.warning("coreminer.yml: group '" + id + "' has an unknown tool '" + n + "' (pickaxe, axe, shovel, hoe, shears, sword).");
            else narzedzia.add(k);
        }
        Material ikona = Material.matchMaterial(g.getString("icon", ""));
        if (ikona == null) ikona = bloki.isEmpty() ? Material.IRON_PICKAXE : bloki.iterator().next();
        String laczenie = g.getString("linking", "same").toLowerCase(Locale.ROOT);
        String perm = g.getString("permission", "");
        return new MinerGroup(id, g.getString("name", id), g.getBoolean("enabled", true), ikona, Set.copyOf(bloki), Set.copyOf(narzedzia),
                Math.max(1, g.getInt("max-blocks", 64)), g.getBoolean("diagonal", false),
                laczenie.equals("group") ? MinerGroup.Laczenie.GRUPA : MinerGroup.Laczenie.TEN_SAM,
                perm.isBlank() ? null : perm.trim());
    }

    /** Jeden wpis z listy bloków: nazwa, wzór z * albo #tag. */
    static Set<Material> bloki(String wpis, String grupa, Logger log) {
        Set<Material> out = new LinkedHashSet<>();
        String w = wpis.trim();
        if (w.isEmpty()) return out;
        if (w.startsWith("#")) {
            String key = w.substring(1).toLowerCase(Locale.ROOT);
            NamespacedKey nk = NamespacedKey.fromString(key.contains(":") ? key : "minecraft:" + key);
            Tag<Material> tag = nk == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, nk, Material.class);
            if (tag == null) log.warning("coreminer.yml: group '" + grupa + "' - there's no tag " + w + ".");
            else out.addAll(tag.getValues());
            return out;
        }
        if (w.contains("*")) {
            Pattern p = Pattern.compile("^" + Pattern.quote(w.toUpperCase(Locale.ROOT)).replace("*", "\\E.*\\Q") + "$");
            for (Material m : Material.values()) if (m.isBlock() && !m.isLegacy() && p.matcher(m.name()).matches()) out.add(m);
            if (out.isEmpty()) log.warning("coreminer.yml: group '" + grupa + "' - pattern " + w + " matches no block.");
            return out;
        }
        Material m = Material.matchMaterial(w);
        if (m == null || !m.isBlock()) log.warning("coreminer.yml: group '" + grupa + "' - unknown block " + w + ".");
        else out.add(m);
        return out;
    }

    private static MinerSettings ustawienia(ConfigurationSection s, Logger log) {
        if (s == null) s = new YamlConfiguration();
        Map<String, Integer> limity = new LinkedHashMap<>();
        ConfigurationSection lu = s.getConfigurationSection("permission-limits");
        if (lu != null) {
            // getValues(true): uprawnienie z kropkami wraca w całości, zagnieżdżone czy zapisane jako jeden klucz
            for (Map.Entry<String, Object> e : lu.getValues(true).entrySet()) {
                if (e.getValue() instanceof Number n) limity.put(e.getKey(), Math.max(1, n.intValue()));
            }
        }
        return new MinerSettings(
                enumZ(s.getString("mode", "sneak"), MinerSettings.Tryb.KUCANIE, Map.of("sneak", MinerSettings.Tryb.KUCANIE,
                        "always", MinerSettings.Tryb.ZAWSZE, "toggle", MinerSettings.Tryb.PRZELACZNIK), "mode", log),
                s.getBoolean("enabled-by-default", true),
                Math.max(1, s.getInt("max-blocks", 64)),
                Map.copyOf(limity),
                Math.max(1, s.getInt("max-distance", 16)),
                Set.copyOf(s.getStringList("enabled-worlds")),
                Set.copyOf(s.getStringList("disabled-worlds")),
                s.getBoolean("in-creative", false),
                Math.max(0, s.getDouble("durability", 1.0)),
                Math.max(0, s.getInt("protect-tool", 1)),
                Math.max(0, s.getDouble("hunger", 0.005)),
                Math.max(0, s.getDouble("cooldown-seconds", 0)),
                Math.max(0, s.getDouble("cost-per-block", 0)),
                enumZ(s.getString("drops", "natural"), MinerSettings.Drop.NATURALNIE, Map.of("natural", MinerSettings.Drop.NATURALNIE,
                        "inventory", MinerSettings.Drop.EKWIPUNEK, "first-block", MinerSettings.Drop.PIERWSZY_BLOK), "drops", log),
                s.getBoolean("auto-smelt", false),
                enumZ(s.getString("xp", "orbs"), MinerSettings.Xp.KULE, Map.of("orbs", MinerSettings.Xp.KULE,
                        "player", MinerSettings.Xp.GRACZ, "none", MinerSettings.Xp.BRAK), "xp", log),
                s.getBoolean("preview", true),
                s.getString("preview-particle", "END_ROD"),
                Math.max(1, s.getInt("preview-max", 64)),
                s.getString("sound", "block.amethyst_block.chime"),
                s.getBoolean("message", true),
                Math.max(0, s.getInt("delay-ticks", 0)),
                Math.max(1, s.getInt("blocks-per-step", 4)));
    }

    private static <E extends Enum<E>> E enumZ(String raw, E domyslna, Map<String, E> inne, String klucz, Logger log) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return domyslna;
        E e = inne.get(v);
        if (e != null) return e;
        log.warning("coreminer.yml: bad value '" + raw + "' in " + klucz + " - allowed: " + String.join(", ", inne.keySet()) + ".");
        return domyslna;
    }
}
