package elo.mainplugins.core.api;

import java.util.Set;
import java.util.UUID;

/**
 * Wspólne odblokowania graczy ("klucze do drzwi"). Dowolny plugin daje je nagrodą
 * "unlock: nazwa", dowolny plugin może ich wymagać (np. warp w Spawnie z requires-unlock).
 * Pluginy nic o sobie nie wiedzą - rozmawiają tylko z core. Nazwy bez rozróżniania wielkości liter.
 */
public interface UnlockService {

    boolean has(UUID player, String name);

    /** true = nowe odblokowanie, false = gracz już je miał (albo pusta nazwa). */
    boolean give(UUID player, String name);

    /** true = zabrano, false = gracz go nie miał. */
    boolean take(UUID player, String name);

    /** Odblokowania gracza - małe litery, posortowane. */
    Set<String> list(UUID player);
}
