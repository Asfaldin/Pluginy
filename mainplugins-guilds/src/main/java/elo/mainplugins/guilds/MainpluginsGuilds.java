package elo.mainplugins.guilds;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.license.LicenseGuard;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Gildie. Za darmo: zakładanie, członkowie, role, czat, dom, ochrona przed własną gildią.
 * Z planem (licencja "guilds" - plan Plus i wyższe): bank gildii, sojusze, wyższy limit członków.
 */
public final class MainpluginsGuilds extends JavaPlugin {

    private static final long LICENSE_RECHECK_TICKS = 30L * 60 * 20;

    private GuildManager guilds;

    @Override
    public void onEnable() {
        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        guilds = new GuildManager(this, lang, CoreAPI.getEconomyService());
        guilds.reload();
        checkLicense();
        getServer().getScheduler().runTaskTimer(this, this::checkLicense, LICENSE_RECHECK_TICKS, LICENSE_RECHECK_TICKS);
        getServer().getPluginManager().registerEvents(guilds, this);
        getServer().getScheduler().runTaskTimer(this, guilds::save, 20L * 120, 20L * 120);

        GuildCommand cmd = new GuildCommand(this, guilds, lang, () -> {
            lang.reload();
            guilds.save();
            guilds.reload();
        });
        for (String name : new String[] {"g", "@guilds"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(cmd);
                c.setTabCompleter(cmd);
            }
        }

        // %mainplugins_guild_tag%, %mainplugins_guild_name%, %mainplugins_guild_role%
        CoreAPI.getPlaceholderService().register(this, (player, name) -> {
            if (player == null) return null;
            Guild g = guilds.store().of(player.getUniqueId());
            return switch (name) {
                case "guild_tag" -> g == null ? "" : g.tag;
                case "guild_name" -> g == null ? "" : g.name;
                case "guild_role" -> g == null ? "" : g.role(player.getUniqueId()).name().toLowerCase();
                default -> null;
            };
        });
    }

    private void checkLicense() {
        boolean ok = LicenseGuard.isValid("guilds", () -> CoreAPI.getLicenseService().licenseProof("guilds"));
        if (ok != guilds.planUnlocked()) {
            getLogger().info(ok ? "Guild bank and alliances unlocked by your plan." : "Guild bank and alliances need a plan (Plus or higher).");
        }
        guilds.setPlanUnlocked(ok);
    }

    @Override
    public void onDisable() {
        if (guilds != null) guilds.save();
    }
}
