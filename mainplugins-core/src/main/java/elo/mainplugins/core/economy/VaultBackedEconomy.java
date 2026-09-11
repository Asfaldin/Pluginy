package elo.mainplugins.core.economy;

import elo.mainplugins.core.api.EconomyService;
import elo.mainplugins.core.api.TopGracz;

import java.util.List;
import java.util.UUID;

/**
 * EconomyService na cudzych pieniądzach (tryb "economy: vault"). Wszystko liczone w
 * groszach jak w EconomyManager. Ranking najbogatszych w tym trybie nie istnieje -
 * Vault go nie udostępnia (getTop pusty, pozycja -1), świadoma decyzja ze spec.
 */
public final class VaultBackedEconomy implements EconomyService {

    private final BalanceBackend backend;

    public VaultBackedEconomy(BalanceBackend backend) {
        this.backend = backend;
    }

    @Override
    public long getGrosze(UUID uuid) {
        return Math.round(backend.balance(uuid) * 100);
    }

    @Override
    public void setGrosze(UUID uuid, long grosze) {
        long roznica = Math.max(0, grosze) - getGrosze(uuid);
        if (roznica > 0) backend.deposit(uuid, roznica / 100.0);
        else if (roznica < 0) backend.withdraw(uuid, -roznica / 100.0);
    }

    @Override
    public void dodajGrosze(UUID uuid, long grosze) {
        if (grosze > 0) {
            backend.deposit(uuid, grosze / 100.0);
        } else if (grosze < 0) {
            long doZabrania = Math.min(-grosze, getGrosze(uuid));
            if (doZabrania > 0) backend.withdraw(uuid, doZabrania / 100.0);
        }
    }

    @Override
    public boolean pobierzGrosze(UUID uuid, long grosze) {
        if (grosze <= 0) return true;
        if (getGrosze(uuid) < grosze) return false;
        return backend.withdraw(uuid, grosze / 100.0);
    }

    @Override
    public double getKasa(UUID uuid) {
        return getGrosze(uuid) / 100.0;
    }

    @Override
    public void setKasa(UUID uuid, double ilosc) {
        setGrosze(uuid, Math.round(ilosc * 100));
    }

    @Override
    public void dodajKase(UUID uuid, double ilosc) {
        dodajGrosze(uuid, Math.round(ilosc * 100));
    }

    @Override
    public void odejmijKase(UUID uuid, double ilosc) {
        dodajGrosze(uuid, -Math.round(ilosc * 100));
    }

    @Override
    public boolean maWystarczajaco(UUID uuid, double ilosc) {
        return getGrosze(uuid) >= Math.round(ilosc * 100);
    }

    @Override
    public List<TopGracz> getTop(int limit) {
        return List.of();
    }

    @Override
    public int getPozycjaWRankingu(UUID uuid) {
        return -1;
    }
}
