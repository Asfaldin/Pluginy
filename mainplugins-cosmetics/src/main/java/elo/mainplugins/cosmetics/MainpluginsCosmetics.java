package elo.mainplugins.cosmetics;

import elo.mainplugins.core.CoreAPI;
import elo.mainplugins.core.api.LangService;
import elo.mainplugins.license.LicenseGuard;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Kosmetyki. Za darmo: czapki i smugi. Z planem: czapki z custom itemów (licencja "cosmetics",
 * plan Plus) i pupile (licencja "cosmetics-pets", plan Pro i wyższe obejmują wszystkie pluginy).
 */
public final class MainpluginsCosmetics extends JavaPlugin {

    private static final long LICENSE_RECHECK_TICKS = 30L * 60 * 20;

    private CosmeticsManager cosmetics;

    @Override
    public void onEnable() {
        LangService lang = CoreAPI.getLangService();
        lang.registerDefaults(this);
        cosmetics = new CosmeticsManager(this, lang, CoreAPI.getCustomItemService());
        cosmetics.reload();
        checkLicense();
        getServer().getScheduler().runTaskTimer(this, this::checkLicense, LICENSE_RECHECK_TICKS, LICENSE_RECHECK_TICKS);
        getServer().getPluginManager().registerEvents(cosmetics, this);
        getServer().getScheduler().runTaskTimer(this, cosmetics::tickTrails, 4L, 4L);
        getServer().getScheduler().runTaskTimer(this, cosmetics::tickPets, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, cosmetics::save, 20L * 300, 20L * 300);

        PluginCommand open = getCommand("cosmetics");
        if (open != null) {
            open.setExecutor((sender, cmd, label, args) -> {
                if (sender instanceof Player p) cosmetics.open(p, Cosmetic.Type.HAT);
                else lang.send(sender, this, "error.players-only");
                return true;
            });
        }
        PluginCommand admin = getCommand("@cosmetics");
        if (admin != null) {
            admin.setExecutor((sender, cmd, label, args) -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                    lang.reload();
                    cosmetics.save();
                    cosmetics.removeAllPets();
                    cosmetics.reload();
                    getServer().getOnlinePlayers().forEach(cosmetics::restore);
                    lang.send(sender, this, "admin.reloaded");
                } else {
                    lang.send(sender, this, "admin.usage");
                }
                return true;
            });
        }
        getServer().getOnlinePlayers().forEach(cosmetics::restore);
    }

    private void checkLicense() {
        // Plan Plus i wyższe obejmują wszystkie pluginy - "cosmetics" odblokowuje czapki z custom
        // itemów; pupile sprawdzamy osobnym id, które serwer licencji wystawia od planu Pro.
        boolean plus = LicenseGuard.isValid("cosmetics", () -> CoreAPI.getLicenseService().licenseProof("cosmetics"));
        boolean pro = plus && LicenseGuard.isValid("cosmetics-pets", () -> CoreAPI.getLicenseService().licenseProof("cosmetics-pets"));
        cosmetics.setPlan(plus, pro);
    }

    @Override
    public void onDisable() {
        if (cosmetics != null) {
            cosmetics.save();
            cosmetics.removeAllPets();
            getServer().getOnlinePlayers().forEach(p -> {
                if (cosmetics.isCosmetic(p.getInventory().getHelmet())) p.getInventory().setHelmet(null);
            });
        }
    }
}
