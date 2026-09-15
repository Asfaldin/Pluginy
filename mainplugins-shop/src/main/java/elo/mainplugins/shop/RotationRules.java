package elo.mainplugins.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Losowanie rotacji: pozycje z puli, które niedawno były w ofercie, "odpoczywają" kilka rotacji. */
public final class RotationRules {

    private RotationRules() {}

    /** Losuje show numerów z puli (bez powtórzeń), najpierw spoza chłodzenia; brakujące z najkrótszym chłodzeniem. */
    public static List<Integer> pick(int poolSize, int show, Map<Integer, Integer> cooldown, Random random) {
        List<Integer> free = new ArrayList<>();
        List<Integer> resting = new ArrayList<>();
        for (int i = 0; i < poolSize; i++) {
            if (cooldown.getOrDefault(i, 0) > 0) resting.add(i);
            else free.add(i);
        }
        Collections.shuffle(free, random);
        List<Integer> out = new ArrayList<>(free.subList(0, Math.min(show, free.size())));
        if (out.size() < show) {
            resting.sort(Comparator.comparingInt(i -> cooldown.getOrDefault(i, 0)));
            for (int i : resting) {
                if (out.size() >= show) break;
                out.add(i);
            }
        }
        return out;
    }

    /** Chłodzenie po rotacji: każde -1 (zera znikają), wylosowane dostają rest. */
    public static Map<Integer, Integer> nextCooldown(Map<Integer, Integer> cooldown, List<Integer> picked, int rest) {
        Map<Integer, Integer> next = new HashMap<>();
        cooldown.forEach((i, left) -> {
            if (left - 1 > 0) next.put(i, left - 1);
        });
        for (int i : picked) next.put(i, rest);
        return next;
    }
}
