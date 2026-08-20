package elo.mainplugins.core.api;

import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Kontrakt rejestru custom itemów (custom-items.yml, patrz CustomItemManager w
 * mainplugins-core) - ten sam wzorzec dostępu co inne serwisy w CoreAPI (reszta
 * ekosystemu pobiera go przez {@link elo.mainplugins.core.CoreAPI#getCustomItemService()}),
 * ale w odróżnieniu od nich JEST zawsze zarejestrowany, gdy tylko mainplugins-core
 * jest włączony - każdy inny moduł ma go jako "depend" w plugin.yml, więc zawsze
 * ładuje się pierwszy. Wołający i tak powinien traktować null jako możliwy wynik
 * CoreAPI (np. bardzo wczesny etap startu serwera), zamiast zakładać go na sztywno.
 */
public interface CustomItemService {

    /**
     * Buduje NOWY ItemStack zarejestrowanego custom itemu (material/nazwa/lore/model
     * z custom-items.yml + tag custom-id w PersistentDataContainer, patrz CustomItemKeys)
     * - null, jeśli podane id nie istnieje w rejestrze.
     */
    ItemStack create(String id, int amount);

    /**
     * Czy dany id istnieje w rejestrze (custom-items.yml) - pod integracje typu sklep
     * (custom-id w sklep.yml), żeby odróżnić "znany" custom item od zwykłego znacznika
     * PDC bez wpisu w rejestrze (np. spawnery, generatory - patrz CustomItemKeys).
     */
    boolean exists(String id);

    /** Wszystkie zarejestrowane id, dokładnie tak jak zapisane w custom-items.yml - pod tab-completion (@dajcustom) i zewnętrzne UI. */
    Set<String> ids();

    /** Wczytuje custom-items.yml na nowo z dysku, bez restartu serwera - pod komendę @reloadcustomitems. */
    void reload();
}
