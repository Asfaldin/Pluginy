package elo.mainplugins.claims;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.license.LicenseGuard;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ochrona terenu (claimy na chunkach). Za darmo: zabezpieczanie, zaufani, ochrona przed
 * niszczeniem/budowaniem/otwieraniem, wybuchami i mobami. Z planem (licencja "claims"):
 * flagi terenu (pvp, wybuchy, moby) i wyższy limit chunków.
 */
public final class MainpluginsClaims extends JavaPlugin {

    private static final long LICENSE_RECHECK_TICKS = 30L * 60 * 20;

    private ClaimManager claims;

    @Override
    public void onEnable() {
        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        claims = new ClaimManager(this, lang, CoreAPI.getEconomyService());
        claims.reload();
        checkLicense();
        getServer().getScheduler().runTaskTimer(this, this::checkLicense, LICENSE_RECHECK_TICKS, LICENSE_RECHECK_TICKS);
        getServer().getPluginManager().registerEvents(claims, this);
        getServer().getScheduler().runTaskTimer(this, claims::save, 20L * 120, 20L * 120);

        ClaimCommand cmd = new ClaimCommand(this, claims, lang, () -> {
            lang.reload();
            claims.save();
            claims.reload();
        });
        for (String name : new String[] {"claim", "unclaim", "@claims"}) {
            PluginCommand c = getCommand(name);
            if (c != null) {
                c.setExecutor(cmd);
                c.setTabCompleter(cmd);
            }
        }
    }

    private void checkLicense() {
        boolean ok = LicenseGuard.isValid("claims", () -> CoreAPI.getLicenseService().licenseProof("claims"));
        if (ok != claims.planUnlocked()) {
            getLogger().info(ok ? "Claim flags and the higher chunk limit unlocked by your plan." : "Claim flags need a plan (Plus or higher) - protection itself works.");
        }
        claims.setPlanUnlocked(ok);
    }

    @Override
    public void onDisable() {
        if (claims != null) claims.save();
    }
}
