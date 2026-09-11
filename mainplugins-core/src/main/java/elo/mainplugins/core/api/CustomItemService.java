package elo.mainplugins.core.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Wspólny katalog itemów: wszystkie pliki plugins/MainpluginsCore/items/*.yml + itemy
 * dostawców ({@link CustomItemProvider}). Rejestruje go samo core - dostępny zawsze,
 * gdy core jest włączony. Id bez rozróżniania wielkości liter. Każdy stworzony item
 * nosi tag custom-id (CustomItemKeys.CUSTOM_ITEM_ID) - po nim pluginy go rozpoznają ({@link #idOf}).
 */
public interface CustomItemService {

    /** Nowy ItemStack itemu o danym id, albo null, gdy id nieznane. */
    ItemStack create(String id, int amount);

    /** Jak {@link #create(String, int)}, ale z graczem dla dostawców, którzy go potrzebują (może być null). */
    ItemStack create(String id, int amount, Player player);

    /** Czy id istnieje w katalogu lub u któregoś dostawcy. */
    boolean exists(String id);

    /** Wszystkie id (katalog + dostawcy), w pisowni z plików - pod tab-completion i aplikację. */
    Set<String> ids();

    /** Id z tagu custom-id na itemie, albo null, gdy to nie nasz item. */
    String idOf(ItemStack item);

    /** Dostawca itemów pluginu - wyrejestrowywany automatycznie, gdy plugin się wyłącza. */
    void registerProvider(Plugin owner, CustomItemProvider provider);

    /**
     * Dopisuje do items/<plugin>.yml te itemy z pliku resourcePath w jarze pluginu, których
     * jeszcze nie ma w katalogu (np. "items/defaults.yml"). Wołać w onEnable pluginu.
     */
    void registerDefaults(Plugin owner, String resourcePath);

    /** Wczytuje katalog items/ na nowo, bez restartu. */
    void reload();
}
