package elo.mainplugins.coreminer;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.coreminer.config.MinerConfigLoader;
import elo.mainplugins.coreminer.config.MinerSettings;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CoreMiner - kopanie całych żył (veinminer). Darmowy plugin (bez licencji), ustawiany w aplikacji
 * (edytor CoreMiner) albo w coreminer.yml.
 *
 * /coreminer [on|off|info] - gracz włącza/wyłącza dla siebie; /@coreminer reload - admin.
 */
public final class MainpluginsCoreMiner extends JavaPlugin {

    private MinerManager manager;

    @Override
    public void onEnable() {
        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        manager = new MinerManager(this, MinerConfigLoader.load(this), lang);
        getServer().getPluginManager().registerEvents(manager, this);

        PluginCommand gracz = getCommand("coreminer");
        if (gracz != null) {
            gracz.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player p)) {
                    lang.send(sender, this, "players-only");
                    return true;
                }
                String sub = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "on", "wlacz" -> ustaw(p, true, lang);
                    case "off", "wylacz" -> ustaw(p, false, lang);
                    case "info" -> info(p, lang);
                    default -> ustaw(p, !manager.wlaczony(p), lang);
                }
                return true;
            });
            gracz.setTabCompleter((sender, command, alias, args) -> args.length == 1 ? List.of("on", "off", "info") : List.of());
        }

        PluginCommand admin = getCommand("@coreminer");
        if (admin != null) {
            admin.setExecutor((sender, command, label, args) -> {
                manager.ustawConfig(MinerConfigLoader.load(this));
                lang.send(sender, this, "reload.done");
                return true;
            });
            admin.setTabCompleter((sender, command, alias, args) -> args.length == 1 ? List.of("reload") : List.of());
        }
    }

    private void ustaw(Player p, boolean on, LangService lang) {
        manager.ustawWlaczony(p, on);
        lang.send(p, this, on ? "toggle.on" : "toggle.off");
        if (on && manager.config().ustawienia().tryb() == MinerSettings.Tryb.KUCANIE) lang.send(p, this, "toggle.sneak-hint");
    }

    private void info(Player p, LangService lang) {
        MinerSettings u = manager.config().ustawienia();
        String tryb = switch (u.tryb()) {
            case KUCANIE -> "sneak";
            case ZAWSZE -> "always";
            case PRZELACZNIK -> "toggle";
        };
        StringBuilder grupy = new StringBuilder();
        manager.config().grupy().stream().filter(g -> g.wlaczona()).forEach(g -> {
            if (!grupy.isEmpty()) grupy.append(", ");
            grupy.append(g.nazwa()).append(" (").append(manager.limitGracza(p, g)).append(')');
        });
        lang.send(p, this, "info", Map.of("state", lang.language().startsWith("pl")
                        ? (manager.wlaczony(p) ? "włączony" : "wyłączony") : (manager.wlaczony(p) ? "on" : "off"),
                "mode", lang.language().startsWith("pl") ? switch (tryb) {
                    case "sneak" -> "kucnij i kop";
                    case "always" -> "zawsze";
                    default -> "po /coreminer on";
                } : switch (tryb) {
                    case "sneak" -> "sneak and mine";
                    case "always" -> "always";
                    default -> "after /coreminer on";
                },
                "groups", grupy.isEmpty() ? "-" : grupy.toString()));
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.zamknij();
    }
}
