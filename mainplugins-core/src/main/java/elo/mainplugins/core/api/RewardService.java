package elo.mainplugins.core.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Jeden format nagród dla wszystkich pluginów (config: lista "rewards:").
 * Wbudowane typy: money, item (+amount), custom (+amount), command ({player} = nick), unlock (nazwa odblokowania).
 * Każdy wpis może mieć silent: true i fallback: [lista nagród].
 * Inne typy wydają pluginy przez {@link #registerType}. Brak handlera/itemu = fallback,
 * a bez fallbacku pominięcie z ostrzeżeniem w logu - nigdy wyjątek.
 */
public interface RewardService {

    String MONEY = "money";
    String ITEM = "item";
    String CUSTOM = "custom";
    String COMMAND = "command";
    /** Odblokowanie z core (UnlockService) - wartość = nazwa, np. "unlock: kowal". */
    String UNLOCK = "unlock";

    /** entries = np. config.getList("rewards"); source = opis miejsca do logów, np. "crates.yml epic.rewards". */
    List<Reward> parse(List<?> entries, String source);

    void give(Player player, List<Reward> rewards);

    /** Rejestruje własny typ nagrody pluginu - wyrejestrowywany automatycznie, gdy plugin się wyłącza. */
    void registerType(Plugin owner, String type, RewardHandler handler);
}
