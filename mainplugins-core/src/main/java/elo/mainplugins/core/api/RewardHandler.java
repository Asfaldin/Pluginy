package elo.mainplugins.core.api;

import org.bukkit.entity.Player;

/**
 * Wydawanie typu nagrody, którego core nie zna (np. "key" - skrzynki, "title" - questy).
 * Handler sam wysyła graczowi wiadomość (chyba że reward.silent()).
 */
@FunctionalInterface
public interface RewardHandler {

    /** false = nie da się teraz wydać -> core użyje fallback tej nagrody. */
    boolean give(Player player, Reward reward);
}
