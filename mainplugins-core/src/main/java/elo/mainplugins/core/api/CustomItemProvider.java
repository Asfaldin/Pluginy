package elo.mainplugins.core.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Plugin, którego itemy mają stan (np. ewoluujące narzędzia), rejestruje się tym w
 * {@link CustomItemService#registerProvider}. Wtedy "custom: <id>" działa dla jego itemów
 * wszędzie tak samo jak dla zwykłych wpisów katalogu. Katalog items/ ma pierwszeństwo.
 */
public interface CustomItemProvider {

    Set<String> ids();

    /** player może być null (np. wydanie z konsoli bez gracza). Null = nie umiem stworzyć. */
    ItemStack create(String id, int amount, Player player);
}
