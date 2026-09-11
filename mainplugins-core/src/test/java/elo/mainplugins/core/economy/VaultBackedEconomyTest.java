package elo.mainplugins.core.economy;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VaultBackedEconomyTest {

    private static final UUID A = UUID.randomUUID();

    /** Atrapa cudzej ekonomii: trzyma saldo jako double, jak prawdziwe pluginy. */
    private static final class FakeBackend implements BalanceBackend {
        final Map<UUID, Double> balances = new HashMap<>();
        public double balance(UUID uuid) { return balances.getOrDefault(uuid, 0.0); }
        public boolean withdraw(UUID uuid, double amount) {
            if (balance(uuid) < amount) return false;
            balances.put(uuid, balance(uuid) - amount);
            return true;
        }
        public boolean deposit(UUID uuid, double amount) {
            balances.put(uuid, balance(uuid) + amount);
            return true;
        }
    }

    private final FakeBackend backend = new FakeBackend();
    private final VaultBackedEconomy economy = new VaultBackedEconomy(backend);

    @Test
    void readsBalanceInGrosze() {
        backend.balances.put(A, 12.34);
        assertEquals(1234, economy.getGrosze(A));
        assertEquals(12.34, economy.getKasa(A), 0.0001);
    }

    @Test
    void addAndSubtract() {
        economy.dodajGrosze(A, 500);
        economy.dodajGrosze(A, -200);
        assertEquals(300, economy.getGrosze(A));
    }

    @Test
    void subtractingMoreThanBalanceStopsAtZero() {
        backend.balances.put(A, 1.0);
        economy.dodajGrosze(A, -500);
        assertEquals(0, economy.getGrosze(A));
    }

    @Test
    void setGoesUpAndDownAndClampsAtZero() {
        economy.setGrosze(A, 1000);
        assertEquals(1000, economy.getGrosze(A));
        economy.setGrosze(A, 250);
        assertEquals(250, economy.getGrosze(A));
        economy.setGrosze(A, -5);
        assertEquals(0, economy.getGrosze(A));
    }

    @Test
    void takeOnlyWhenAffordable() {
        backend.balances.put(A, 5.0);
        assertFalse(economy.pobierzGrosze(A, 600));
        assertEquals(500, economy.getGrosze(A));
        assertTrue(economy.pobierzGrosze(A, 500));
        assertEquals(0, economy.getGrosze(A));
        assertTrue(economy.maWystarczajaco(A, 0));
    }

    @Test
    void rankingIsNotAvailable() {
        backend.balances.put(A, 100.0);
        assertEquals(List.of(), economy.getTop(10));
        assertEquals(-1, economy.getPozycjaWRankingu(A));
    }
}
