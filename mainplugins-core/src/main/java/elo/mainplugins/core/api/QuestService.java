package elo.mainplugins.core.api;

import java.util.UUID;

/**
 * Opcjonalny kontrakt postępu questowego - implementuje go i rejestruje w ServicesManager
 * wyłącznie mainplugins-quests. Ten sam wzorzec co {@link IslandService}: reszta ekosystemu
 * pobiera go przez {@link elo.mainplugins.core.CoreAPI#getQuestService()}, które zwraca
 * null, jeśli mainplugins-quests nie jest wgrany/włączony - wołający musi mieć na to
 * sensowny fallback.
 */
public interface QuestService {

    /** Czy gracz ukończył dany quest (po id) Głównej Ścieżki - np. pod bramki typu "quest X odblokowuje Y" w innych modułach. */
    boolean ukonczylGlownaSciezke(UUID uuid, int questId);
}