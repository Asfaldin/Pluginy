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

    /** Jak {@link #dajEwoluujacyKilof(Player)}, ale siekiera - nagroda za quest "Timberman" Głównej Ścieżki. */
    void dajEwoluujacaSiekiere(Player player);

    /** Jak {@link #dajEwoluujacyKilof(Player)}, ale motyka - nagroda za quest "Farmimy dalej" Głównej Ścieżki. */
    void dajEwoluujacaMotyke(Player player);

    /** Jak {@link #dajEwoluujacyKilof(Player)}, ale miecz - nagroda za quest "Pierwsza Krew" Głównej Ścieżki. */
    void dajEwoluujacyMiecz(Player player);

    /**
     * Poziom (globalny, rośnie 1,2,3...) ewoluującego kilofa gracza, gdziekolwiek w
     * ekwipunku - 0, jeśli gracz w ogóle go nie ma. Kilof ma OSOBNY system poziomowania
     * (drzewko umiejętności, PickaxeSkillManager) niż reszta narzędzi - stąd osobna
     * metoda zamiast ogólnego "poziom narzędzia".
     */
    int poziomKilofa(Player player);
}