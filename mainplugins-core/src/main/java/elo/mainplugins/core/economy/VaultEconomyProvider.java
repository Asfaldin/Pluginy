package elo.mainplugins.core.economy;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.util.MoneyFormat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.UUID;

/**
 * Nasza ekonomia widziana przez Vault (tryb "economy: own") - inne pluginy (aukcje,
 * prace...) mogą czytać i zmieniać salda naszych graczy. Banki nieobsługiwane.
 * Wersje metod po nazwie gracza (deprecated w Vault) mapujemy przez OfflinePlayer.
 */
final class VaultEconomyProvider implements Economy {

    private final EconomyService economy;

    VaultEconomyProvider(EconomyService economy) {
        this.economy = economy;
    }

    private static UUID id(OfflinePlayer player) {
        return player.getUniqueId();
    }

    @SuppressWarnings("deprecation")
    private static UUID id(String name) {
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private EconomyResponse withdraw(UUID uuid, double amount) {
        if (amount < 0) return new EconomyResponse(0, economy.getKasa(uuid), EconomyResponse.ResponseType.FAILURE, "Negative amount");
        boolean ok = economy.pobierzGrosze(uuid, Math.round(amount * 100));
        return new EconomyResponse(ok ? amount : 0, economy.getKasa(uuid),
                ok ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                ok ? null : "Insufficient funds");
    }

    private EconomyResponse deposit(UUID uuid, double amount) {
        if (amount < 0) return new EconomyResponse(0, economy.getKasa(uuid), EconomyResponse.ResponseType.FAILURE, "Negative amount");
        economy.dodajGrosze(uuid, Math.round(amount * 100));
        return new EconomyResponse(amount, economy.getKasa(uuid), EconomyResponse.ResponseType.SUCCESS, null);
    }

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported");
    }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "Mainplugins"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return MoneyFormat.pelna(amount) + "$"; }
    @Override public String currencyNamePlural() { return "$"; }
    @Override public String currencyNameSingular() { return "$"; }

    @Override public boolean hasAccount(String playerName) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player) { return true; }
    @Override public boolean hasAccount(String playerName, String worldName) { return true; }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) { return true; }

    @Override public double getBalance(String playerName) { return economy.getKasa(id(playerName)); }
    @Override public double getBalance(OfflinePlayer player) { return economy.getKasa(id(player)); }
    @Override public double getBalance(String playerName, String world) { return getBalance(playerName); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }

    @Override public boolean has(String playerName, double amount) { return economy.maWystarczajaco(id(playerName), amount); }
    @Override public boolean has(OfflinePlayer player, double amount) { return economy.maWystarczajaco(id(player), amount); }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return withdraw(id(playerName), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return withdraw(id(player), amount); }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return withdrawPlayer(playerName, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdrawPlayer(player, amount); }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) { return deposit(id(playerName), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return deposit(id(player), amount); }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return depositPlayer(playerName, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return depositPlayer(player, amount); }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }

    @Override public boolean createPlayerAccount(String playerName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return true; }
}
