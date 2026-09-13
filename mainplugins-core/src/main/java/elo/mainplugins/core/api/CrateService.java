package elo.mainplugins.core.api;

import org.bukkit.inventory.ItemStack;

import java.util.Set;

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
     * Stare API (po numerze tieru) - skrzynka o tej pozycji w crates.yml (poza zakresem: pierwsza).
     * Nowe pluginy: {@link #createCrate(String, int)}.
     */
    ItemStack stworzSkrzynke(int tier);

    /** Stare API - klucz universal_key (albo klucz pierwszej skrzynki). Nowe pluginy: {@link #createKey(String, int)}. */
    ItemStack stworzKlucz();

    /** Skrzynka o danym id z crates.yml (null, gdy nieznana). */
    ItemStack createCrate(String id, int amount);

    /** Klucz o danym id z crates.yml (null, gdy nieznany). */
    ItemStack createKey(String id, int amount);

    /** Id wszystkich skrzynek, w kolejności z crates.yml. */
    Set<String> crateIds();

    /** Id wszystkich kluczy. */
    Set<String> keyIds();
}
