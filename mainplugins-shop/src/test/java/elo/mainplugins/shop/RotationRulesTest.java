package elo.mainplugins.shop;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RotationRulesTest {

    @Test
    void picksWithoutDuplicatesAndSkipsCooldown() {
        for (int seed = 0; seed < 50; seed++) {
            List<Integer> p = RotationRules.pick(10, 5, Map.of(0, 2, 1, 1, 2, 3), new Random(seed));
            assertEquals(5, p.size());
            assertEquals(5, new HashSet<>(p).size());
            assertFalse(p.contains(0));
            assertFalse(p.contains(1));
            assertFalse(p.contains(2));
        }
    }

    @Test
    void fallsBackToCooldownWhenPoolTooSmall() {
        List<Integer> p = RotationRules.pick(4, 3, Map.of(0, 5, 1, 2, 2, 1), new Random(1));
        assertEquals(3, p.size());
        assertTrue(p.contains(3));
        assertTrue(p.contains(2));   // najkrótsze chłodzenie idzie pierwsze
        assertTrue(p.contains(1));
        assertEquals(List.of(0, 1), RotationRules.pick(2, 5, Map.of(), new Random(1)).stream().sorted().toList());
        assertTrue(RotationRules.pick(0, 5, Map.of(), new Random(1)).isEmpty());
    }

    @Test
    void cooldownCountsDown() {
        Map<Integer, Integer> next = RotationRules.nextCooldown(Map.of(0, 1, 1, 3), List.of(4, 5), 5);
        assertEquals(Map.of(1, 2, 4, 5, 5, 5), next);
    }
}
