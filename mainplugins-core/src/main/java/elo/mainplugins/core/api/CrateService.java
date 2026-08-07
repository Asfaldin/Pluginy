package elo.mainplugins.core.api;

import org.bukkit.inventory.ItemStack;

/**
 * Opcjonalny kontrakt skrzynek - implementuje go i rejestruje w ServicesManager
 * wyłącznie mainplugins-crates. Ten sam wzorzec co {@link ToolsService}: reszta
 * ekosystemu (np. mainplugins-fishing, dający rzadkie skrzynki jako drop z łowienia)
 * pobiera go przez {@link elo.mainplugins.core.CoreAPI#getCrateService()}, które
 * zwraca null, jeśli mainplugins-crates nie jest wgrany/włączony - wołający musi
 * mieć na to sensowny fallback (np. nagroda zastępcza w monetach).
 */
public interface CrateService {

    /**
     * Tworzy przedmiot Tajemniczej Skrzynki danego tieru (1 = zwykła, 2/3 = rzadsze,
     * własna pula nagród) - gotowy do wręczenia graczowi (addItem/dropItemNaturally).
     */
    ItemStack stworzSkrzynke(int tier);

    /** Tworzy uniwersalny klucz - otwiera skrzynkę dowolnego tieru. */
    ItemStack stworzKlucz();
}