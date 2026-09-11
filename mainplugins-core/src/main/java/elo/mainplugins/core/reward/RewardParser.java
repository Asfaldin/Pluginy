package elo.mainplugins.core.reward;

import elo.mainplugins.core.api.Reward;
import elo.mainplugins.core.api.RewardService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Czyta listę "rewards:" z configu. Każdy wpis = mapa z dokładnie jednym kluczem typu
 * (+ opcjonalne amount / silent / fallback). Złe wpisy pomijane z ostrzeżeniem.
 * Istnienie custom itemów i handlerów pluginów sprawdzane dopiero przy wydawaniu
 * (mogą się zarejestrować później niż ten config jest czytany).
 */
public final class RewardParser {

    public static final Set<String> RESERVED = Set.of("amount", "silent", "fallback");

    private final Predicate<String> materialExists;
    private final Consumer<String> warn;

    public RewardParser(Predicate<String> materialExists, Consumer<String> warn) {
        this.materialExists = materialExists;
        this.warn = warn;
    }

    public List<Reward> parse(List<?> entries, String source) {
        List<Reward> out = new ArrayList<>();
        if (entries == null) return out;
        for (int i = 0; i < entries.size(); i++) {
            String where = source + " [" + (i + 1) + "]";
            if (!(entries.get(i) instanceof Map<?, ?> map)) {
                warn.accept(where + ": a reward must look like '- money: 100' - skipping.");
                continue;
            }
            Reward reward = parseOne(map, where);
            if (reward != null) out.add(reward);
        }
        return out;
    }

    private Reward parseOne(Map<?, ?> map, String where) {
        List<String> typeKeys = new ArrayList<>();
        for (Object key : map.keySet()) {
            String k = String.valueOf(key);
            if (!RESERVED.contains(k)) typeKeys.add(k);
        }
        if (typeKeys.size() != 1) {
            warn.accept(where + ": expected exactly one reward type, found " + typeKeys + " - skipping.");
            return null;
        }
        String type = typeKeys.get(0).toLowerCase(Locale.ROOT);
        Object value = map.get(typeKeys.get(0));

        int amount = 1;
        Object rawAmount = map.get("amount");
        if (rawAmount != null) {
            if (!(rawAmount instanceof Number n) || n.intValue() < 1) {
                warn.accept(where + ": 'amount' must be a whole number >= 1 - skipping.");
                return null;
            }
            amount = n.intValue();
        }
        boolean silent = Boolean.TRUE.equals(map.get("silent"));
        List<Reward> fallback = map.get("fallback") instanceof List<?> list ? parse(list, where + " fallback") : List.of();

        switch (type) {
            case RewardService.MONEY -> {
                if (!(value instanceof Number n) || n.doubleValue() <= 0) {
                    warn.accept(where + ": 'money' must be a number > 0 - skipping.");
                    return null;
                }
                value = n.doubleValue();
            }
            case RewardService.ITEM -> {
                String material = value == null ? "" : String.valueOf(value);
                if (!materialExists.test(material)) {
                    warn.accept(where + ": unknown item '" + material + "' - skipping.");
                    return null;
                }
                value = material.toUpperCase(Locale.ROOT);
            }
            case RewardService.CUSTOM, RewardService.COMMAND -> {
                if (value == null || String.valueOf(value).isBlank()) {
                    warn.accept(where + ": '" + type + "' needs a value - skipping.");
                    return null;
                }
                value = String.valueOf(value);
            }
            default -> {
                if (value == null) {
                    warn.accept(where + ": '" + type + "' needs a value - skipping.");
                    return null;
                }
            }
        }
        return new Reward(type, value, amount, silent, fallback);
    }
}
