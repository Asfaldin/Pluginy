package elo.mainplugins.core.api;

import java.util.List;

/**
 * Jedna nagroda ze wspólnego formatu "rewards:" (patrz {@link RewardService}).
 * type = "money" / "item" / "custom" / "command" albo typ zarejestrowany przez plugin
 * (np. "key", "title"). fallback = co dać zamiast, gdy tej nagrody nie da się wydać.
 */
public record Reward(String type, Object value, int amount, boolean silent, List<Reward> fallback) {

    public Reward {
        fallback = List.copyOf(fallback);
    }
}
