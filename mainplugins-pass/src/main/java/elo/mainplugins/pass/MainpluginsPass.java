package elo.mainplugins.pass;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.license.LicenseGuard;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Season Pass. Za darmo: nagrody dzienne i za głosowanie. Przepustka sezonowa (poziomy, XP)
 * wymaga planu - licencja "pass" (plan Plus i wyższe obejmują wszystkie pluginy). Bez planu
 * plugin działa dalej, tylko okno przepustki pokazuje kłódkę.
 */
public final class MainpluginsPass extends JavaPlugin {

    /** Jak często ponownie sprawdzamy plan (Core odświeża dowód licencji co 6 h). */
    private static final long LICENSE_RECHECK_TICKS = 30L * 60 * 20;

    private PassManager pass;

    @Override
    public void onEnable() {
        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        pass = new PassManager(this, lang, CoreAPI.getRewardService(), CoreAPI.getItemNameService());
        pass.reload();
        checkLicense();
        getServer().getScheduler().runTaskTimer(this, this::checkLicense, LICENSE_RECHECK_TICKS, LICENSE_RECHECK_TICKS);

        getServer().getPluginManager().registerEvents(pass, this);
        // XP za czas gry - co minutę; zapis postępu - co 5 minut (i przy wyłączaniu).
        getServer().getScheduler().runTaskTimer(this, pass::tickPlaytime, 20L * 60, 20L * 60);
        getServer().getScheduler().runTaskTimer(this, pass::save, 20L * 300, 20L * 300);

        PassCommand cmd = new PassCommand(this, pass, lang, () -> {
            lang.reload();
            pass.reload();
        });
        for (String name : new String[] {"pass", "daily", "@pass"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(cmd);
                c.setTabCompleter(cmd);
            }
        }

        // %mainplugins_pass_level%, %mainplugins_pass_xp%, %mainplugins_daily_streak%
        CoreAPI.getPlaceholderService().register(this, (player, name) -> {
            if (player == null) return null;
            PlayerStore.Data d = pass.data(player.getUniqueId());
            return switch (name) {
                case "pass_level" -> String.valueOf(pass.level(d));
                case "pass_xp" -> String.valueOf(d.xp);
                case "daily_streak" -> String.valueOf(d.streak);
                default -> null;
            };
        });
    }

    private void checkLicense() {
        boolean ok = LicenseGuard.isValid("pass", () -> CoreAPI.getLicenseService().licenseProof("pass"));
        if (ok != pass.passUnlocked()) {
            getLogger().info(ok ? "Season pass unlocked by your plan." : "Season pass needs a plan (Plus or higher) - daily and vote rewards still work.");
        }
        pass.setPassUnlocked(ok);
    }

    @Override
    public void onDisable() {
        if (pass != null) pass.save();
    }
}
