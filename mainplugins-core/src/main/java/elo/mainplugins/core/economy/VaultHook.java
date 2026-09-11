package elo.mainplugins.core.economy;

import elo.mainplugins.core.api.EconomyService;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JEDYNE miejsce (obok VaultEconomyProvider) z klasami Vault - wołane tylko, gdy
 * plugin Vault jest włączony, więc bez niego core nie dostaje NoClassDefFoundError.
 */
public final class VaultHook {

    private VaultHook() {}

    /** Tryb "own": nasza ekonomia jako Vault Economy (priorytet High - wygrywa z np. EssentialsX). */
    public static void registerProvider(Plugin core, EconomyService economy) {
        Bukkit.getServicesManager().register(Economy.class, new VaultEconomyProvider(economy), core, ServicePriority.High);
        core.getLogger().info("Vault found - Mainplugins economy is now available to other plugins.");
    }

    /**
     * Tryb "vault": cudza ekonomia szukana przy KAŻDYM wywołaniu - plugin z pieniędzmi
     * (np. EssentialsX) może się włączyć później niż core. Brak = saldo 0 + jedno ostrzeżenie.
     */
    public static BalanceBackend backend(Plugin core) {
        AtomicBoolean warned = new AtomicBoolean();
        return new BalanceBackend() {
            private Economy economy() {
                RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
                if (rsp == null && warned.compareAndSet(false, true)) {
                    core.getLogger().warning("economy: vault is set, but no plugin provides a Vault economy (e.g. EssentialsX) - balances read as 0.");
                }
                return rsp == null ? null : rsp.getProvider();
            }

            @Override
            public double balance(UUID uuid) {
                Economy e = economy();
                return e == null ? 0 : e.getBalance(Bukkit.getOfflinePlayer(uuid));
            }

            @Override
            public boolean withdraw(UUID uuid, double amount) {
                Economy e = economy();
                return e != null && e.withdrawPlayer(Bukkit.getOfflinePlayer(uuid), amount).transactionSuccess();
            }

            @Override
            public boolean deposit(UUID uuid, double amount) {
                Economy e = economy();
                return e != null && e.depositPlayer(Bukkit.getOfflinePlayer(uuid), amount).transactionSuccess();
            }
        };
    }
}
