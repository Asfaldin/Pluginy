package elo.mainplugins.core.economy;

import java.util.UUID;

/** Minimalny kontrakt "cudzych pieniędzy" (np. Vault) - pozwala testować VaultBackedEconomy bez serwera. */
public interface BalanceBackend {

    double balance(UUID uuid);

    boolean withdraw(UUID uuid, double amount);

    boolean deposit(UUID uuid, double amount);
}
