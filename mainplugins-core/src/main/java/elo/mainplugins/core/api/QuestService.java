package elo.mainplugins.core.api;

import org.bukkit.Material;

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

    /**
     * Zarejestruj zakup w sklepie (mainplugins-shop) - pod quest.Requirement#BuyItemRequirement.
     * Wołane PO udanej transakcji (kasa pobrana, item w ekwipunku). customId null = zwykły
     * wanilijski Material (patrz CustomItemKeys w tym module).
     */
    void zarejestrujZakup(UUID uuid, Material material, String customId, int ilosc);

    /** Jak {@link #zarejestrujZakup}, ale sprzedaż w sklepie (skup) - pod Requirement#SellItemRequirement. */
    void zarejestrujSprzedaz(UUID uuid, Material material, String customId, int ilosc);

    /** Zarejestruj UDANE wystawienie oferty na Targu graczy (/targ wystaw) - pod Requirement#MarketListingsRequirement (licznik zdarzeń, niezależny od MarketService#maAktywnaOferte). */
    void zarejestrujWystawienieNaTarg(UUID uuid);
}