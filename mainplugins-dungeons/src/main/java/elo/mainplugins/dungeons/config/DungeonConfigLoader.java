package elo.mainplugins.dungeons.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.logging.Logger;

/**
 * Wczytuje dungeons-config.yml do niemutowalnego {@link DungeonConfig}. Ten sam wzorzec
 * co MenuGuiLoader/SpawnerConfigLoader: plik kopiowany z zasobu TYLKO przy pierwszym
 * uruchomieniu, każda zła/brakująca wartość dostaje warning i pada na sensowny domyślny
 * odpowiednik dawnej hardkodowanej stałej, zamiast crashować cały serwer przy starcie.
 */
public final class DungeonConfigLoader {

    private DungeonConfigLoader() {}

    public static DungeonConfig load(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "dungeons-config.yml");
        if (!file.exists()) {
            plugin.saveResource("dungeons-config.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Logger log = plugin.getLogger();

        DungeonConfig.Miejsce miejsce = new DungeonConfig.Miejsce(
                cfg.getInt("miejsce.bazowy-x", 0),
                cfg.getInt("miejsce.bazowy-y", 250),
                cfg.getInt("miejsce.bazowy-z", 5000),
                cfg.getInt("miejsce.odstep-pokoi", 40),
                Math.max(0, cfg.getInt("miejsce.liczba-pokoi", 4)),
                cfg.getInt("miejsce.promien-pokoju", 6),
                material(cfg, "miejsce.material-podlogi-pokoju", Material.DEEPSLATE_TILES, log),
                material(cfg, "miejsce.material-sciany-pokoju", Material.DEEPSLATE_BRICKS, log),
                cfg.getInt("miejsce.wysokosc-sciany-pokoju", 4),
                cfg.getInt("miejsce.promien-areny-bossa", 10),
                material(cfg, "miejsce.material-podlogi-areny", Material.BLACKSTONE, log),
                material(cfg, "miejsce.material-sciany-areny", Material.POLISHED_BLACKSTONE_BRICKS, log),
                cfg.getInt("miejsce.wysokosc-sciany-areny", 5)
        );

        DungeonConfig.Pokoje pokoje = new DungeonConfig.Pokoje(
                entityType(cfg, "pokoje.encja", EntityType.ZOMBIE, log),
                cfg.getInt("pokoje.ilosc-bazowa", 3),
                cfg.getInt("pokoje.ilosc-na-pokoj", 1),
                cfg.getDouble("pokoje.hp-bazowe", 20),
                cfg.getDouble("pokoje.hp-na-pokoj", 10),
                cfg.getDouble("pokoje.obrazenia-bazowe", 3),
                cfg.getDouble("pokoje.obrazenia-na-pokoj", 1)
        );

        DungeonConfig.Boss boss = new DungeonConfig.Boss(
                entityType(cfg, "boss.encja", EntityType.PIGLIN_BRUTE, log),
                entityType(cfg, "boss.encja-slugi", EntityType.ZOMBIE, log),
                cfg.getDouble("boss.max-hp", 200),
                cfg.getDouble("boss.obrazenia-ataku", 8),
                cfg.getDouble("boss.obrazenia-pocisku", 6),
                Math.max(1, cfg.getInt("boss.okres-umiejetnosci-sekundy", 3)) * 20L,
                cfg.getDouble("boss.prog-przywolania-slug-1", 0.66),
                cfg.getDouble("boss.prog-przywolania-slug-2", 0.33),
                cfg.getDouble("boss.prog-szalu", 0.30),
                cfg.getDouble("boss.nagroda-monety", 500)
        );

        log.info("dungeons-config.yml: wczytano konfiguracje lochu/bossa.");
        return new DungeonConfig(miejsce, pokoje, boss);
    }

    private static Material material(YamlConfiguration cfg, String path, Material domyslny, Logger log) {
        String raw = cfg.getString(path);
        if (raw == null) return domyslny;
        Material parsed = Material.matchMaterial(raw);
        if (parsed == null) {
            log.warning("dungeons-config.yml: '" + path + "' ma zly material ('" + raw + "') - uzywam " + domyslny + ".");
            return domyslny;
        }
        return parsed;
    }

    private static EntityType entityType(YamlConfiguration cfg, String path, EntityType domyslny, Logger log) {
        String raw = cfg.getString(path);
        if (raw == null) return domyslny;
        try {
            return EntityType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warning("dungeons-config.yml: '" + path + "' ma zla encje ('" + raw + "') - uzywam " + domyslny + ".");
            return domyslny;
        }
    }
}
