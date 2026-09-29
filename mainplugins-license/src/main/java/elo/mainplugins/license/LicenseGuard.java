package elo.mainplugins.license;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Supplier;

/**
 * Strażnik licencji WEWNĄTRZ płatnego pluginu (własna kopia w każdym jarze - shade).
 * Nie ufa samemu "true" z Core: prosi Core o podpisany dowód licencji i sam sprawdza
 * podpis kluczem publicznym wbudowanym w {@link LicenseToken}. Potem pilnuje licencji
 * w trakcie działania - wygasła albo odwołana licencja wyłącza plugin bez restartu.
 *
 * Użycie na górze {@code onEnable()}:
 * <pre>{@code
 * if (!LicenseGuard.enable(this, "crates", () -> CoreAPI.getLicenseService().licenseProof("crates"))) {
 *     getServer().getPluginManager().disablePlugin(this);
 *     return;
 * }
 * }</pre>
 */
public final class LicenseGuard {

    /** Jak często plugin ponownie sprawdza dowód od Core (Core odświeża go z serwera co 6 h). */
    private static final long RECHECK_TICKS = 30L * 60 * 20;

    private LicenseGuard() {}

    /** true = licencja potwierdzona podpisem; false = plugin powinien się wyłączyć. */
    public static boolean enable(JavaPlugin plugin, String pluginId, Supplier<String> proofSupplier) {
        if (!check(pluginId, proofSupplier)) {
            plugin.getLogger().severe("No valid license for '" + pluginId + "' - the plugin will be disabled.");
            plugin.getLogger().severe("Set the key in license.yml (MainpluginsCore data folder, 'keys: " + pluginId + ": ...') and restart the server.");
            return false;
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!check(pluginId, proofSupplier)) {
                plugin.getLogger().severe("The license for '" + pluginId + "' is no longer valid (expired or revoked) - disabling the plugin.");
                plugin.getServer().getPluginManager().disablePlugin(plugin);
            }
        }, RECHECK_TICKS, RECHECK_TICKS);
        return true;
    }

    static boolean check(String pluginId, Supplier<String> proofSupplier) {
        String proof;
        try {
            proof = proofSupplier.get();
        } catch (RuntimeException | LinkageError e) {
            // Stary Core bez licenseProof() albo Core nie działa - bez dowodu nie ma licencji.
            return false;
        }
        LicenseToken.Claims claims = LicenseToken.verifyProof(proof);
        return claims != null && claims.validFor(pluginId, System.currentTimeMillis());
    }
}
