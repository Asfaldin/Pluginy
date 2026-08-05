package elo.mainplugins.core.api;

import java.util.UUID;

/**
 * Opcjonalny kontrakt rang - implementuje go i rejestruje w ServicesManager
 * wyłącznie mainplugins-ranks. Ten sam wzorzec co {@link IslandService}/
 * {@link ToolsService}: reszta ekosystemu pobiera go przez
 * {@link elo.mainplugins.core.CoreAPI#getRankService()}, które zwraca null,
 * jeśli mainplugins-ranks nie jest wgrany/włączony - wołający musi mieć na to
 * sensowny fallback (najczęściej: traktować gracza jako {@link Rank#GRACZ}).
 */
public interface RankService {

    /** Nigdy nie zwraca null - gracz bez zapisanej rangi to zwykły {@link Rank#GRACZ}. */
    Rank getRank(UUID uuid);
}
