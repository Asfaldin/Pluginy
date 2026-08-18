package elo.mainplugins.core.api;

import java.util.UUID;

/**
 * Opcjonalny kontrakt rynku graczy - implementuje go i rejestruje w ServicesManager
 * wyłącznie mainplugins-market. Ten sam wzorzec co {@link IslandService}: reszta
 * ekosystemu pobiera go przez {@link elo.mainplugins.core.CoreAPI#getMarketService()},
 * które zwraca null, jeśli mainplugins-market nie jest wgrany/włączony - wołający musi
 * mieć na to sensowny fallback.
 */
public interface MarketService {

    /** Czy gracz ma aktualnie choć jedną AKTYWNĄ (niesprzedaną, niewycofaną) ofertę na targu. */
    boolean maAktywnaOferte(UUID uuid);
}