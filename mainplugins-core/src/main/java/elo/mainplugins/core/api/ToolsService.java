package elo.mainplugins.core.api;

import org.bukkit.entity.Player;

/**
 * Opcjonalny kontrakt narzędzi - implementuje go i rejestruje w ServicesManager
 * wyłącznie mainplugins-tools. Ten sam wzorzec co {@link IslandService}: reszta
 * ekosystemu pobiera go przez {@link elo.mainplugins.core.CoreAPI#getToolsService()},
 * które zwraca null, jeśli mainplugins-tools nie jest wgrany/włączony - wołający
 * musi mieć na to sensowny fallback.
 */
public interface ToolsService {

    /** Daje graczowi jego startowy, ewoluujący kilof (tier drewno, poziom 1) - patrz LevelableToolsManager. */
    void dajEwoluujacyKilof(Player player);
}