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

    /**
     * Daje graczowi jego startowy kilof - typ Wydajnościowy, poziom 1 (patrz
     * elo.mainplugins.tools.pickaxe.PickaxeType w mainplugins-tools). Kilof NIE ma już
     * jednej ewoluującej linii tierów (drewno→netheryt) - gracz może posiadać kilka
     * RÓŻNYCH typów naraz, każdy z własną, dedykowaną progresją.
     */
    void dajEwoluujacyKilof(Player player);

    /** Jak {@link #dajEwoluujacyKilof(Player)}, ale siekiera - nagroda za quest "Drwal i Siewca" Głównej Ścieżki. */
    void dajEwoluujacaSiekiere(Player player);

    /** Jak {@link #dajEwoluujacyKilof(Player)}, ale motyka - nagroda za quest "Rolniczy Krok" Głównej Ścieżki. */
    void dajEwoluujacaMotyke(Player player);

    /** Jak {@link #dajEwoluujacyKilof(Player)}, ale miecz - nagroda za quest "Fundusz Obronny" Głównej Ścieżki. */
    void dajEwoluujacyMiecz(Player player);

    /**
     * Jak {@link #dajEwoluujacyKilof(Player)}, ale łopata - piąte ewoluujące narzędzie,
     * przywrócone do gry jako nagroda za quest "generator kruchych surowców" Głównej
     * Ścieżki (wcześniej usunięte, patrz historia LevelableToolsManager). Działa
     * DOKŁADNIE tak jak pozostałe cztery narzędzia - bez żadnej specjalnej flagi/silk
     * touchu (świadomie odłożone na później).
     */
    void dajEwoluujacaLopate(Player player);

    /**
     * NAJWYŻSZY poziom (1-100) wśród WSZYSTKICH kilofów gracza (dowolnego typu,
     * gdziekolwiek w ekwipunku) - 0, jeśli gracz nie ma żadnego. Kilof ma OSOBNY system
     * poziomowania (PickaxeSkillManager) niż reszta narzędzi - stąd osobna metoda
     * zamiast ogólnego "poziom narzędzia".
     */
    int poziomKilofa(Player player);
}